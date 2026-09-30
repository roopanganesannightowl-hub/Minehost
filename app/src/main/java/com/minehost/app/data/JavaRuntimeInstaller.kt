package com.minehost.app.data

import android.content.Context
import android.os.Build
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class JavaRuntimeInstallResult(
    val executablePath: String,
    val runtimeRoot: String,
    val versionOutput: String
)

/**
 * Installs an ARM64 Android-native OpenJDK 21 runtime into app-private
 * storage, optimised for big archives on mobile networks:
 *
 *  - **Resumable download** — bytes land in a `.part` file and every retry
 *    continues from where the connection dropped (HTTP Range), so a flaky
 *    network never restarts the ~197 MB from zero.
 *  - **Bounded retries with backoff** — transient failures are absorbed
 *    transparently; the checksum decides whether the bytes are trustworthy.
 *  - **Parallel extraction** — archive entries are unpacked by a worker pool
 *    sized to the device's cores instead of a single thread.
 *  - **Large sequential buffers** — 64 KiB reads/writes throughout.
 *
 * The archive is verified by exact size + SHA-256 before anything is
 * selected, and the extracted runtime must pass a `java -version` self-test
 * before it is considered installed.
 */
class JavaRuntimeInstaller(context: Context) {
    private val appContext = context.applicationContext

    suspend fun install(onProgress: (Float) -> Unit): JavaRuntimeInstallResult = withContext(Dispatchers.IO) {
        requireArm64Device()

        val runtimeParent = File(appContext.filesDir, "java-runtime").apply { mkdirs() }
        val target = File(runtimeParent, RUNTIME_DIRECTORY)
        // A previously installed runtime short-circuits everything else.
        findJava(target)?.let { existing ->
            val root = runtimeRootFor(existing)
            val result = runCatching { verifyJava(existing, root) }.getOrNull()
            if (result != null) {
                onProgress(1f)
                return@withContext JavaRuntimeInstallResult(existing.absolutePath, root.absolutePath, result)
            }
        }

        val available = StatFs(appContext.filesDir.absolutePath).availableBytes
        require(available >= REQUIRED_FREE_BYTES) {
            "This runtime needs about 700 MB of free storage. Free space and try again."
        }

        val partial = File(appContext.cacheDir, PARTIAL_ARCHIVE_NAME)
        val staging = File(runtimeParent, "$RUNTIME_DIRECTORY-staging")
        try {
            downloadArchive(partial, onProgress)
            onProgress(DOWNLOAD_PROGRESS_FRACTION)
            staging.deleteRecursively()
            extractZipParallel(partial, staging, onProgress)
            val stagedJava = findJava(staging) ?: error("The runtime archive did not contain bin/java.")
            val stagedRoot = runtimeRootFor(stagedJava)
            makeExecutables(stagedRoot)
            verifyJava(stagedJava, stagedRoot)

            if (target.exists()) target.deleteRecursively()
            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = true)
                staging.deleteRecursively()
            }

            val finalJava = findJava(target) ?: error("The installed runtime could not be found.")
            val finalRoot = runtimeRootFor(finalJava)
            makeExecutables(finalRoot)
            val finalVersion = verifyJava(finalJava, finalRoot)
            // Success: the partial archive is no longer needed.
            partial.delete()
            onProgress(1f)
            JavaRuntimeInstallResult(finalJava.absolutePath, finalRoot.absolutePath, finalVersion)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun requireArm64Device() {
        val supported = Build.SUPPORTED_ABIS.any { it.equals("arm64-v8a", ignoreCase = true) }
        require(supported) {
            "This bundled runtime currently supports ARM64 Android devices only."
        }
    }

    // ------------------------------------------------------------------
    // Download: resumable, checksum-verified, bounded retries
    // ------------------------------------------------------------------

