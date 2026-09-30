package com.minehost.app.data

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.io.DEFAULT_BUFFER_SIZE

class CoreDownloader(context: Context) {
    private val appContext = context.applicationContext
    private val importer = CoreImporter(appContext)

    suspend fun download(
        asset: CoreAsset,
        onProgress: (Float?) -> Unit
    ): CoreImporter.ImportedCore = withContext(Dispatchers.IO) {
        val downloadUrl = asset.downloadUrl ?: error("This project has no direct download asset")
        val url = URL(downloadUrl)
        requireAllowedUrl(url)

        val temporaryDirectory = File(appContext.cacheDir, "core-downloads").apply { mkdirs() }
        var lastError: Exception? = null

        repeat(MAX_ATTEMPTS) { attempt ->
            val temporaryFile = File.createTempFile("core", ".part", temporaryDirectory)
            try {
                val imported = transfer(url, temporaryFile, asset, onProgress)
                temporaryFile.delete()
                return@withContext imported
            } catch (error: CancellationException) {
                temporaryFile.delete()
                throw error
            } catch (error: Exception) {
                temporaryFile.delete()
                lastError = error
                if (attempt < MAX_ATTEMPTS - 1) {
                    // Unknown progress while retrying, then back off a little.
                    onProgress(null)
                    delay(RETRY_BACKOFF_MS * (attempt + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("The download could not be completed")
    }

    /** Single transfer attempt: streams to [destination], hashing as it goes. */
    private fun transfer(
        url: URL,
        destination: File,
        asset: CoreAsset,
        onProgress: (Float?) -> Unit
    ): CoreImporter.ImportedCore {
        val connection = openAllowedConnection(url)
        try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) error(describeHttpFailure(responseCode))
            val totalBytes = connection.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
            val digest = MessageDigest.getInstance(asset.checksumAlgorithm.ifBlank { "SHA-256" })
            var downloadedBytes = 0L
            connection.inputStream.buffered().use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloadedBytes += count
                        require(downloadedBytes <= MAX_DOWNLOAD_BYTES) { "Download is larger than the 512 MB safety limit" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        onProgress(totalBytes?.let { downloadedBytes.toFloat() / it }?.coerceIn(0f, 1f))
                    }
                }
            }
            onProgress(1f)
            require(downloadedBytes > 0L) { "The server returned an empty file" }

            val actualChecksum = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            if (!asset.checksum.isNullOrBlank() && !actualChecksum.equals(asset.checksum, ignoreCase = true)) {
                error("Checksum verification failed; the file was not installed")
            }
            if (asset.fileName.endsWith(".jar", ignoreCase = true)) {
                require(isZipArchive(destination)) { "Downloaded JAR is not a valid ZIP archive" }
            }
            return importer.installDownloaded(destination, asset.fileName)
        } finally {
            connection.disconnect()
        }
    }

    private fun openAllowedConnection(startUrl: URL): HttpURLConnection {
        var currentUrl = startUrl
        repeat(MAX_REDIRECTS + 1) {
            requireAllowedUrl(currentUrl)
            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                // Ask for the raw body so Content-Length matches the bytes we receive.
                setRequestProperty("Accept-Encoding", "identity")
            }
            val responseCode = connection.responseCode
            if (responseCode !in 300..399) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            require(!location.isNullOrBlank()) { "Download redirect did not include a destination" }
            currentUrl = URL(currentUrl, location)
        }
        error("Too many download redirects")
    }

    private fun requireAllowedUrl(url: URL) {
        requireAllowedDownloadUrl(url)?.let { reason -> error(reason) }
    }

    private fun describeHttpFailure(code: Int): String = when (code) {
        401, 403 -> "The release host refused the download (HTTP $code). Try another platform or version."
        404 -> "That build is no longer published (HTTP 404). Pick a different version."
        429 -> "The release host is rate limiting downloads (HTTP 429). Wait a minute and retry."
        in 500..599 -> "The release host had a server error (HTTP $code). Try again shortly."
        else -> "Download failed (HTTP $code)"
    }

    private fun isZipArchive(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val header = ByteArray(2)
            input.read(header) == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    private companion object {
        const val MAX_REDIRECTS = 5
        const val MAX_ATTEMPTS = 3
        const val RETRY_BACKOFF_MS = 1_500L
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_DOWNLOAD_BYTES = 512L * 1024L * 1024L
        const val USER_AGENT = "MineHost/1.1 (Android server catalog)"
    }
}

/** Hosts MineHost is willing to fetch a server core from. */
internal val ALLOWED_DOWNLOAD_HOSTS = setOf(
    "api.purpurmc.org",
    "fill.papermc.io",
    "fill-data.papermc.io",
    "github.com",
    "launchermeta.mojang.com",
    "piston-meta.mojang.com",
    "maven.minecraftforge.net",
    "meta.fabricmc.net",
    "piston-data.mojang.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com"
)

/**
 * Returns a human readable refusal reason, or null when [url] is safe to fetch.
 * Only HTTPS is accepted and the host must match the allowlist exactly or be a
 * subdomain of it.
 */
internal fun requireAllowedDownloadUrl(url: URL): String? {
    if (!url.protocol.equals("https", ignoreCase = true)) {
        return "Only HTTPS catalog downloads are allowed"
    }
    val host = url.host.lowercase()
    val allowed = ALLOWED_DOWNLOAD_HOSTS.any { host == it || host.endsWith(".$it") }
    return if (allowed) null else "Download host $host is not in the MineHost allowlist"
}
