package com.minehost.app.data

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the profile JSON codec round-trip and the active-profile fallbacks
 * of [ProfilesState] without touching DataStore.
 */
class ProfilesRepositoryTest {

    private fun profile(id: String, name: String = id, port: Int = 25565) = ServerProfile(
        id = id,
        name = name,
        createdAt = 1_000L,
        config = ServerConfig(profileId = id, serverName = name, port = port)
    )

    @Test
    fun `config json round-trips every field`() {
        val config = ServerConfig(
            profileId = "p1",
            serverName = "Creative",
            motd = "welcome",
            port = 25566,
            maxPlayers = 5,
            memoryMb = 1024,
            performanceMode = PerformanceMode.PERFORMANCE,
            enableRcon = true,
            rconPassword = "secret",
            authMode = ServerAuthMode.OFFLINE_LAN,
            corePath = "/data/core.jar",
            coreName = "Paper"
        )
        val decoded = ServerConfigJson.fromJson(ServerConfigJson.toJson(config))
        assertEquals(config, decoded)
    }

    @Test
    fun `config json fills defaults for missing fields`() {
        val json = org.json.JSONObject().put("serverName", "Only name")
        val decoded = ServerConfigJson.fromJson(json)
        assertEquals("Only name", decoded.serverName)
        assertEquals(25565, decoded.port)
        assertEquals(ServerConfig(profileId = null, serverName = "Only name"), decoded)
    }

    @Test
    fun `config json round-trips imported plugin paths`() {
        val config = ServerConfig(
            pluginPaths = listOf("/data/plugins/EssentialsX.jar", "/data/plugins/Vault.jar")
        )
        val decoded = ServerConfigJson.fromJson(ServerConfigJson.toJson(config))
        assertEquals(config.pluginPaths, decoded.pluginPaths)
    }

    @Test
    fun `old config json without plugin paths decodes to none`() {
        assertEquals(emptyList<String>(), ServerConfigJson.fromJson(org.json.JSONObject()).pluginPaths)
    }

    @Test
    fun `state falls back to the first profile when active is unknown`() {
        val state = ProfilesState(
            profiles = listOf(profile("a"), profile("b")),
            activeId = "missing"
        )
        assertEquals("a", state.active?.id)
    }

    @Test
    fun `state with no active still exposes the first profile`() {
        val state = ProfilesState(profiles = listOf(profile("a"), profile("b")), activeId = null)
        assertEquals("a", state.active?.id)
    }

    @Test
    fun `empty state has no active profile`() {
        assertNull(ProfilesState().active)
    }

    @Test
    fun `duplicate profile config keeps identity but drops the core`() {
        // Mirrors ProfilesRepository.duplicate: new id, no corePath, new port.
        val source = profile("a", "Survival", port = 25565)
            .copy(config = ServerConfig(profileId = "a", serverName = "Survival", port = 25565, corePath = "/x.jar"))
        val copyId = "b"
        val copy = source.config.copy(profileId = copyId, serverName = "${source.name} copy", port = 25566, corePath = null, coreName = null)
        assertEquals(copyId, copy.profileId)
        assertNotEquals(source.config.port, copy.port)
        assertNull(copy.corePath)
        assertTrue(copy.serverName.endsWith("copy"))
    }

    @Test
    fun `encoded profile list parses back through the codec`() {
        val profiles = listOf(profile("a", "One", 25565), profile("b", "Two", 25566))
        val array = JSONArray()
        profiles.forEach { p ->
            array.put(org.json.JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("createdAt", p.createdAt)
                put("config", ServerConfigJson.toJson(p.config))
            })
        }
        // Decode via the same path the repository uses.
        val decoded = (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            ServerProfile(
                id = item.getString("id"),
                name = item.getString("name"),
                createdAt = item.getLong("createdAt"),
                config = ServerConfigJson.fromJson(item.getJSONObject("config"))
            )
        }
        assertEquals(profiles, decoded)
    }
}
