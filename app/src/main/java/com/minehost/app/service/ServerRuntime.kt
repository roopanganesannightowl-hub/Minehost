package com.minehost.app.service

import android.content.Context
import com.minehost.app.data.ConsoleLevel
import com.minehost.app.data.PerformanceMode
import com.minehost.app.data.ServerAuthMode
import com.minehost.app.data.ConsoleLine
import com.minehost.app.data.ServerConfig
import com.minehost.app.data.ServerPhase
import com.minehost.app.data.ServerSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import java.util.concurrent.TimeUnit

private val PLAYER_PATTERNS = listOf(
    Regex("(\\d+)\\s+of\\s+a\\s+max\\s+of\\s+(\\d+)\\s+players?\\s+online", RegexOption.IGNORE_CASE),
    Regex("(\\d+)\\s*/\\s*(\\d+)\\s+players?\\s+online", RegexOption.IGNORE_CASE),
    Regex("(\\d+)\\s+players?\\s+online", RegexOption.IGNORE_CASE)
)
private val TPS_PATTERN = Regex("TPS(?:\\s+from\\s+last\\s+\\w+)?\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)
private val READY_PATTERN = Regex("Done \\([0-9.]+s\\)")
private val ANSI_PATTERN = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]")
private const val READY_TIMEOUT_MS = 5 * 60 * 1000L
private const val EMIT_INTERVAL_MS = 250L
private const val IDLE_EMIT_INTERVAL_MS = 1_000L
private const val LOG_CAPACITY = 240
private const val GRACEFUL_STOP_SECONDS = 15L
private const val METRIC_SAMPLE_TICKS = 4L
private const val CLOCK_TICKS_PER_SECOND = 100.0

/** Flags every JAR core gets: predictable encoding, no AWT and Log4Shell hardening. */
private val COMMON_JVM_FLAGS = listOf(
    "-Dfile.encoding=UTF-8",
    "-Djava.awt.headless=true",
    "-Dlog4j2.formatMsgNoLookups=true",
    "-XX:+PerfDisableSharedMem"
)

class ServerRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val _snapshot = MutableStateFlow(ServerSnapshot())
    // Coalescing stage: writers update this; an emitter publishes at most
    // every EMIT_INTERVAL_MS so console bursts don't flood recomposition.
    private var stagedSnapshot = ServerSnapshot()
    private var hasStaged = false
    // Console output lives in a ring buffer so appending a line is O(1) and the
    // immutable list is only built once per emit interval.
    private val logBuffer = ArrayDeque<ConsoleLine>(LOG_CAPACITY + 1)
    private var logRevision = 0L
    private var process: Process? = null
    private var outputJob: Job? = null
    private var shouldRemainRunning = false
    private var lastCpuTicks = 0L
    private var lastCpuSampleAt = 0L

    val snapshot = _snapshot.asStateFlow()

    init {
        scope.launch {
            var tick = 0L
            while (true) {
                // Idle processes need no 250 ms cadence: when the server is down
                // and nothing is staged, one wakeup per second is plenty and
                // keeps a live timer from burning battery in the background.
                val idle = synchronized(lock) { !hasStaged && !stagedSnapshot.isActive }
                delay(if (idle) IDLE_EMIT_INTERVAL_MS else EMIT_INTERVAL_MS)
                tick++
                if (tick % METRIC_SAMPLE_TICKS == 0L) sampleProcessMetrics()
                publish()
            }
        }
    }

    /**
     * Materializes the log ring buffer into an immutable list and emits the
     * staged snapshot. Writers never pay for this, so a burst of console output
     * costs O(1) per line and at most one emission per interval.
     */
    private fun publish() {
        synchronized(lock) {
            if (!hasStaged) return
            hasStaged = false
            stagedSnapshot = stagedSnapshot.copy(logs = logBuffer.toList(), logsRevision = logRevision)
            _snapshot.value = stagedSnapshot
        }
    }

    /**
     * Samples RSS and CPU straight from /proc for the running server process.
     * Cheap enough to run once a second and gives the dashboard real numbers.
     */
    private fun sampleProcessMetrics() {
        val runningProcess = synchronized(lock) {
            if (stagedSnapshot.phase == ServerPhase.RUNNING) process else null
        } ?: return
        val pid = readProcessId(runningProcess) ?: return
        val residentPages = readProcessResidentPages(pid) ?: return
        val pageSize = runCatching {
            android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
        }.getOrDefault(4096L)
        val memoryMb = ((residentPages * pageSize) / (1024L * 1024L)).toInt()
        val cpuTicks = readProcessCpuTicks(pid)
        val now = System.currentTimeMillis()
        var cpuPercent: Float? = null
        if (cpuTicks != null && lastCpuSampleAt > 0L) {
            val elapsedMs = (now - lastCpuSampleAt).coerceAtLeast(1L)
            val deltaTicks = (cpuTicks - lastCpuTicks).coerceAtLeast(0L)
            cpuPercent = (deltaTicks / CLOCK_TICKS_PER_SECOND * 100_000.0 / elapsedMs).toFloat()
        }
        if (cpuTicks != null) {
            lastCpuTicks = cpuTicks
            lastCpuSampleAt = now
        }
        synchronized(lock) {
            if (stagedSnapshot.phase != ServerPhase.RUNNING) return
            if (stagedSnapshot.memoryUsedMb == memoryMb && stagedSnapshot.cpuPercent == cpuPercent) return
            stagedSnapshot = stagedSnapshot.copy(memoryUsedMb = memoryMb, cpuPercent = cpuPercent)
            hasStaged = true
        }
    }

    private fun readProcessResidentPages(pid: Long): Long? = runCatching {
        File("/proc/$pid/statm").readText().trim().split(' ').getOrNull(1)?.toLongOrNull()
    }.getOrNull()

    private fun readProcessCpuTicks(pid: Long): Long? = runCatching {
        val stat = File("/proc/$pid/stat").readText()
        // Fields after the process name start at state (field 3); utime is 14.
        val fields = stat.substringAfterLast(')').trim().split(' ')
        val user = fields.getOrNull(11)?.toLongOrNull() ?: return@runCatching null
        val system = fields.getOrNull(12)?.toLongOrNull() ?: return@runCatching null
        user + system
    }.getOrNull()

    fun markStarting(config: ServerConfig): Boolean = synchronized(lock) {
        if (
            stagedSnapshot.phase == ServerPhase.RUNNING ||
            stagedSnapshot.phase == ServerPhase.STARTING ||
            stagedSnapshot.phase == ServerPhase.STOPPING
        ) {
            return@synchronized false
        }
        shouldRemainRunning = true
        lastCpuSampleAt = 0L
        stagedSnapshot = stagedSnapshot.copy(
            phase = ServerPhase.STARTING,
            maxPlayers = config.maxPlayers,
            onlinePlayers = 0,
            currentTps = null,
            memoryUsedMb = null,
            cpuPercent = null,
            errorMessage = null
        )
        hasStaged = true
        appendLogLocked("Preparing ${config.serverName}…")
        publish()
        true
    }

    suspend fun start(config: ServerConfig) {
        if (!markStarting(config)) return

        val corePath = config.corePath
        if (corePath.isNullOrBlank()) {
            fail("Choose a server core in Settings before starting.")
            return
        }
        if (!config.acceptEula) {
            fail("Accept the Minecraft EULA in Settings before starting.")
            return
        }
        if (config.enableRcon && config.rconPassword.isBlank()) {
            fail("Add an RCON password in Settings before starting.")
            return
        }
        // A heap the device cannot back is the number-one way a phone-hosted
        // server gets OOM-killed mid-game. Warn honestly instead of failing.
        deviceRamMb()?.let { totalDeviceMb ->
            if (config.memoryMb > totalDeviceMb / 2) {
                appendLog(
                    "Warning: a ${config.memoryMb} MB heap on a ${totalDeviceMb} MB device leaves little " +
                        "room for Android. If the server gets killed, lower the memory limit.",
                    ConsoleLevel.WARNING
                )
            }
        }

        val coreFile = File(corePath)
        if (!coreFile.exists()) {
            fail("The selected server core is no longer available. Import it again.")
            return
        }

        val workspace = try {
            withContext(Dispatchers.IO) {
                ServerWorkspace(appContext).prepare(config)
            }
        } catch (error: Exception) {
            fail("Could not prepare the server workspace: ${error.message.orEmpty()}")
            return
        }

        val command = buildCommand(config, coreFile)
        appendLog("Launch command: ${command.joinToString(" ")}")

        val startedProcess = try {
            withContext(Dispatchers.IO) {
                ProcessBuilder(*command.toTypedArray())
                    .directory(workspace)
                    .redirectErrorStream(true)
                    .start()
            }
        } catch (error: Exception) {
            fail("Could not launch the server: ${friendlyError(error)}")
            return
        }

        val processId = readProcessId(startedProcess)
        synchronized(lock) {
            if (!shouldRemainRunning) {
                startedProcess.destroy()
                stagedSnapshot = stagedSnapshot.copy(
                    phase = ServerPhase.STOPPED,
                    startedAt = null,
                    pid = null,
                    onlinePlayers = 0,
                    errorMessage = null
                )
                hasStaged = true
                publish()
                return
            }
            process = startedProcess
            stagedSnapshot = stagedSnapshot.copy(
                phase = ServerPhase.STARTING,
                startedAt = System.currentTimeMillis(),
                pid = processId,
                errorMessage = null
            )
            hasStaged = true
            publish()
        }
        appendLog(
            if (processId != null) "Server process started (pid $processId)." else "Server process started.",
            ConsoleLevel.SUCCESS
        )

        outputJob = scope.launch {
            consumeOutput(startedProcess)
        }
        // Safety net: some cores never print the vanilla "Done (…s)" banner.
        scope.launch {
            delay(READY_TIMEOUT_MS)
            markReady()
        }
    }

    suspend fun stop() {
        val runningProcess = synchronized(lock) {
            shouldRemainRunning = false
            if (stagedSnapshot.phase != ServerPhase.STOPPED && stagedSnapshot.phase != ServerPhase.ERROR) {
                stagedSnapshot = stagedSnapshot.copy(phase = ServerPhase.STOPPING, errorMessage = null)
                hasStaged = true
            }
            process
        }

        if (runningProcess == null) {
            resetToStopped("Server stopped.")
            return
        }

        appendLog("Stopping server…", ConsoleLevel.WARNING)
        withContext(Dispatchers.IO) {
            // Ask the server to shut down gracefully first so the world, player
            // data and level.dat are flushed the way the core intends.
            runCatching {
                runningProcess.outputStream.write("stop\n".toByteArray(Charsets.UTF_8))
                runningProcess.outputStream.flush()
            }
            if (!runningProcess.waitFor(GRACEFUL_STOP_SECONDS, TimeUnit.SECONDS)) {
                runningProcess.destroy()
                if (!runningProcess.waitFor(8, TimeUnit.SECONDS)) {
                    runningProcess.destroyForcibly()
                    runningProcess.waitFor(3, TimeUnit.SECONDS)
                }
            }
        }
        publish()
    }

    fun requestStop() {
        scope.launch { stop() }
    }

    fun clearLogs() {
        synchronized(lock) {
            logBuffer.clear()
            logRevision += 1
            hasStaged = true
            publish()
        }
    }

    fun sendCommand(rawCommand: String) {
        val command = rawCommand.replace('\n', ' ').replace('\r', ' ').trim().take(500)
        if (command.isBlank()) return
        val runningProcess = synchronized(lock) { process }
        if (runningProcess == null) {
            appendLog("Cannot send a command while the server is stopped.", ConsoleLevel.WARNING)
            return
        }
        // Writing to the process pipe can block; never do it on the caller's thread.
        scope.launch(Dispatchers.IO) {
            try {
                val output = runningProcess.outputStream
                output.write((command + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
                appendLog("> $command", ConsoleLevel.INFO)
            } catch (error: Exception) {
                appendLog("Could not send command: ${friendlyError(error)}", ConsoleLevel.ERROR)
            }
        }
    }

    fun resetToStopped(message: String? = null) {
        synchronized(lock) {
            shouldRemainRunning = false
            process = null
            outputJob = null
            lastCpuSampleAt = 0L
            if (!message.isNullOrBlank()) {
                logRevision += 1
                logBuffer.addLast(ConsoleLine(text = message, level = ConsoleLevel.SUCCESS, seq = logRevision))
                while (logBuffer.size > LOG_CAPACITY) logBuffer.removeFirst()
            }
            stagedSnapshot = ServerSnapshot(
                phase = ServerPhase.STOPPED,
                maxPlayers = stagedSnapshot.maxPlayers,
                logsRevision = logRevision
            )
            hasStaged = true
            publish()
        }
    }

    private fun consumeOutput(serverProcess: Process) {
        try {
            serverProcess.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    appendLog(line, levelForLine(line))
                    if (READY_PATTERN.containsMatchIn(line)) markReady()
                }
            }
        } catch (_: Exception) {
            // The process may be destroyed while the reader is blocked.
        }

        val exitCode = runCatching { serverProcess.waitFor() }.getOrDefault(-1)
        synchronized(lock) {
            if (process !== serverProcess) return
            process = null
            outputJob = null
            val wasStopping = stagedSnapshot.phase == ServerPhase.STOPPING || !shouldRemainRunning
            if (wasStopping) {
                stagedSnapshot = stagedSnapshot.copy(
                    phase = ServerPhase.STOPPED,
                    startedAt = null,
                    pid = null,
                    onlinePlayers = 0,
                    errorMessage = null
                )
            } else if (exitCode == 0) {
                stagedSnapshot = stagedSnapshot.copy(
                    phase = ServerPhase.STOPPED,
                    startedAt = null,
                    pid = null,
                    onlinePlayers = 0,
                    errorMessage = "Server exited normally. Start it again when you are ready."
                )
            } else {
                stagedSnapshot = stagedSnapshot.copy(
                    phase = ServerPhase.ERROR,
                    startedAt = null,
                    pid = null,
                    onlinePlayers = 0,
                    errorMessage = "Server stopped with exit code $exitCode."
                )
            }
            hasStaged = true
        }
        publish()
        appendLog(
            when (exitCode) {
                0 -> "Server process exited."
                else -> "Server process exited with code $exitCode."
            },
            if (exitCode == 0) ConsoleLevel.INFO else ConsoleLevel.ERROR
        )
    }

    private fun fail(message: String) {
        synchronized(lock) {
            shouldRemainRunning = false
            stagedSnapshot = stagedSnapshot.copy(
                phase = ServerPhase.ERROR,
                startedAt = null,
                pid = null,
                errorMessage = message
            )
            hasStaged = true
        }
        appendLog(message, ConsoleLevel.ERROR)
        publish()
    }

    private fun appendLog(text: String, level: ConsoleLevel = ConsoleLevel.INFO) {
        synchronized(lock) {
            appendLogLocked(text, level)
        }
    }

    private fun appendLogLocked(text: String, level: ConsoleLevel = ConsoleLevel.INFO) {
        // Cores occasionally emit ANSI colour codes and carriage returns; keep
        // them out of the buffer so the console stays readable and compact.
        val cleaned = text.replace('\r', ' ').replace(ANSI_PATTERN, "").trimEnd()
        if (cleaned.isEmpty()) return
        var current = stagedSnapshot
        val playerMatch = PLAYER_PATTERNS.asSequence().mapNotNull { it.find(cleaned) }.firstOrNull()
        if (playerMatch != null) {
            current = current.copy(
                onlinePlayers = playerMatch.groupValues[1].toIntOrNull() ?: current.onlinePlayers,
                maxPlayers = playerMatch.groupValues.getOrNull(2)?.toIntOrNull() ?: current.maxPlayers
            )
        } else if (cleaned.contains("joined the game", ignoreCase = true)) {
            current = current.copy(onlinePlayers = current.onlinePlayers + 1)
        } else if (cleaned.contains("left the game", ignoreCase = true)) {
            current = current.copy(onlinePlayers = (current.onlinePlayers - 1).coerceAtLeast(0))
        }
        TPS_PATTERN.find(cleaned)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.let { tps ->
            current = current.copy(currentTps = tps)
        }
        stagedSnapshot = current
        logRevision += 1
        logBuffer.addLast(ConsoleLine(text = cleaned, level = level, seq = logRevision))
        while (logBuffer.size > LOG_CAPACITY) logBuffer.removeFirst()
        hasStaged = true
    }

    private fun markReady() {
        synchronized(lock) {
            if (stagedSnapshot.phase == ServerPhase.STARTING) {
                stagedSnapshot = stagedSnapshot.copy(phase = ServerPhase.RUNNING)
                hasStaged = true
            }
            publish()
        }
    }

    private fun levelForLine(line: String): ConsoleLevel = when {
        line.contains("ERROR", ignoreCase = true) || line.contains("Exception", ignoreCase = true) -> ConsoleLevel.ERROR
        line.contains("WARN", ignoreCase = true) -> ConsoleLevel.WARNING
        line.contains("Done", ignoreCase = true) || line.contains("started", ignoreCase = true) -> ConsoleLevel.SUCCESS
        else -> ConsoleLevel.INFO
    }

    private fun buildCommand(config: ServerConfig, coreFile: File): List<String> {
        val launchArgs = parseArguments(config.launchArgs)
        val initialMemory = when (config.performanceMode) {
            PerformanceMode.BATSAVER -> (config.memoryMb / 2).coerceAtLeast(512)
            else -> config.memoryMb
        }
        val jvmFlags = COMMON_JVM_FLAGS + when (config.performanceMode) {
            PerformanceMode.BATSAVER -> listOf(
                "-XX:+UseSerialGC",
                "-XX:MaxGCPauseMillis=100",
                "-XX:MinHeapFreeRatio=20",
                "-XX:MaxHeapFreeRatio=40",
                "-XX:+DisableExplicitGC"
            )
            PerformanceMode.BALANCED -> listOf(
                "-XX:+UseG1GC",
                "-XX:MaxGCPauseMillis=75",
                "-XX:+UseStringDeduplication",
                "-XX:+DisableExplicitGC"
            )
            PerformanceMode.PERFORMANCE -> listOf(
                "-XX:+UseG1GC",
                "-XX:+ParallelRefProcEnabled",
                "-XX:MaxGCPauseMillis=50",
                "-XX:+UnlockExperimentalVMOptions",
                "-XX:+DisableExplicitGC",
                "-XX:+UseStringDeduplication",
                "-XX:G1NewSizePercent=30",
                "-XX:G1MaxNewSizePercent=40",
                "-XX:G1HeapRegionSize=${g1RegionSizeMb(config.memoryMb)}M",
                "-XX:G1ReservePercent=20",
                "-XX:InitiatingHeapOccupancyPercent=15",
                "-XX:MaxTenuringThreshold=1"
            )
        }
        return if (coreFile.extension.equals("jar", ignoreCase = true)) {
            // JVM flags the user typed (e.g. -XX:TieredStopAtLevel=1) must go
            // before -jar; server cores reject them as unknown program options.
            val userJvmFlags = launchArgs.filter { isJvmFlag(it) }
            val programArgs = launchArgs.filterNot { isJvmFlag(it) }
            if (userJvmFlags.isNotEmpty()) {
                appendLog(
                    "Moved JVM flags ${userJvmFlags.joinToString(" ")} before -jar so the core can read them.",
                    ConsoleLevel.INFO
                )
            }
            buildList {
                add(config.javaExecutable.trim().ifBlank { "java" })
                add("-Xms${initialMemory}M")
                add("-Xmx${config.memoryMb}M")
                addAll(jvmFlags)
                addAll(userJvmFlags)
                add("-jar")
                add(coreFile.absolutePath)
                addAll(programArgs)
            }
        } else {
            buildList {
                add(coreFile.absolutePath)
                addAll(launchArgs)
            }
        }
    }

    /**
     * G1 region size matched to the configured heap. A fixed 8 M only suits
     * multi-gigabyte heaps; on a 1–2 GB phone heap it leaves G1 with barely a
     * couple hundred regions to schedule, which hurts pause times. Must stay
     * a power of two between 1 M and 32 M.
     */
    private fun g1RegionSizeMb(memoryMb: Int): Int = when {
        memoryMb >= 6144 -> 16
        memoryMb >= 2048 -> 8
        memoryMb >= 1024 -> 4
        else -> 2
    }

    /** Total device RAM in MB, or null when it cannot be read. */
    private fun deviceRamMb(): Long? = runCatching {
        val info = android.app.ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager)
            .getMemoryInfo(info)
        info.totalMem / (1024L * 1024L)
    }.getOrNull()

    /** Recognises JVM options so they can be placed before `-jar`. */
    private fun isJvmFlag(argument: String): Boolean =
        argument.startsWith("-X") ||
            argument.startsWith("-XX") ||
            argument.startsWith("-D") ||
            argument.startsWith("-agentlib") ||
            argument.startsWith("-agentpath") ||
            argument.startsWith("-javaagent") ||
            argument.startsWith("-verbose") ||
            argument == "-ea" ||
            argument == "-da"

    private fun parseArguments(raw: String): List<String> {
        val arguments = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var escaped = false
        raw.trim().forEach { character ->
            when {
                escaped -> {
                    current.append(character)
                    escaped = false
                }
                character == '\\' && quote != '\'' -> escaped = true
                quote != null && character == quote -> quote = null
                quote == null && (character == '"' || character == '\'') -> quote = character
                quote == null && character.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        arguments += current.toString()
                        current.setLength(0)
                    }
                }
                else -> current.append(character)
            }
        }
        if (current.isNotEmpty()) arguments += current.toString()
        return arguments
    }

    private fun readProcessId(process: Process): Long? = runCatching {
        process.javaClass.getMethod("pid").invoke(process) as? Long
    }.getOrNull()

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            message.contains("java", ignoreCase = true) && message.contains("not found", ignoreCase = true) ->
                "Java was not found. Set a Java executable in Settings or choose a native server core."
            message.isNotBlank() -> message
            else -> "Unknown process error"
        }
    }
}

