package com.minehost.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rules that decide whether a picked file may be installed as a
 * server plugin, plus the descriptor read used for the plugin list label.
 */
class PluginJarRulesTest {

    private val zipHeader = byteArrayOf('P'.code.toByte(), 'K'.code.toByte())

    @Test
    fun `accepts a jar carrying a descriptor`() {
        assertNull(
            PluginJarRules.rejectionReason(
                fileName = "EssentialsX.jar",
                sizeBytes = 2_400_000L,
                isZip = true,
                entryNames = listOf("plugin.yml", "com/earth2me/essentials/Essentials.class")
            )
        )
    }

    @Test
    fun `accepts the paper plugin descriptor format`() {
        assertNull(
            PluginJarRules.rejectionReason(
                fileName = "Modern.jar",
                sizeBytes = 10L,
                isZip = true,
                entryNames = listOf("META-INF/MANIFEST.MF", "paper-plugin.yml")
            )
        )
    }

    @Test
    fun `rejects a jar without a descriptor`() {
        // The exact mistake users make: picking a server core or a mod JAR.
        val reason = PluginJarRules.rejectionReason(
            fileName = "paper.jar",
            sizeBytes = 50_000_000L,
            isZip = true,
            entryNames = listOf("META-INF/MANIFEST.MF", "org/bukkit/Bukkit.class")
        )
        assertEquals("That JAR has no plugin.yml, so Paper would not load it.", reason)
    }

    @Test
    fun `rejects non jars and non archives`() {
        assertEquals(
            "Plugins must be .jar files.",
            PluginJarRules.rejectionReason("EssentialsX.zip", 10L, isZip = true, entryNames = listOf("plugin.yml"))
        )
        assertEquals(
            "That file is not a valid JAR archive.",
            PluginJarRules.rejectionReason("EssentialsX.jar", 10L, isZip = false, entryNames = emptyList())
        )
    }

    @Test
    fun `rejects empty oversized and pathological jars`() {
        assertEquals(
            "That file is empty.",
            PluginJarRules.rejectionReason("a.jar", 0L, isZip = true, entryNames = listOf("plugin.yml"))
        )
        assertEquals(
            "That JAR is larger than 128 MB.",
            PluginJarRules.rejectionReason("a.jar", PluginJarRules.MAX_PLUGIN_BYTES + 1, true, listOf("plugin.yml"))
        )
        val many = List(PluginJarRules.MAX_ENTRIES + 1) { "class$it" } + "plugin.yml"
        assertEquals(
            "That JAR contains too many files.",
            PluginJarRules.rejectionReason("a.jar", 10L, isZip = true, entryNames = many)
        )
    }

    @Test
    fun `detects the zip signature`() {
        assertTrue(PluginJarRules.isZipArchive(zipHeader))
        assertFalse(PluginJarRules.isZipArchive(byteArrayOf(0x7F, 'E'.code.toByte())))
        assertFalse(PluginJarRules.isZipArchive(byteArrayOf()))
    }

    @Test
    fun `safe file names strip paths and always end in jar`() {
        assertEquals("EssentialsX.jar", PluginJarRules.safeFileName("EssentialsX.jar"))
        // Traversal in a picked display name must not survive.
        assertEquals("evil.jar", PluginJarRules.safeFileName("../../evil.jar"))
        assertEquals("plugin.jar", PluginJarRules.safeFileName("..\\..\\plugin.jar"))
        assertEquals("My_Plugin_v2.jar", PluginJarRules.safeFileName("My Plugin (v2).jar"))
        // A file with no extension still becomes a loadable name.
        assertEquals("Vault.jar", PluginJarRules.safeFileName("Vault"))
        assertEquals("plugin.jar", PluginJarRules.safeFileName("   "))
    }

    @Test
    fun `reads root keys from a plugin descriptor`() {
        val descriptor = PluginJarRules.readDescriptor(
            """
            # comment
            name: EssentialsX
            version: 2.20.1
            main: com.earth2me.essentials.Essentials
            api-version: 1.13
            author:
              - Zombie
            """.trimIndent()
        )
        assertEquals("EssentialsX", descriptor.name)
        assertEquals("2.20.1", descriptor.version)
        assertEquals("com.earth2me.essentials.Essentials", descriptor.main)
        assertEquals("1.13", descriptor.apiVersion)
        assertEquals("EssentialsX 2.20.1", descriptor.label())
    }

    @Test
    fun `descriptor tolerates quoted values and missing fields`() {
        val descriptor = PluginJarRules.readDescriptor("name: \"My Plugin\"\nmain: com.example.Main\n")
        assertEquals("My Plugin", descriptor.name)
        assertNull(descriptor.version)
        assertEquals("My Plugin", descriptor.label())
    }
}