    private fun downloadArchive(partial: File, onProgress: (Float) -> Unit) {
        var lastError: Exception? = null
        // GitHub first, then mirrors. Every candidate is pinned to the exact
        // bytes below, so a mirror can only serve the identical archive.
        var firstFailure: String? = null
        for (url in ARCHIVE_URLS) {
            for (attempt in 1..DOWNLOAD_ATTEMPTS) {
                try {
                    downloadOnce(URL(url), partial, onProgress)
                    // Checksum only when the archive is byte-complete.
                    if (partial.length() == EXPECTED_ARCHIVE_BYTES) {
                        verifyChecksum(partial)
                        return
                    }
                    throw IOException(
                        "The Java runtime archive was incomplete (${partial.length()} bytes)."
                    )
                } catch (error: IOException) {
                    lastError = error
                    if (firstFailure == null) firstFailure = error.message
                    if (error.message?.contains("checksum", ignoreCase = true) == true) {
                        // Corruption is not worth retrying the same bytes; drop
                        // the partial so the next attempt starts fresh.
                        partial.delete()
                        break
                    }
                    // Otherwise the .part file survives so the next attempt resumes.
                    if (attempt < DOWNLOAD_ATTEMPTS) {
                        try {
                            Thread.sleep(retryBackoffMs(attempt))
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IOException("Java runtime download interrupted.", error)
                        }
                    }
                }
            }
            // A partially downloaded prefix only matches the primary origin's
            // byte layout; drop it before trying another host.
            partial.delete()
        }
        throw IOException(
            firstFailure?.let { "$it — all Java runtime download servers failed." }
                ?: "All Java runtime download servers failed."
        )
    }