private class ServerWorkspace(private val context: Context) {

    /** One workspace per profile; the legacy layout stays for old installs. */
    private fun rootFor(config: ServerConfig): File {
        val profileId = config.profileId ?: return File(context.filesDir, "server")
        return File(File(context.filesDir, "servers"), profileId)
    }

    /**
     * Installs an imported resource pack or mod/plugin pack into the workspace.
     * A resource pack ZIP lands in `resourcepacks/`; a modpack ZIP's `mods/`,
     * `plugins/` and top-level JARs merge into the workspace so Forge/Fabric
     * and Paper/Bukkit setups both work. Import paths must live under this
     * app's private storage (they come from MineHost's own importer).
     */
    private fun installPacks(root: File, config: ServerConfig) {
        val packs = listOf(
            config.resourcePackPath to "resourcepacks",
            config.modpackPath to null
        )
        for ((path, subdirectory) in packs) {
            val source = path?.takeIf { it.isNotBlank() }?.let(::File) ?: continue
            if (!source.isFile) continue
            // Only archives the app imported itself: never trust arbitrary paths.
            if (!source.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator)) continue
            when {
                subdirectory != null -> {
                    val target = File(root, "$subdirectory/${source.name}")
                    if (source.canonicalPath != target.canonicalPath &&
                        (!target.exists() || target.length() != source.length())
                    ) {
                        target.parentFile?.mkdirs()
                        source.copyTo(target, overwrite = true)
                    }
                }
                isZipArchive(source) -> extractServerPack(source, root)
                // Bare server JARs from a pack go to the workspace root.
                source.extension.equals("jar", ignoreCase = true) -> {
                    val target = File(root, source.name)
                    if (source.canonicalPath != target.canonicalPath &&
                        (!target.exists() || target.length() != source.length())
                    ) {
                        source.copyTo(target, overwrite = true)
                    }
                }
            }
        }
    }

    /**
     * Merges a modpack ZIP into the workspace: `mods/`, `plugins/` and any
     * top-level JARs are extracted into the matching workspace folders.
     */
    private fun extractServerPack(archive: File, root: File) {
        java.util.zip.ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= 20_000) { "The modpack archive contains too many files." }
            for (entry in entries) {
                val name = entry.name.replace('\\', '/')
                require(name.isNotBlank() && !name.startsWith("/") && name.split('/').none { it == ".." }) {
                    "The modpack archive contains an unsafe path."
                }
                val resolved = File(root, name).canonicalPath
                require(resolved.startsWith(root.canonicalPath + File.separator)) {
                    "The modpack archive contains an unsafe path."
                }
            }
            for (entry in entries) {
                if (entry.isDirectory) {
                    File(root, entry.name).mkdirs()
                    continue
                }
                val name = entry.name.replace('\\', '/')
                val relevant = name.startsWith("mods/") || name.startsWith("plugins/") ||
                    (!name.contains('/') && name.endsWith(".jar", ignoreCase = true))
                if (!relevant) continue
                val target = File(root, name)
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private fun isZipArchive(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(2)
            input.read(header) == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        }
    }.getOrDefault(false)

    fun prepare(config: ServerConfig): File {
        val root = rootFor(config)
        root.mkdirs()
        File(root, "eula.txt").writeText(if (config.acceptEula) "eula=true\n" else "eula=false\n")
        installPacks(root, config)

        val properties = Properties().apply {
            setProperty("motd", config.motd.replace(Regex("[\\r\\n]"), " "))
            setProperty("server-port", config.port.toString())
            setProperty("max-players", config.maxPlayers.toString())
            setProperty("level-name", config.levelName.replace(Regex("[^A-Za-z0-9_-]"), "_"))
            setProperty("online-mode", (config.authMode == ServerAuthMode.ONLINE).toString())
            setProperty("enforce-secure-profile", (config.authMode == ServerAuthMode.ONLINE).toString())
            setProperty("allow-flight", config.allowFlight.toString())
            setProperty("enable-query", config.enableQuery.toString())
            setProperty("enable-rcon", config.enableRcon.toString())
            setProperty("rcon.port", (config.port + 10).coerceAtMost(65535).toString())
            if (config.rconPassword.isNotBlank()) setProperty("rcon.password", config.rconPassword)
            setProperty("view-distance", config.viewDistance.coerceIn(2, 32).toString())
            setProperty("simulation-distance", config.simulationDistance.coerceIn(2, 32).toString())
            setProperty("network-compression-threshold", config.networkCompressionThreshold.coerceIn(-1, 65536).toString())
            setProperty("difficulty", config.difficulty)
            setProperty("gamemode", config.gamemode)
            setProperty("level-type", config.levelType)
            setProperty("level-seed", config.levelSeed)
            setProperty("spawn-protection", config.spawnProtection.coerceIn(0, 1000000).toString())
            setProperty("enable-command-block", config.enableCommandBlock.toString())
            setProperty("player-idle-timeout", config.playerIdleTimeout.coerceIn(0, 1440).toString())
            setProperty("pause-when-empty-seconds", config.pauseWhenEmptySeconds.coerceIn(0, 86400).toString())
            setProperty("resource-pack", config.resourcePackUrl)
            setProperty("resource-pack-sha1", config.resourcePackSha1)
            setProperty("require-resource-pack", config.requireResourcePack.toString())
            setProperty("resource-pack-prompt", config.resourcePackPrompt)
            setProperty("enable-status", config.enableStatus.toString())
            setProperty("hide-online-players", config.hideOnlinePlayers.toString())
            setProperty("sync-chunk-writes", config.syncChunkWrites.toString())
            setProperty("use-native-transport", config.useNativeTransport.toString())
            setProperty("allow-nether", config.allowNether.toString())
            setProperty("generate-structures", config.generateStructures.toString())
        }
        File(root, "server.properties").outputStream().use { properties.store(it, "MineHost server settings") }
        return root
    }
}
