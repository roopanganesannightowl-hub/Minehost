package com.minehost.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID

private val Context.profilesDataStore by preferencesDataStore(name = "minehost_profiles")

/** One manageable server: its own config, workspace, world and identity. */
data class ServerProfile(
    val id: String,
    val name: String,
    val createdAt: Long,
    val config: ServerConfig
)

data class ProfilesState(
    val profiles: List<ServerProfile> = emptyList(),
    val activeId: String? = null
) {
    val active: ServerProfile?
        get() = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
}

/**
 * Stores every server profile plus the active selection in a dedicated
 * DataStore file. The first launch migrates the existing single-server
 * DataStore config into a profile so nothing the user already set up (or any
 * world in the old workspace) is lost.
 */
class ProfilesRepository(private val context: Context) {

    private object Keys {
        val profilesJson = stringPreferencesKey("profiles_json")
        val activeId = stringPreferencesKey("active_id")
        val migrated = stringPreferencesKey("migrated")
    }

    val state: Flow<ProfilesState> = context.profilesDataStore.data
        .catch { error -> if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error }
        .map { preferences -> parse(preferences[Keys.profilesJson], preferences[Keys.activeId]) }

    suspend fun current(): ProfilesState = state.first()

    suspend fun activeConfig(): ServerConfig = current().active?.config ?: ServerConfig()

    suspend fun create(name: String): String {
        val id = UUID.randomUUID().toString()
        val profile = ServerProfile(
            id = id,
            name = name,
            createdAt = System.currentTimeMillis(),
            config = ServerConfig(profileId = id, serverName = name)
        )
        context.profilesDataStore.edit { preferences ->
            val state = parse(preferences[Keys.profilesJson], preferences[Keys.activeId])
            val updated = state.profiles + profile
            preferences[Keys.profilesJson] = encodeProfiles(updated)
            // A brand-new profile becomes active right away so setup flows continue.
            preferences[Keys.activeId] = id
        }
        return id
    }

    suspend fun saveConfig(profileId: String, config: ServerConfig) {
        context.profilesDataStore.edit { preferences ->
            val state = parse(preferences[Keys.profilesJson], preferences[Keys.activeId])
            val updated = state.profiles.map { profile ->
                if (profile.id == profileId) profile.copy(config = config.copy(profileId = profileId)) else profile
            }
            preferences[Keys.profilesJson] = encodeProfiles(updated)
        }
    }

    suspend fun rename(profileId: String, name: String) {
        val trimmed = name.trim().ifBlank { "Server" }
        context.profilesDataStore.edit { preferences ->
            val state = parse(preferences[Keys.profilesJson], preferences[Keys.activeId])
            val updated = state.profiles.map { profile ->
                if (profile.id == profileId) {
                    profile.copy(name = trimmed, config = profile.config.copy(serverName = trimmed))
                } else profile
            }
            preferences[Keys.profilesJson] = encodeProfiles(updated)
        }
    }

    suspend fun duplicate(profileId: String): String? {
        var newId: String? = null
        context.profilesDataStore.edit { preferences ->
            val state = parse(preferences[Keys.profilesJson], preferences[Keys.activeId])
            val source = state.profiles.firstOrNull { it.id == profileId } ?: return@edit
            val id = UUID.randomUUID().toString()
            newId = id
            val copy = ServerProfile(
                id = id,
                name = "${source.name} copy",
                createdAt = System.currentTimeMillis(),
                config = source.config.copy(
                    profileId = id,
                    serverName = "${source.config.serverName} copy",
                    // Ports must differ or the copy could never run alongside state.
                    port = nextFreePort(state.profiles),
                    // The copy does not own the original's core selection.
                    corePath = null,
                    coreName = null
                )
            )
            val updated = state.profiles + copy
            preferences[Keys.profilesJson] = encodeProfiles(updated)
        }
        return newId
    }

    suspend fun delete(profileId: String) {
        context.profilesDataStore.edit { preferences ->
            val state = parse(preferences[Keys.profilesJson], preferences[Keys.activeId])
            if (state.profiles.size <= 1) return@edit // never delete the last profile
            val updated = state.profiles.filterNot { it.id == profileId }
            preferences[Keys.profilesJson] = encodeProfiles(updated)
            if (preferences[Keys.activeId] == profileId) {
                preferences[Keys.activeId] = updated.firstOrNull()?.id ?: ""
            }
        }
    }

    suspend fun setActive(profileId: String) {
        context.profilesDataStore.edit { preferences ->
            val state = parse(preferences[Keys.profilesJson], preferences[Keys.activeId])
            if (state.profiles.any { it.id == profileId }) {
                preferences[Keys.activeId] = profileId
            }
        }
    }

    /**
     * One-time migration: wrap the single-server config into the first
     * profile and reuse its server name. Runs once, then never again.
     */
    suspend fun migrateLegacyIfNeeded(legacy: ServerConfig) {
        context.profilesDataStore.edit { preferences ->
            if (preferences[Keys.migrated] == "true") return@edit
            val existing = parse(preferences[Keys.profilesJson], preferences[Keys.activeId])
            if (existing.profiles.isEmpty()) {
                val id = UUID.randomUUID().toString()
                val profile = ServerProfile(
                    id = id,
                    name = legacy.serverName,
                    createdAt = System.currentTimeMillis(),
                    config = legacy.copy(profileId = id)
                )
                preferences[Keys.profilesJson] = encodeProfiles(listOf(profile))
                preferences[Keys.activeId] = id
            }
            preferences[Keys.migrated] = "true"
        }
    }

    // ------------------------------------------------------------------

    private fun parse(profilesJson: String?, activeId: String?): ProfilesState {
        val profiles = profilesJson
            ?.let { runCatching { decodeProfiles(it) }.getOrNull() }
            .orEmpty()
        return ProfilesState(profiles, activeId?.takeIf { id -> profiles.any { it.id == id } })
    }

    private fun encodeProfiles(profiles: List<ServerProfile>): String {
        val array = org.json.JSONArray()
        profiles.forEach { profile ->
            array.put(org.json.JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("createdAt", profile.createdAt)
                put("config", ServerConfigJson.toJson(profile.config))
            })
        }
        return array.toString()
    }

    private fun decodeProfiles(json: String): List<ServerProfile> {
        val array = org.json.JSONArray(json)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val configJson = item.optJSONObject("config")
            ServerProfile(
                id = item.getString("id"),
                name = item.optString("name", "Server"),
                createdAt = item.optLong("createdAt"),
                config = configJson?.let(ServerConfigJson::fromJson) ?: ServerConfig()
            )
        }
    }

    private fun nextFreePort(profiles: List<ServerProfile>): Int {
        val used = profiles.map { it.config.port }.toSet()
        var candidate = 25566
        while (candidate in used) candidate += 1
        return candidate.coerceAtMost(65535)
    }
}
