package com.minehost.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.mineHostDataStore by preferencesDataStore(name = "minehost_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val serverName = stringPreferencesKey("server_name")
        val motd = stringPreferencesKey("motd")
        val port = intPreferencesKey("port")
        val maxPlayers = intPreferencesKey("max_players")
        val memoryMb = intPreferencesKey("memory_mb")
        val performanceMode = stringPreferencesKey("performance_mode")
        val levelName = stringPreferencesKey("level_name")
        val launchArgs = stringPreferencesKey("launch_args")
        val javaExecutable = stringPreferencesKey("java_executable")
        val corePath = stringPreferencesKey("core_path")
        val coreName = stringPreferencesKey("core_name")
        val keepAwake = booleanPreferencesKey("keep_awake")
        val startOnBoot = booleanPreferencesKey("start_on_boot")
        val acceptEula = booleanPreferencesKey("accept_eula")
        val allowFlight = booleanPreferencesKey("allow_flight")
        val enableQuery = booleanPreferencesKey("enable_query")
        val enableRcon = booleanPreferencesKey("enable_rcon")
        val rconPassword = stringPreferencesKey("rcon_password")
        val authMode = stringPreferencesKey("auth_mode")
        val viewDistance = intPreferencesKey("view_distance")
        val simulationDistance = intPreferencesKey("simulation_distance")
        val networkCompressionThreshold = intPreferencesKey("network_compression_threshold")
        val difficulty = stringPreferencesKey("difficulty")
        val gamemode = stringPreferencesKey("gamemode")
        val levelType = stringPreferencesKey("level_type")
        val levelSeed = stringPreferencesKey("level_seed")
        val spawnProtection = intPreferencesKey("spawn_protection")
        val enableCommandBlock = booleanPreferencesKey("enable_command_block")
        val playerIdleTimeout = intPreferencesKey("player_idle_timeout")
        val pauseWhenEmptySeconds = intPreferencesKey("pause_when_empty_seconds")
        val resourcePackUrl = stringPreferencesKey("resource_pack_url")
        val resourcePackSha1 = stringPreferencesKey("resource_pack_sha1")
        val requireResourcePack = booleanPreferencesKey("require_resource_pack")
        val resourcePackPrompt = stringPreferencesKey("resource_pack_prompt")
        val resourcePackPath = stringPreferencesKey("resource_pack_path")
        val modpackPath = stringPreferencesKey("modpack_path")
        val enableStatus = booleanPreferencesKey("enable_status")
        val hideOnlinePlayers = booleanPreferencesKey("hide_online_players")
        val syncChunkWrites = booleanPreferencesKey("sync_chunk_writes")
        val useNativeTransport = booleanPreferencesKey("use_native_transport")
        val allowNether = booleanPreferencesKey("allow_nether")
        val generateStructures = booleanPreferencesKey("generate_structures")
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val playitSecretKey = stringPreferencesKey("playit_secret_key")
        val autoOpenTunnel = booleanPreferencesKey("auto_open_playit_tunnel")
    }

    /** Flipped after the first-launch walkthrough; the wizard never returns. */
    val onboardingComplete: Flow<Boolean> = context.mineHostDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences -> preferences[Keys.onboardingComplete] ?: false }

    suspend fun setOnboardingComplete(complete: Boolean = true) {
        context.mineHostDataStore.edit { preferences ->
            preferences[Keys.onboardingComplete] = complete
        }
    }

    /** Persistent playit.gg agent secret; blank until the claim flow finishes. */
    val playitSecretKey: Flow<String> = context.mineHostDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences -> preferences[Keys.playitSecretKey] ?: "" }

    suspend fun setPlayitSecretKey(secret: String) {
        context.mineHostDataStore.edit { preferences ->
            if (secret.isBlank()) preferences.remove(Keys.playitSecretKey) else preferences[Keys.playitSecretKey] = secret
        }
    }

    /**
     * When on, opening a playit.gg tunnel is automatic: the moment the server
     * reaches RUNNING the tunnel connects too. On by default — friends can
     * join through the persistent address without a manual trip to Settings.
     */
    val autoOpenTunnel: Flow<Boolean> = context.mineHostDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences -> preferences[Keys.autoOpenTunnel] ?: true }

    suspend fun setAutoOpenTunnel(enabled: Boolean) {
        context.mineHostDataStore.edit { preferences ->
            preferences[Keys.autoOpenTunnel] = enabled
        }
    }

    val config: Flow<ServerConfig> = context.mineHostDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences())
            else throw error
        }
        .map { preferences ->
            ServerConfig(
                serverName = preferences[Keys.serverName] ?: "Survival world",
                motd = preferences[Keys.motd] ?: "A cozy place to build together",
                port = preferences[Keys.port] ?: 25565,
                maxPlayers = preferences[Keys.maxPlayers] ?: 20,
                memoryMb = preferences[Keys.memoryMb] ?: 2048,
                performanceMode = runCatching {
                    PerformanceMode.valueOf(preferences[Keys.performanceMode] ?: PerformanceMode.BALANCED.name)
                }.getOrDefault(PerformanceMode.BALANCED),
                levelName = preferences[Keys.levelName] ?: "world",
                launchArgs = preferences[Keys.launchArgs] ?: "nogui",
                javaExecutable = preferences[Keys.javaExecutable] ?: "java",
                corePath = preferences[Keys.corePath],
                coreName = preferences[Keys.coreName],
                keepAwake = preferences[Keys.keepAwake] ?: true,
                startOnBoot = preferences[Keys.startOnBoot] ?: false,
                acceptEula = preferences[Keys.acceptEula] ?: false,
                allowFlight = preferences[Keys.allowFlight] ?: false,
                enableQuery = preferences[Keys.enableQuery] ?: false,
                enableRcon = preferences[Keys.enableRcon] ?: false,
                rconPassword = preferences[Keys.rconPassword] ?: "",
                authMode = runCatching {
                    ServerAuthMode.valueOf(preferences[Keys.authMode] ?: ServerAuthMode.ONLINE.name)
                }.getOrDefault(ServerAuthMode.ONLINE),
                viewDistance = preferences[Keys.viewDistance] ?: 10,
                simulationDistance = preferences[Keys.simulationDistance] ?: 10,
                networkCompressionThreshold = preferences[Keys.networkCompressionThreshold] ?: 256,
                difficulty = preferences[Keys.difficulty] ?: "normal",
                gamemode = preferences[Keys.gamemode] ?: "survival",
                levelType = preferences[Keys.levelType] ?: "minecraft:normal",
                levelSeed = preferences[Keys.levelSeed] ?: "",
                spawnProtection = preferences[Keys.spawnProtection] ?: 16,
                enableCommandBlock = preferences[Keys.enableCommandBlock] ?: false,
                playerIdleTimeout = preferences[Keys.playerIdleTimeout] ?: 0,
                pauseWhenEmptySeconds = preferences[Keys.pauseWhenEmptySeconds] ?: 0,
                resourcePackUrl = preferences[Keys.resourcePackUrl] ?: "",
                resourcePackSha1 = preferences[Keys.resourcePackSha1] ?: "",
                requireResourcePack = preferences[Keys.requireResourcePack] ?: false,
                resourcePackPrompt = preferences[Keys.resourcePackPrompt] ?: "",
                resourcePackPath = preferences[Keys.resourcePackPath]?.takeIf { it.isNotBlank() },
                modpackPath = preferences[Keys.modpackPath]?.takeIf { it.isNotBlank() },
                enableStatus = preferences[Keys.enableStatus] ?: true,
                hideOnlinePlayers = preferences[Keys.hideOnlinePlayers] ?: false,
                syncChunkWrites = preferences[Keys.syncChunkWrites] ?: true,
                useNativeTransport = preferences[Keys.useNativeTransport] ?: true,
                allowNether = preferences[Keys.allowNether] ?: true,
                generateStructures = preferences[Keys.generateStructures] ?: true
            )
        }

    suspend fun current(): ServerConfig = config.first()

    suspend fun save(config: ServerConfig) {
        context.mineHostDataStore.edit { preferences ->
            preferences[Keys.serverName] = config.serverName.trim().ifBlank { "Survival world" }
            preferences[Keys.motd] = config.motd.trim()
            preferences[Keys.port] = config.port.coerceIn(1, 65535)
            preferences[Keys.maxPlayers] = config.maxPlayers.coerceIn(1, 500)
            preferences[Keys.memoryMb] = config.memoryMb.coerceIn(512, 16384)
            preferences[Keys.performanceMode] = config.performanceMode.name
            preferences[Keys.levelName] = config.levelName.trim().ifBlank { "world" }
            preferences[Keys.launchArgs] = config.launchArgs.trim().ifBlank { "nogui" }
            preferences[Keys.javaExecutable] = config.javaExecutable.trim().ifBlank { "java" }
            config.corePath?.let { preferences[Keys.corePath] = it }
                ?: preferences.remove(Keys.corePath)
            config.coreName?.let { preferences[Keys.coreName] = it }
                ?: preferences.remove(Keys.coreName)
            preferences[Keys.keepAwake] = config.keepAwake
            preferences[Keys.startOnBoot] = config.startOnBoot
            preferences[Keys.acceptEula] = config.acceptEula
            preferences[Keys.allowFlight] = config.allowFlight
            preferences[Keys.enableQuery] = config.enableQuery
            preferences[Keys.enableRcon] = config.enableRcon
            preferences[Keys.rconPassword] = config.rconPassword
            preferences[Keys.authMode] = config.authMode.name
            preferences[Keys.viewDistance] = config.viewDistance.coerceIn(2, 32)
            preferences[Keys.simulationDistance] = config.simulationDistance.coerceIn(2, 32)
            preferences[Keys.networkCompressionThreshold] = config.networkCompressionThreshold.coerceIn(-1, 65536)
            preferences[Keys.difficulty] = config.difficulty
            preferences[Keys.gamemode] = config.gamemode
            preferences[Keys.levelType] = config.levelType
            preferences[Keys.levelSeed] = config.levelSeed
            preferences[Keys.spawnProtection] = config.spawnProtection.coerceIn(0, 1000000)
            preferences[Keys.enableCommandBlock] = config.enableCommandBlock
            preferences[Keys.playerIdleTimeout] = config.playerIdleTimeout.coerceIn(0, 1440)
            preferences[Keys.pauseWhenEmptySeconds] = config.pauseWhenEmptySeconds.coerceIn(0, 86400)
            preferences[Keys.resourcePackUrl] = config.resourcePackUrl.trim()
            preferences[Keys.resourcePackSha1] = config.resourcePackSha1.trim().lowercase()
            preferences[Keys.requireResourcePack] = config.requireResourcePack
            preferences[Keys.resourcePackPrompt] = config.resourcePackPrompt.trim()
            config.resourcePackPath?.let { preferences[Keys.resourcePackPath] = it }
                ?: preferences.remove(Keys.resourcePackPath)
            config.modpackPath?.let { preferences[Keys.modpackPath] = it }
                ?: preferences.remove(Keys.modpackPath)
            preferences[Keys.enableStatus] = config.enableStatus
            preferences[Keys.hideOnlinePlayers] = config.hideOnlinePlayers
            preferences[Keys.syncChunkWrites] = config.syncChunkWrites
            preferences[Keys.useNativeTransport] = config.useNativeTransport
            preferences[Keys.allowNether] = config.allowNether
            preferences[Keys.generateStructures] = config.generateStructures
        }
    }
}
