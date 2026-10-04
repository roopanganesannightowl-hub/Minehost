package com.minehost.app.data

enum class PerformanceMode {
    BATSAVER,
    BALANCED,
    PERFORMANCE
}

enum class ServerAuthMode {
    ONLINE,
    OFFLINE_LAN
}

enum class ServerPhase {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

enum class ConsoleLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}

data class ConsoleLine(
    val timestamp: Long = System.currentTimeMillis(),
    val text: String,
    val level: ConsoleLevel = ConsoleLevel.INFO,
    /** Monotonic identity for stable LazyColumn keys across log rotation. */
    val seq: Long = 0L
)

data class ServerConfig(
    /** Owning profile; null only for the pre-profiles legacy config. */
    val profileId: String? = null,
    val serverName: String = "Survival world",
    val motd: String = "A cozy place to build together",
    val port: Int = 25565,
    val maxPlayers: Int = 20,
    val memoryMb: Int = 2048,
    val performanceMode: PerformanceMode = PerformanceMode.BALANCED,
    val levelName: String = "world",
    val launchArgs: String = "nogui",
    val javaExecutable: String = "java",
    val corePath: String? = null,
    val coreName: String? = null,
    val keepAwake: Boolean = true,
    val startOnBoot: Boolean = false,
    val acceptEula: Boolean = false,
    val allowFlight: Boolean = false,
    val enableQuery: Boolean = false,
    val enableRcon: Boolean = false,
    val rconPassword: String = "",
    val authMode: ServerAuthMode = ServerAuthMode.ONLINE,
    val viewDistance: Int = 10,
    val simulationDistance: Int = 10,
    val networkCompressionThreshold: Int = 256,
    val difficulty: String = "normal",
    val gamemode: String = "survival",
    val levelType: String = "minecraft:normal",
    val levelSeed: String = "",
    val spawnProtection: Int = 16,
    val enableCommandBlock: Boolean = false,
    val playerIdleTimeout: Int = 0,
    val pauseWhenEmptySeconds: Int = 0,
    val resourcePackUrl: String = "",
    val resourcePackSha1: String = "",
    val requireResourcePack: Boolean = false,
    val resourcePackPrompt: String = "",
    /** Absolute path of an imported resource pack inside the workspace. */
    val resourcePackPath: String? = null,
    /** Absolute path of an imported mod/plugin pack copied into the workspace. */
    val modpackPath: String? = null,
    /**
     * Absolute paths of individually imported plugin JARs, in the app's own
     * `plugins/` storage. They are copied into the workspace `plugins/` folder
     * on the next server start.
     */
    val pluginPaths: List<String> = emptyList(),
    val enableStatus: Boolean = true,
    val hideOnlinePlayers: Boolean = false,
    val syncChunkWrites: Boolean = true,
    val useNativeTransport: Boolean = true,
    val allowNether: Boolean = true,
    val generateStructures: Boolean = true
) {
    val isConfigured: Boolean
        get() = !corePath.isNullOrBlank()

    val address: String
        get() = "localhost:$port"
}

data class ServerSnapshot(
    val phase: ServerPhase = ServerPhase.STOPPED,
    val startedAt: Long? = null,
    val pid: Long? = null,
    val onlinePlayers: Int = 0,
    val maxPlayers: Int = 20,
    val currentTps: Double? = null,
    val errorMessage: String? = null,
    val logs: List<ConsoleLine> = emptyList(),
    /** Bumped only when [logs] changes, so keyed composables/lists survive
     *  unrelated snapshot updates (phase, player counts, etc.). */
    val logsRevision: Long = 0L,
    /** Resident memory of the server process, sampled while it runs. */
    val memoryUsedMb: Int? = null,
    /** Recent CPU load of the server process (0-100 per core set). */
    val cpuPercent: Float? = null
) {
    val isActive: Boolean
        get() = phase == ServerPhase.STARTING || phase == ServerPhase.RUNNING || phase == ServerPhase.STOPPING
}