    private fun downloadOnce(url: URL, partial: File, onProgress: (Float) -> Unit) {
        var connection: HttpURLConnection? = null
        try {
            var resumeFrom = 0L
            if (partial.exists()) {
                resumeFrom = partial.length()
                // A partial larger than expected cannot be right.
                if (resumeFrom >= EXPECTED_ARCHIVE_BYTES) {
                    partial.delete()
                    resumeFrom = 0L
                }
            }
            connection = openAllowedConnection(url, resumeFrom)
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("The Java runtime download failed ($code).")

            val resuming = code == HttpURLConnection.HTTP_PARTIAL && resumeFrom > 0
            if (resumeFrom > 0 && !resuming) {
                // Server ignored the Range request; start over cleanly.
                partial.delete()
                resumeFrom = 0L
            }
            val totalBytes = connection.getHeaderField("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
                ?.let { it + resumeFrom } ?: EXPECTED_ARCHIVE_BYTES

            var downloaded = resumeFrom
            var lastReportedBytes = downloaded
            onProgress((downloaded.toFloat() / totalBytes).coerceIn(0f, DOWNLOAD_PROGRESS_FRACTION))

            val digest = MessageDigest.getInstance("SHA-256")
            if (resuming) {
                // Hash the already-downloaded prefix so the final checksum
                // covers the whole archive, not just the new tail.
                digestChunkFromStart(partial, digest)
            }

            if (!resuming) partial.delete()
            RandomAccessFile(partial, "rw").use { output ->
                output.seek(downloaded)
                connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        require(downloaded <= MAX_ARCHIVE_BYTES) { "The Java runtime archive is too large." }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        if (downloaded - lastReportedBytes >= REPORT_INTERVAL_BYTES || downloaded >= totalBytes) {
                            lastReportedBytes = downloaded
                            onProgress(
                                ((downloaded.toFloat() / totalBytes) * DOWNLOAD_PROGRESS_FRACTION)
                                    .coerceIn(0f, DOWNLOAD_PROGRESS_FRACTION)
                            )
                        }
                    }
                }
            }
            if (downloaded != EXPECTED_ARCHIVE_BYTES) {
                throw IOException("The Java runtime archive was incomplete ($downloaded bytes).")
            }
        } finally {
            connection?.disconnect()
        }
    }

    /** Feeds the first [length] bytes of the partial file into the digest. */
    private fun digestChunkFromStart(partial: File, digest: MessageDigest) {
        FileInputStream(partial).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }

    private fun verifyChecksum(archive: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(archive).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        require(actual.equals(EXPECTED_SHA256, ignoreCase = true)) {
            "Java runtime checksum verification failed. Nothing was installed."
        }
    }

    private fun openAllowedConnection(startUrl: URL, resumeFrom: Long): HttpURLConnection {
        var currentUrl = startUrl
        repeat(MAX_REDIRECTS + 1) {
            require(currentUrl.protocol.equals("https", ignoreCase = true)) { "The runtime URL must use HTTPS." }
            require(currentUrl.host.lowercase() in ALLOWED_HOSTS) { "The runtime host is not trusted." }
            val connection = (currentUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 90_000
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("User-Agent", "MineHost/1.0 (Android runtime setup)")
                if (resumeFrom > 0) setRequestProperty("Range", "bytes=$resumeFrom-")
            }
            val code = connection.responseCode
            if (code !in 300..399) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            require(!location.isNullOrBlank()) { "The runtime download redirect was invalid." }
            currentUrl = URL(currentUrl, location)
        }
        error("Too many runtime download redirects.")
    }

    // ------------------------------------------------------------------
    // Extraction: parallel per-entry workers
    // ------------------------------------------------------------------

    private fun extractZipParallel(archive: File, destination: File, onProgress: (Float) -> Unit) {
        destination.mkdirs()
        ZipFile(archive).use { zip ->
            val all = zip.entries().asSequence().toList()
            require(all.size <= MAX_ENTRIES) { "The runtime archive contains too many files." }
            all.forEach { entry -> validateEntryName(entry, destination) }
            val fileEntries = all.filter { !it.isDirectory }
            if (fileEntries.isEmpty()) return
            // Directories first, single pass, cheap.
            all.filter { it.isDirectory }.forEach { File(destination, it.name).mkdirs() }

            val uncompressedBytes = AtomicLong(0L)
            val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, EXTRACTION_WORKERS_MAX)
            val pool = Executors.newFixedThreadPool(workers)
            val failures = ConcurrentLinkedQueue<Exception>()
            val entriesDone = AtomicLong(0L)
            try {
                val futures = mutableListOf<Future<*>>()
                for (entry in fileEntries) {
                    futures.add(pool.submit {
                        try {
                            extractEntry(zip, entry, destination, uncompressedBytes)
                        } catch (error: Exception) {
                            failures.add(error)
                        }
                        val done = entriesDone.incrementAndGet()
                        if (done % PROGRESS_ENTRY_INTERVAL == 0L || done == fileEntries.size.toLong()) {
                            val extractionSpan = 1f - DOWNLOAD_PROGRESS_FRACTION
                            onProgress(
                                DOWNLOAD_PROGRESS_FRACTION +
                                    ((done.toFloat() / fileEntries.size) * extractionSpan)
                                    .coerceIn(0f, extractionSpan)
                            )
                        }
                    })
                }
                // Every worker must finish (or fail) before we judge the result.
                futures.forEach { future ->
                    try {
                        future.get(EXTRACTION_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    } catch (error: Exception) {
                        failures.add(IOException("The runtime extraction stalled.", error))
                    }
                }
            } finally {
                pool.shutdownNow()
            }
            failures.firstOrNull()?.let { failure ->
                throw IOException("The runtime archive could not be extracted.", failure)
            }
        }
    }

    private fun extractEntry(
        zip: ZipFile,
        entry: ZipEntry,
        destination: File,
        uncompressedBytes: AtomicLong
    ) {
        val output = File(destination, entry.name)
        output.parentFile?.mkdirs()
        zip.getInputStream(entry).use { input ->
            BufferedOutputStream(FileOutputStream(output), BUFFER_SIZE).use { target ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    val total = uncompressedBytes.addAndGet(count.toLong())
                    require(total <= MAX_UNCOMPRESSED_BYTES) {
                        "The runtime expands beyond the safety limit."
                    }
                    target.write(buffer, 0, count)
                }
            }
        }
    }

    private fun validateEntryName(entry: ZipEntry, destination: File) {
        val name = entry.name.replace('\\', '/')
        require(name.isNotBlank() && !name.startsWith("/") && name.split('/').none { it == ".." }) {
            "The runtime archive contains an unsafe path."
        }
        val resolved = File(destination, name).canonicalPath
        require(resolved.startsWith(destination.canonicalPath + File.separator)) {
            "The runtime archive contains an unsafe path."
        }
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private fun findJava(root: File): File? = root.walkTopDown()
        .filter { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
        .firstOrNull()

    private fun runtimeRootFor(javaFile: File): File = javaFile.parentFile!!.parentFile!!

    private fun makeExecutables(root: File) {
        root.walkTopDown()
            .filter { it.isFile && it.parentFile?.name == "bin" }
            .forEach { it.setExecutable(true, false) }
    }

    private fun verifyJava(executable: File, runtimeRoot: File): String {
        val process = try {
            ProcessBuilder(executable.absolutePath, "-version")
                .directory(runtimeRoot)
                .redirectErrorStream(true)
                .apply {
                    environment()["JAVA_HOME"] = runtimeRoot.absolutePath
                    environment()["PATH"] = "${runtimeRoot.absolutePath}/bin:${environment()["PATH"]}"
                    environment()["LD_LIBRARY_PATH"] = listOf(
                        "${runtimeRoot.absolutePath}/lib",
                        "${runtimeRoot.absolutePath}/lib/server",
                        environment()["LD_LIBRARY_PATH"].orEmpty()
                    ).joinToString(":").trimEnd(':')
                }
                .start()
        } catch (error: Exception) {
            throw IOException("The Android-native Java runtime could not start on this device.", error)
        }

        return try {
            val finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                error("The Android-native Java runtime did not respond.")
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            if (process.exitValue() != 0) {
                throw IOException(output.lineSequence().firstOrNull().orEmpty().ifBlank {
                    "The Android-native Java runtime failed its self-check."
                })
            }
            output.lineSequence().firstOrNull { it.isNotBlank() }?.take(180)
                .orEmpty()
                .ifBlank { "Java runtime self-check passed." }
        } finally {
            process.destroy()
        }
    }

    private fun retryBackoffMs(attempt: Int): Long = (2_000L * attempt).coerceAtMost(10_000L)

    private companion object {
        const val RUNTIME_DIRECTORY = "openjdk-21.0.1"
        const val PARTIAL_ARCHIVE_NAME = "openjdk-21.0.1-aarch64.zip.part"
        /**
         * Byte-identical copies of the runtime archive. GitHub is tried first
         * (the primary release host); the mirrors only kick in when GitHub is
         * unreachable. Every candidate is checksum-pinned, so any of them can
         * only ever deliver the exact same archive.
         */
        val ARCHIVE_URLS = listOf(
            "https://github.com/zryyoung/openjdk-Termux/releases/download/openjdk-21.0.1/openjdk-21.0.1-aarch64.zip",
            "https://ghproxy.net/https://github.com/zryyoung/openjdk-Termux/releases/download/openjdk-21.0.1/openjdk-21.0.1-aarch64.zip",
            "https://gh-proxy.com/https://github.com/zryyoung/openjdk-Termux/releases/download/openjdk-21.0.1/openjdk-21.0.1-aarch64.zip"
        )
        const val EXPECTED_ARCHIVE_BYTES = 197_199_642L
        const val EXPECTED_SHA256 = "ad9918b5ba34a1460e1a2abfca3f744647a98fe72b2865c6f38aa620cc0dba5a"
        const val REQUIRED_FREE_BYTES = 700L * 1024L * 1024L
        const val MAX_ARCHIVE_BYTES = 300L * 1024L * 1024L
        const val MAX_UNCOMPRESSED_BYTES = 600L * 1024L * 1024L
        const val MAX_ENTRIES = 20_000
        const val MAX_REDIRECTS = 5
        const val REPORT_INTERVAL_BYTES = 512L * 1024L
        const val BUFFER_SIZE = 64 * 1024
        const val DOWNLOAD_ATTEMPTS = 4

        /** Download occupies the first part of the progress bar. */
        const val DOWNLOAD_PROGRESS_FRACTION = 0.75f

        /** Parallel extraction threads; bounded to avoid memory pressure. */
        const val EXTRACTION_WORKERS_MAX = 6

        /** Extraction progress is reported in coarse steps to limit recomposition. */
        const val PROGRESS_ENTRY_INTERVAL = 64L

        /** Upper bound for waiting on a single entry task (huge entries included). */
        const val EXTRACTION_TASK_TIMEOUT_SECONDS = 120L

        val ALLOWED_HOSTS = setOf(
            "github.com",
            "release-assets.githubusercontent.com",
            "objects.githubusercontent.com",
            "ghproxy.net",
            "gh-proxy.com"
        )
    }
}
