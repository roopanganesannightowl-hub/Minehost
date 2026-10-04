package com.minehost.app.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

/**
 * Copies plugin JARs picked from device storage into the app's private
 * `plugins/` folder. Nothing is installed into a workspace here — the
 * workspace copy happens on the next server start, like every other pack, so a
 * running server is never mutated underneath itself.
 */
class PluginImporter(private val context: Context) {

    data class ImportedPlugin(
        val path: String,
        val fileName: String,
        val descriptor: PluginJarRules.Descriptor
    )

    suspend fun import(uri: Uri): ImportedPlugin = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor: Cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        } ?: "plugin.jar"

        val directory = File(context.filesDir, "plugins").apply { mkdirs() }
        // Staged under a temporary name so a rejected JAR never lands as a
        // half-written plugin the server would try to load later.
        val staging = File(directory, ".incoming-${System.currentTimeMillis()}.jar")
        try {
            val sizeBytes = resolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
                staging.length()
            } ?: error("MineHost could not read the selected plugin file")

            val header = ByteArray(2)
            val isZip = staging.inputStream().use { input ->
                input.read(header) == 2 && PluginJarRules.isZipArchive(header)
            }
            val entries = readEntryNames(staging)
            PluginJarRules.rejectionReason(
                fileName = displayName,
                sizeBytes = sizeBytes,
                isZip = isZip,
                entryNames = entries
            )?.let { reason ->
                error(reason)
            }

            val descriptor = readDescriptor(staging, entries)
            val safeName = PluginJarRules.safeFileName(displayName)
            val destination = File(directory, safeName)
            if (destination.canonicalPath != staging.canonicalPath) {
                // Rename is cheap and atomic; fall back to a copy if the
                // filesystem refuses it so a good plugin is never lost.
                if (!staging.renameTo(destination)) {
                    staging.copyTo(destination, overwrite = true)
                }
            }
            ImportedPlugin(destination.absolutePath, safeName, descriptor)
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    /** Deletes a previously imported plugin JAR from the app's own storage. */
    fun delete(path: String) {
        val file = File(path)
        val root = File(context.filesDir, "plugins").canonicalFile
        // Only ever delete inside the app's own plugins folder.
        if (file.canonicalFile.parentFile == root) file.delete()
    }

    private fun readEntryNames(jar: File): List<String> = runCatching {
        ZipFile(jar).use { zip ->
            zip.entries().asSequence().take(PluginJarRules.MAX_ENTRIES + 1).map { it.name }.toList()
        }
    }.getOrDefault(emptyList())

    private fun readDescriptor(jar: File, entries: List<String>): PluginJarRules.Descriptor =
        runCatching {
            ZipFile(jar).use { zip ->
                val entry = entries.firstNotNullOfOrNull { name ->
                    if (name !in PluginJarRules.DESCRIPTOR_ENTRIES) null else zip.getEntry(name)
                } ?: return@use PluginJarRules.Descriptor()
                val text = zip.getInputStream(entry).use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                }
                PluginJarRules.readDescriptor(text)
            }
        }.getOrDefault(PluginJarRules.Descriptor())
}