package com.minehost.app.data

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class CoreImporter(private val context: Context) {
    data class ImportedCore(val path: String, val displayName: String)

    suspend fun import(uri: Uri): ImportedCore = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val displayName = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor: Cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        } ?: "minecraft-server"

        val directory = File(context.filesDir, "cores").apply { mkdirs() }
        val destination = uniqueDestination(directory, displayName)
        resolver.openInputStream(uri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Unable to read the selected server core")
        destination.setExecutable(true, false)

        ImportedCore(destination.absolutePath, displayName)
    }

    fun installDownloaded(source: File, displayName: String): ImportedCore {
        require(source.exists() && source.length() > 0L) { "Downloaded core is empty" }
        val directory = File(context.filesDir, "cores").apply { mkdirs() }
        val destination = uniqueDestination(directory, displayName)
        source.copyTo(destination, overwrite = true)
        destination.setExecutable(true, false)
        return ImportedCore(destination.absolutePath, displayName)
    }

    private fun uniqueDestination(directory: File, displayName: String): File {
        val safeName = displayName
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "minecraft-server" }
        return File(directory, "${System.currentTimeMillis()}_$safeName")
    }
}
