package com.minehost.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

/**
 * Guards the two rules that decide whether a catalog download is trustworthy:
 * which host may be contacted, and which release actually matches a version.
 */
class CatalogDownloadRulesTest {

    @Test
    fun `accepts the official release hosts`() {
        val trusted = listOf(
            "https://fill-data.papermc.io/v1/objects/abc/paper-1.21.4-232.jar",
            "https://api.purpurmc.org/v2/purpur/1.21.4/latest/download",
            "https://meta.fabricmc.net/v2/versions/loader/1.21.4/0.16.9/1.0.1/server/jar",
            "https://piston-data.mojang.com/v1/objects/abc/server.jar",
            "https://maven.minecraftforge.net/net/minecraftforge/forge/1.21.4-51.0.33/forge-1.21.4-51.0.33-installer.jar",
            "https://release-assets.githubusercontent.com/github-production-release-asset/leaf.jar"
        )
        trusted.forEach { url ->
            assertNull("$url should be allowed", requireAllowedDownloadUrl(URL(url)))
        }
    }

    @Test
    fun `rejects hosts outside the allowlist`() {
        val untrusted = listOf(
            "https://evil.example.com/paper.jar",
            "https://papermc.io.evil.example.com/paper.jar",
            "https://evil-papermc.io/paper.jar",
            "https://raw.githubusercontent.com/someone/paper.jar"
        )
        untrusted.forEach { url ->
            val reason = requireAllowedDownloadUrl(URL(url))
            assertTrue("$url should be refused", reason != null)
        }
    }

    @Test
    fun `rejects plain http even on an allowed host`() {
        val reason = requireAllowedDownloadUrl(URL("http://fill-data.papermc.io/paper.jar"))
        assertEquals("Only HTTPS catalog downloads are allowed", reason)
    }

    @Test
    fun `matches an exact version inside a release tag`() {
        assertTrue(CoreCatalogRepository.versionMentioned("Leaf 1.21.4", "1.21.4"))
        assertTrue(CoreCatalogRepository.versionMentioned("ver-1.21.4", "1.21.4"))
        assertTrue(CoreCatalogRepository.versionMentioned("1.21.4-beta", "1.21.4"))
        assertTrue(CoreCatalogRepository.versionMentioned("Release 1.21.11 notes", "1.21.11"))
    }

    @Test
    fun `does not match a longer neighbouring version`() {
        // The bug this guards against: 1.21.1 must never select the 1.21.11 build.
        assertFalse(CoreCatalogRepository.versionMentioned("Leaf 1.21.11", "1.21.1"))
        assertFalse(CoreCatalogRepository.versionMentioned("ver-1.21.10", "1.21.1"))
        assertFalse(CoreCatalogRepository.versionMentioned("1.21.4.1", "1.21.4"))
        assertFalse(CoreCatalogRepository.versionMentioned("21.x", "21.1"))
    }
}
