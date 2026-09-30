package com.minehost.app.data

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class WorldBackupResult(
    val importedCorePath: String?,
    val fileCount: Int
)

class WorldBackupManager(context: Context) {
    private val appContext = context.applicationContext

    /** Legacy single-workspace layout, used by profiles without an id. */
    private val legacyRoot = File(appContext.filesDir, "server")

    private fun serverRootFor(config: ServerConfig): File {
        val profileId = config.profileId ?: return legacyRoot
        return File(File(appContext.filesDir, "servers"), profileId)
    }

    suspend fun export(uri: Uri, config: ServerConfig): Int = withContext(Dispatchers.IO) {
        val serverRoot = serverRootFor(config)
        require(serverRoot.exists()) { "There is no server workspace to export yet." }
        val output = appContext.contentResolver.openOutputStream(uri)
            ?: error("MineHost could not open the selected backup destination.")
        var fileCount = 0
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            serverRoot.walkTopDown()
                .filter { it.isFile && !shouldExclude(it.relativeTo(serverRoot)) }
                .forEach { file ->
                    val entryName = file.relativeTo(serverRoot).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                    fileCount++
                }
        }
        require(fileCount > 0) { "The server workspace did not contain any backupable files." }
        fileCount
    }

    suspend fun import(uri: Uri, config: ServerConfig): WorldBackupResult = withContext(Dispatchers.IO) {
        val serverRoot = serverRootFor(config)
        val staging = File(appContext.filesDir, "server-import-staging")
        // Keep exactly one rollback copy: without this, every restore leaves a
        // full workspace snapshot behind and app storage grows without bound.
        appContext.filesDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(ROLLBACK_PREFIX) }
            ?.forEach { it.deleteRecursively() }
        val previous = File(appContext.filesDir, "$ROLLBACK_PREFIX${System.currentTimeMillis()}")
        staging.deleteRecursively()
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: error("MineHost could not open the selected backup file.")
            val fileCount = extract(input, staging)
            require(fileCount > 0) { "The selected ZIP is empty." }
            val packRoot = findPackRoot(staging)
            require(
                File(packRoot, "world").isDirectory || File(packRoot, "server.properties").isFile
            ) {
                "This ZIP does not look like a Minecraft server workspace or world backup."
            }

            if (serverRoot.exists() && !serverRoot.renameTo(previous)) {
                error("MineHost could not safely replace the current workspace.")
            }
            if (!packRoot.renameTo(serverRoot)) {
                if (previous.exists()) previous.renameTo(serverRoot)
                error("MineHost could not activate the imported workspace.")
            }

            val core = findImportedCore(serverRoot)
            WorldBackupResult(core?.absolutePath, fileCount)
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extract(input: java.io.InputStream, destination: File): Int {
        var entryCount = 0
        var totalBytes = 0L
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "The backup contains too many files." }
                val name = entry.name.replace('\\', '/')
                require(name.isNotBlank() && !name.startsWith("/") && name.split('/').none { it == ".." }) {
                    "The backup contains an unsafe file path."
                }
                val output = File(destination, name)
                val destinationPath = destination.canonicalPath + File.separator
                require(output.canonicalPath.startsWith(destinationPath)) {
                    "The backup contains an unsafe file path."
                }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(output)).use { target ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            totalBytes += count
                            require(totalBytes <= MAX_UNCOMPRESSED_BYTES) {
                                "The backup expands beyond the safety limit."
                            }
                            target.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return entryCount
    }

    private fun shouldExclude(relative: File): Boolean {
        val path = relative.invariantSeparatorsPath.lowercase()
        return path == "logs" || path.startsWith("logs/") ||
            path == "cache" || path.startsWith("cache/") ||
            path.endsWith(".tmp") || path.endsWith(".lock")
    }

    private fun findPackRoot(staging: File): File {
        if (File(staging, "world").isDirectory || File(staging, "server.properties").isFile) return staging
        return staging.listFiles()?.firstOrNull { child ->
            child.isDirectory && (File(child, "world").isDirectory || File(child, "server.properties").isFile)
        } ?: staging
    }

    private fun findImportedCore(root: File): File? {
        val preferred = listOf("server.jar", "paper.jar", "fabric-server-launch.jar")
        preferred.firstOrNull { name ->
            File(root, name).let { it.isFile && it.length() > 0L }
        }?.let { return File(root, it) }
        return root.listFiles()?.firstOrNull { file ->
            file.isFile && file.extension.equals("jar", ignoreCase = true) && file.length() > 0L
        }
    }

    private companion object {
        const val MAX_ENTRIES = 100_000
        const val MAX_UNCOMPRESSED_BYTES = 2L * 1024L * 1024L * 1024L
        const val ROLLBACK_PREFIX = "server-before-import-"
    }
}
