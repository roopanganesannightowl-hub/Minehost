package com.minehost.app.data

import org.json.JSONObject

/**
 * JSON codec for [ServerConfig]. Profiles store their whole config as one
 * JSON blob in DataStore, so adding a field only touches this file and the
 * default value in [ServerConfig] — old blobs pick the default up for free.
 */
object ServerConfigJson {

    fun toJson(config: ServerConfig): JSONObject = JSONObject().apply {
        put("profileId", config.profileId ?: JSONObject.NULL)
        put("serverName", config.serverName)
        put("motd", config.motd)
        put("port", config.port)
        put("maxPlayers", config.maxPlayers)
        put("memoryMb", config.memoryMb)
        put("performanceMode", config.performanceMode.name)
        put("levelName", config.levelName)
        put("launchArgs", config.launchArgs)
        put("javaExecutable", config.javaExecutable)
        put("corePath", config.corePath ?: JSONObject.NULL)
        put("coreName", config.coreName ?: JSONObject.NULL)
        put("keepAwake", config.keepAwake)
        put("startOnBoot", config.startOnBoot)
        put("acceptEula", config.acceptEula)
        put("allowFlight", config.allowFlight)
        put("enableQuery", config.enableQuery)
        put("enableRcon", config.enableRcon)
        put("rconPassword", config.rconPassword)
        put("authMode", config.authMode.name)
        put("viewDistance", config.viewDistance)
        put("simulationDistance", config.simulationDistance)
        put("networkCompressionThreshold", config.networkCompressionThreshold)
        put("difficulty", config.difficulty)
        put("gamemode", config.gamemode)
        put("levelType", config.levelType)
        put("levelSeed", config.levelSeed)
        put("spawnProtection", config.spawnProtection)
        put("enableCommandBlock", config.enableCommandBlock)
        put("playerIdleTimeout", config.playerIdleTimeout)
        put("pauseWhenEmptySeconds", config.pauseWhenEmptySeconds)
        put("resourcePackUrl", config.resourcePackUrl)
        put("resourcePackSha1", config.resourcePackSha1)
        put("requireResourcePack", config.requireResourcePack)
        put("resourcePackPrompt", config.resourcePackPrompt)
        put("resourcePackPath", config.resourcePackPath)
        put("modpackPath", config.modpackPath)
        put("enableStatus", config.enableStatus)
        put("hideOnlinePlayers", config.hideOnlinePlayers)
        put("syncChunkWrites", config.syncChunkWrites)
        put("useNativeTransport", config.useNativeTransport)
        put("allowNether", config.allowNether)
        put("generateStructures", config.generateStructures)
    }

    fun fromJson(json: JSONObject): ServerConfig = ServerConfig(
        profileId = json.optStringOrNull("profileId"),
        serverName = json.optString("serverName", "Survival world"),
        motd = json.optString("motd", "A cozy place to build together"),
        port = json.optInt("port", 25565),
        maxPlayers = json.optInt("maxPlayers", 20),
        memoryMb = json.optInt("memoryMb", 2048),
        performanceMode = enumOr(json.optString("performanceMode"), PerformanceMode.BALANCED),
        levelName = json.optString("levelName", "world"),
        launchArgs = json.optString("launchArgs", "nogui"),
        javaExecutable = json.optString("javaExecutable", "java"),
        corePath = json.optStringOrNull("corePath"),
        coreName = json.optStringOrNull("coreName"),
        keepAwake = json.optBoolean("keepAwake", true),
        startOnBoot = json.optBoolean("startOnBoot", false),
        acceptEula = json.optBoolean("acceptEula", false),
        allowFlight = json.optBoolean("allowFlight", false),
        enableQuery = json.optBoolean("enableQuery", false),
        enableRcon = json.optBoolean("enableRcon", false),
        rconPassword = json.optString("rconPassword", ""),
        authMode = enumOr(json.optString("authMode"), ServerAuthMode.ONLINE),
        viewDistance = json.optInt("viewDistance", 10),
        simulationDistance = json.optInt("simulationDistance", 10),
        networkCompressionThreshold = json.optInt("networkCompressionThreshold", 256),
        difficulty = json.optString("difficulty", "normal"),
        gamemode = json.optString("gamemode", "survival"),
        levelType = json.optString("levelType", "minecraft:normal"),
        levelSeed = json.optString("levelSeed", ""),
        spawnProtection = json.optInt("spawnProtection", 16),
        enableCommandBlock = json.optBoolean("enableCommandBlock", false),
        playerIdleTimeout = json.optInt("playerIdleTimeout", 0),
        pauseWhenEmptySeconds = json.optInt("pauseWhenEmptySeconds", 0),
        resourcePackUrl = json.optString("resourcePackUrl", ""),
        resourcePackSha1 = json.optString("resourcePackSha1", ""),
        requireResourcePack = json.optBoolean("requireResourcePack", false),
        resourcePackPrompt = json.optString("resourcePackPrompt", ""),
        resourcePackPath = json.optString("resourcePackPath").takeIf { it.isNotBlank() },
        modpackPath = json.optString("modpackPath").takeIf { it.isNotBlank() },
        enableStatus = json.optBoolean("enableStatus", true),
        hideOnlinePlayers = json.optBoolean("hideOnlinePlayers", false),
        syncChunkWrites = json.optBoolean("syncChunkWrites", true),
        useNativeTransport = json.optBoolean("useNativeTransport", true),
        allowNether = json.optBoolean("allowNether", true),
        generateStructures = json.optBoolean("generateStructures", true)
    )

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key)

    private inline fun <reified T : Enum<T>> enumOr(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(name) }.getOrDefault(fallback)
}
