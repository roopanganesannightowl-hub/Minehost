package com.minehost.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Reimplements the workspace plugin sync from ServerRuntime so the rules that
 * caused a boot-breaking crash stay pinned: a first start has no manifest, and
 * a removed plugin must actually leave the workspace.
 *
 * ServerWorkspace itself is private and needs a Context, so the file handling
 * is mirrored here rather than reflected.
 */
class PluginWorkspaceSyncTest {

    private val staging = File(System.getProperty("java.io.tmpdir"), "mh-plugin-src").apply {
        deleteRecursively()
        mkdirs()
        deleteOnExit()
    }

    /** Writes a real JAR under the exact file name, as a picked plugin has. */
    private fun jar(name: String, pluginYml: Boolean = true): File {
        val file = File(staging, name)
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
            zip.write("Manifest-Version: 1.0\n".toByteArray())
            zip.closeEntry()
            if (pluginYml) {
                zip.putNextEntry(ZipEntry("plugin.yml"))
                zip.write("name: Test\nversion: 1.0\n".toByteArray())
                zip.closeEntry()
            }
        }
        file.deleteOnExit()
        return file
    }

    /** The sync as ServerRuntime performs it, minus the Context path check. */
    private fun sync(workspace: File, pluginPaths: List<String>) {
        val pluginDirectory = File(workspace, "plugins").apply { mkdirs() }
        val managed = File(workspace, ".minehost-plugins")
        val previous = if (managed.isFile) {
            runCatching { managed.readLines() }.getOrDefault(emptyList())
                .map { it.trim() }.filter { it.isNotBlank() }.toSet()
        } else {
            emptySet()
        }
        val current = LinkedHashSet<String>()
        for (path in pluginPaths) {
            val source = File(path)
            if (!source.isFile || source.length() == 0L) continue
            val name = source.name
            val target = File(pluginDirectory, name)
            if (source.canonicalPath != target.canonicalPath &&
                (!target.exists() || target.length() != source.length())
            ) {
                target.delete()
                source.copyTo(target, overwrite = true)
            }
            current += name
        }
        for (name in previous - current) {
            File(pluginDirectory, name).takeIf { it.isFile }?.delete()
        }
        managed.writeText(current.joinToString("\n"))
    }

    private fun workspace(): File = File.createTempFile("workspace", "").let {
        it.delete()
        it.mkdirs()
        it.deleteOnExit()
        it
    }

    @Test
    fun `first start with no manifest must not throw`() {
        // The regression: readLines() on a missing manifest threw ENOENT and
        // "Could not prepare the server workspace" stopped the server booting.
        val root = workspace()
        assertFalse(File(root, ".minehost-plugins").exists())
        sync(root, emptyList()) // must simply return
        assertTrue(File(root, ".minehost-plugins").isFile)
    }

    @Test
    fun `first start with plugins copies them into the workspace`() {
        val root = workspace()
        val plugin = jar("Vault.jar")
        sync(root, listOf(plugin.absolutePath))
        val installed = File(root, "plugins/Vault.jar")
        assertTrue(installed.isFile)
        assertEquals(plugin.length(), installed.length())
        assertTrue(File(root, ".minehost-plugins").readText().contains("Vault.jar"))
    }

    @Test
    fun `removing a plugin deletes it from the workspace`() {
        val root = workspace()
        val keep = jar("Keep.jar")
        val drop = jar("Drop.jar")
        sync(root, listOf(keep.absolutePath, drop.absolutePath))
        assertTrue(File(root, "plugins/Drop.jar").isFile())

        sync(root, listOf(keep.absolutePath))
        // The bug this guards: without pruning, an uninstall was cosmetic and
        // Paper kept loading the JAR on every later boot.
        assertFalse(File(root, "plugins/Drop.jar").exists())
        assertTrue(File(root, "plugins/Keep.jar").isFile())
    }

    @Test
    fun `a changed jar of the same name is replaced`() {
        val root = workspace()
        val first = jar("Same.jar")
        sync(root, listOf(first.absolutePath))
        val staleLength = File(root, "plugins/Same.jar").length()

        val second = jar("Same.jar").apply {
            // Make the replacement a different size so the length check trips.
            writeBytes(readBytes() + ByteArray(64))
        }
        sync(root, listOf(second.absolutePath))
        val installed = File(root, "plugins/Same.jar")
        assertEquals(second.length(), installed.length())
        assertTrue(installed.length() != staleLength)
    }

    @Test
    fun `missing source files are skipped without failing the sync`() {
        val root = workspace()
        sync(root, listOf("/does/not/exist/Ghost.jar"))
        assertFalse(File(root, "plugins/Ghost.jar").exists())
        assertTrue(File(root, ".minehost-plugins").isFile)
    }
}