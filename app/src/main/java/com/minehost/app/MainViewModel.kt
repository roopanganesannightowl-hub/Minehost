package com.minehost.app

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minehost.app.data.CgnatState
import com.minehost.app.data.PlayitTunnelClient
import com.minehost.app.data.PlayitTunnelState
import com.minehost.app.data.ProfilesState
import com.minehost.app.data.RouterCheckResult
import com.minehost.app.data.RouterNetworkType
import com.minehost.app.data.ServerConfig
import com.minehost.app.data.TunnelState
import com.minehost.app.data.ServerPhase
import com.minehost.app.data.ServerSnapshot
import com.minehost.app.service.ServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LOG_TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MineHostApplication
    private val container = app.container
    private val _message = MutableStateFlow<String?>(null)
    private val _commandHistory = MutableStateFlow<List<String>>(emptyList())
    private val _routerCheck = MutableStateFlow<RouterCheckResult?>(null)
    // Shared with ServerService through the container so both control one tunnel.
    private val tunnelManager get() = container.tunnel
    private val playitTunnel get() = container.playitTunnel
    private val _routerChecking = MutableStateFlow(false)
    private val _routerMappingBusy = MutableStateFlow(false)
    private val _playitClaimBusy = MutableStateFlow(false)

    /** The active profile's config — every screen edits this, never legacy settings. */
    val config: StateFlow<ServerConfig> = container.profiles.state
        .map { it.active?.config ?: ServerConfig() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerConfig())

    /** All profiles plus which one is active, for the switcher UI. */
    val profilesState: StateFlow<ProfilesState> = container.profiles.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfilesState())
    val snapshot: StateFlow<ServerSnapshot> = container.runtime.snapshot
    val message: StateFlow<String?> = _message.asStateFlow()
    /** Most recent commands, newest first, so the console can offer recall. */
    val commandHistory: StateFlow<List<String>> = _commandHistory.asStateFlow()
    /** null until the stored flag loads, so the first launch never flashes. */
    val onboardingComplete: StateFlow<Boolean?> = container.settings.onboardingComplete
        .map { it as Boolean? }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val routerCheck: StateFlow<RouterCheckResult?> = _routerCheck.asStateFlow()
    val tunnelState: StateFlow<TunnelState> = tunnelManager.state

    /** Persistent playit.gg tunnel state (only meaningful once an agent is claimed). */
    val playitTunnelState: StateFlow<PlayitTunnelState> = playitTunnel.state

    /** Secret stored = agent claimed; blank means the claim flow still has to run. */
    val playitConfigured: StateFlow<Boolean> = container.settings.playitSecretKey
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val routerChecking: StateFlow<Boolean> = _routerChecking.asStateFlow()
    val routerMappingBusy: StateFlow<Boolean> = _routerMappingBusy.asStateFlow()
    val playitClaimBusy: StateFlow<Boolean> = _playitClaimBusy.asStateFlow()

    /** When on (default), the playit tunnel opens itself with the server. */
    val autoOpenTunnel: StateFlow<Boolean> = container.settings.autoOpenTunnel
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun startServer() {
        ContextCompat.startForegroundService(
            getApplication<Application>(),
            Intent(getApplication<Application>(), ServerService::class.java).setAction(ServerService.ACTION_START)
        )
    }

    fun stopServer() {
        if (_routerCheck.value?.mappingActive == true) {
            removeRouterMapping()
        }
        // A stopped server has nothing to tunnel; both relays are closed so a
        // dead endpoint is never advertised to friends.
        tunnelManager.resetForServerStop()
        playitTunnel.resetForServerStop()
        getApplication<Application>().startService(
            Intent(getApplication<Application>(), ServerService::class.java).setAction(ServerService.ACTION_STOP)
        )
    }

    fun restartServer() {
        if (_routerCheck.value?.mappingActive == true) {
            removeRouterMapping()
        }
        // Deliberately NOT closing the tunnels: a restart keeps the same local
        // port, so both relays (and their addresses) survive it untouched.
        ContextCompat.startForegroundService(
            getApplication<Application>(),
            Intent(getApplication<Application>(), ServerService::class.java).setAction(ServerService.ACTION_RESTART)
        )
    }

    fun startTunnel() {
        if (container.runtime.snapshot.value.phase != ServerPhase.RUNNING) {
            _message.value = "Start the server before opening a tunnel"
            return
        }
        tunnelManager.start()
    }

    fun stopTunnel() {
        tunnelManager.stop()
    }

    /** Runs the one-time playit.gg claim: create code, open browser, wait, store secret. */
    fun startPlayitClaim() {
        if (_playitClaimBusy.value) return
        _playitClaimBusy.value = true
        viewModelScope.launch {
            try {
                val client = PlayitTunnelClient { com.minehost.app.data.PlayitTunnelClient.PlayitTunnelSettings() }
                val claim = client.startClaim()
                openInBrowser(claim.url)
                // The official agent polls /claim/setup until the user acts,
                // and only then swaps the code for a secret.
                var accepted = false
                for (attempt in 0 until PlayitTunnelClient.CLAIM_POLL_ATTEMPTS) {
                    when (client.pollClaim(claim.code)) {
                        PlayitTunnelClient.ClaimPhase.ACCEPTED -> {
                            accepted = true
                            break
                        }
                        PlayitTunnelClient.ClaimPhase.REJECTED -> {
                            _message.value = "The claim was rejected in the browser — start again"
                            return@launch
                        }
                        else -> delay(PlayitTunnelClient.CLAIM_POLL_INTERVAL_MS)
                    }
                }
                if (!accepted) {
                    _message.value = "The claim code timed out — try again"
                    return@launch
                }
                // Acceptance can lag a moment behind; a few exchanges are normal.
                repeat(PlayitTunnelClient.CLAIM_EXCHANGE_ATTEMPTS) {
                    val secret = client.exchangeClaim(claim.code)
                    if (secret != null) {
                        container.settings.setPlayitSecretKey(secret)
                        _message.value = "playit.gg agent linked — open its tunnel from Settings"
                        return@launch
                    }
                    delay(PlayitTunnelClient.CLAIM_POLL_INTERVAL_MS)
                }
                _message.value = "The claim was accepted but the secret never arrived — try again"
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _message.value = error.message ?: "Could not link a playit.gg agent"
            } finally {
                _playitClaimBusy.value = false
            }
        }
    }

    private fun openInBrowser(url: String) {
        runCatching {
            getApplication<Application>().startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { _message.value = "Open $url in your browser to confirm" }
    }

    /** Removes the stored playit secret so a new agent can be claimed. */
    fun unlinkPlayit() {
        playitTunnel.stop()
        viewModelScope.launch {
            container.settings.setPlayitSecretKey("")
            _message.value = "playit.gg agent unlinked"
        }
    }

    fun startPlayitTunnel() {
        if (container.runtime.snapshot.value.phase != ServerPhase.RUNNING) {
            _message.value = "Start the server before opening a tunnel"
            return
        }
        if (!playitConfigured.value) {
            _message.value = "Link a playit.gg agent first"
            return
        }
        playitTunnel.start()
    }

    fun stopPlayitTunnel() {
        playitTunnel.stop()
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            runCatching { container.settings.setOnboardingComplete(true) }
        }
    }

    fun clearLogs() = container.runtime.clearLogs()

    /** Copies the whole console buffer so it can be pasted into a bug report. */
    fun copyConsoleLog() {
        val logs = container.runtime.snapshot.value.logs
        if (logs.isEmpty()) {
            _message.value = "The console is empty"
            return
        }
        val text = logs.joinToString("\n") { line ->
            "${LOG_TIME_FORMAT.format(Date(line.timestamp))}  ${line.level.name}  ${line.text}"
        }
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("MineHost console", text))
        _message.value = "Console log copied (${logs.size} lines)"
    }

    fun sendCommand(command: String) {
        container.runtime.sendCommand(command)
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return
        _commandHistory.value = (listOf(trimmed) + _commandHistory.value.filterNot { it.equals(trimmed, ignoreCase = true) })
            .take(MAX_HISTORY)
    }

    fun checkRouter(port: Int = config.value.port) {
        if (_routerChecking.value) return
        _routerChecking.value = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    container.routerDiagnostics.inspect(
                        config = config.value.copy(port = port),
                        serverRunning = container.runtime.snapshot.value.phase == ServerPhase.RUNNING
                    )
                }
                _routerCheck.value = result
            } catch (error: Exception) {
                _routerCheck.value = RouterCheckResult(
                    serverPort = port,
                    errorMessage = error.message ?: "Router check failed"
                )
                _message.value = "Router check failed"
            } finally {
                _routerChecking.value = false
            }
        }
    }

    /**
     * One-tap public access: inspect the network, then ask the router for a
     * TCP port mapping when the network allows it.
     */
    fun checkRouterAndMap(port: Int = config.value.port) {
        if (_routerChecking.value || _routerMappingBusy.value) return
        _routerChecking.value = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    container.routerDiagnostics.inspect(
                        config = config.value.copy(port = port),
                        serverRunning = container.runtime.snapshot.value.phase == ServerPhase.RUNNING
                    )
                }
                _routerCheck.value = result
                if (!result.canRequestMapping) {
                    _message.value = when {
                        result.cgnatState == CgnatState.LIKELY -> "Carrier NAT detected — see the options below"
                        result.networkType != RouterNetworkType.WIFI -> "Connect to Wi-Fi to map a port automatically"
                        !result.hasInternet -> "No active internet connection"
                        else -> "Checked. Forward the port using the values below"
                    }
                    return@launch
                }
                _routerMappingBusy.value = true
                val mapping = withContext(Dispatchers.IO) {
                    container.routerDiagnostics.requestPortMapping(result)
                }
                _routerCheck.value = result.copy(
                    mappingActive = mapping.success,
                    mappingMethod = mapping.method ?: result.mappingMethod,
                    mappingMessage = mapping.message
                )
                _message.value = mapping.message
            } catch (error: Exception) {
                _routerCheck.value = RouterCheckResult(
                    serverPort = port,
                    errorMessage = error.message ?: "Router check failed"
                )
                _message.value = "Router check failed"
            } finally {
                _routerChecking.value = false
                _routerMappingBusy.value = false
            }
        }
    }

    fun requestRouterMapping() {
        val current = _routerCheck.value
        if (current == null) {
            _message.value = "Run Router Check first"
            checkRouter()
            return
        }
        if (_routerMappingBusy.value) return
        _routerMappingBusy.value = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    container.routerDiagnostics.requestPortMapping(current)
                }
                _routerCheck.value = current.copy(
                    mappingActive = result.success,
                    mappingMethod = result.method ?: current.mappingMethod,
                    mappingMessage = result.message
                )
                _message.value = result.message
            } catch (error: Exception) {
                _message.value = error.message ?: "Could not request the router port mapping"
            } finally {
                _routerMappingBusy.value = false
            }
        }
    }

    fun removeRouterMapping() {
        val current = _routerCheck.value ?: return
        if (_routerMappingBusy.value) return
        _routerMappingBusy.value = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    container.routerDiagnostics.removePortMapping(current)
                }
                _routerCheck.value = current.copy(
                    mappingActive = false,
                    mappingMethod = result.method ?: current.mappingMethod,
                    mappingMessage = result.message
                )
                _message.value = result.message
            } catch (error: Exception) {
                _message.value = error.message ?: "Could not remove the router port mapping"
            } finally {
                _routerMappingBusy.value = false
            }
        }
    }

    fun saveConfig(newConfig: ServerConfig) {
        viewModelScope.launch {
            try {
                persistConfig(newConfig)
                _message.value = "Settings saved"
            } catch (_: Exception) {
                _message.value = "Could not save settings"
            }
        }
    }

    /**
     * Persists the active profile's config without touching the snackbar, so
     * flows that carry their own message (pack imports, world restore) are not
     * overwritten by a late "Settings saved".
     */
    private suspend fun persistConfig(newConfig: ServerConfig) {
        val active = container.profiles.current().active
        if (active != null) {
            container.profiles.saveConfig(active.id, newConfig)
        } else {
            container.settings.save(newConfig)
        }
    }

    // ------------------------------------------------------------------
    // Multi-server profiles
    // ------------------------------------------------------------------

    fun createProfile(name: String) {
        viewModelScope.launch {
            try {
                container.profiles.create(name)
                _message.value = "Server \"$name\" created"
            } catch (_: Exception) {
                _message.value = "Could not create the server"
            }
        }
    }

    fun switchProfile(profileId: String) {
        viewModelScope.launch {
            val snapshot = container.runtime.snapshot.value
            if (snapshot.isActive) {
                _message.value = "Stop the running server before switching"
                return@launch
            }
            container.profiles.setActive(profileId)
        }
    }

    fun renameProfile(profileId: String, name: String) {
        viewModelScope.launch {
            try {
                container.profiles.rename(profileId, name)
                _message.value = "Server renamed"
            } catch (_: Exception) {
                _message.value = "Could not rename the server"
            }
        }
    }

    fun duplicateProfile(profileId: String) {
        viewModelScope.launch {
            try {
                container.profiles.duplicate(profileId)
                _message.value = "Server duplicated — pick a core for the copy"
            } catch (_: Exception) {
                _message.value = "Could not duplicate the server"
            }
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            val snapshot = container.runtime.snapshot.value
            if (snapshot.isActive) {
                _message.value = "Stop the running server before deleting"
                return@launch
            }
            container.profiles.delete(profileId)
            _message.value = "Server deleted"
        }
    }

    fun importCore(uri: Uri) {
        viewModelScope.launch {
            try {
                val imported = container.coreImporter.import(uri)
                val active = container.profiles.current().active
                if (active != null) {
                    container.profiles.saveConfig(
                        active.id,
                        active.config.copy(corePath = imported.path, coreName = imported.displayName)
                    )
                } else {
                    container.settings.save(
                        config.value.copy(corePath = imported.path, coreName = imported.displayName)
                    )
                }
                _message.value = "${imported.displayName} is ready"
            } catch (_: Exception) {
                _message.value = "Could not import that server core"
            }
        }
    }

    fun exportWorld(uri: Uri) {
        if (container.runtime.snapshot.value.isActive) {
            _message.value = "Stop the server before exporting a world backup"
            return
        }
        viewModelScope.launch {
            try {
                val active = container.profiles.current().active?.config ?: config.value
                val fileCount = withContext(Dispatchers.IO) { container.worldBackup.export(uri, active) }
                _message.value = "World backup exported ($fileCount files)"
            } catch (error: Exception) {
                _message.value = error.message ?: "Could not export the world backup"
            }
        }
    }

    fun importWorld(uri: Uri) {
        if (container.runtime.snapshot.value.isActive) {
            _message.value = "Stop the server before restoring a world or server pack"
            return
        }
        viewModelScope.launch {
            try {
                val current = container.profiles.current().active?.config ?: container.settings.current()
                val result = withContext(Dispatchers.IO) { container.worldBackup.import(uri, current) }
                if (result.importedCorePath != null) {
                    persistConfig(
                        current.copy(
                            corePath = result.importedCorePath,
                            coreName = "Imported server pack"
                        )
                    )
                } else {
                    persistConfig(current.copy(corePath = null, coreName = null))
                }
                _message.value = if (result.importedCorePath != null) {
                    "Server pack restored; review Settings before starting"
                } else {
                    "World restored; import a server core before starting"
                }
            } catch (error: Exception) {
                _message.value = error.message ?: "Could not restore the world backup"
            }
        }
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ------------------------------------------------------------------
    // Auto tunnel + pack imports
    // ------------------------------------------------------------------

    fun setAutoOpenTunnel(enabled: Boolean) {
        viewModelScope.launch {
            container.settings.setAutoOpenTunnel(enabled)
        }
    }

    /** Imports a resource pack; applied to the workspace on next server start. */
    fun importResourcePack(uri: Uri) {
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) { container.coreImporter.import(uri) }
                val current = container.profiles.current().active?.config ?: container.settings.current()
                persistConfig(current.copy(resourcePackPath = imported.path))
                _message.value = "${imported.displayName} will be applied on the next start"
            } catch (_: Exception) {
                _message.value = "Could not import that resource pack"
            }
        }
    }

    /** Imports a mod/plugin pack; merged into the workspace on next server start. */
    fun importModpack(uri: Uri) {
        viewModelScope.launch {
            try {
                val imported = withContext(Dispatchers.IO) { container.coreImporter.import(uri) }
                val current = container.profiles.current().active?.config ?: container.settings.current()
                persistConfig(current.copy(modpackPath = imported.path))
                _message.value = "${imported.displayName} will be installed on the next start"
            } catch (_: Exception) {
                _message.value = "Could not import that mod pack"
            }
        }
    }

    /**
     * Imports a single plugin JAR; it is copied into the workspace `plugins/`
     * folder on the next server start. Importing the same file again replaces
     * the previous copy instead of listing it twice.
     */
    fun importPlugin(uri: Uri) {
        viewModelScope.launch {
            try {
                val imported = container.pluginImporter.import(uri)
                val current = container.profiles.current().active?.config ?: container.settings.current()
                val paths = current.pluginPaths
                    .filterNot { File(it).name == imported.fileName }
                    .plus(imported.path)
                val label = imported.descriptor.label().ifBlank { imported.fileName }
                persistConfig(current.copy(pluginPaths = paths))
                _message.value = "$label will load on the next start"
            } catch (error: Exception) {
                _message.value = error.message ?: "Could not import that plugin"
            }
        }
    }

    /** Removes a plugin from the config and deletes the app's copy of the JAR. */
    fun removePlugin(path: String) {
        viewModelScope.launch {
            try {
                val current = container.profiles.current().active?.config ?: container.settings.current()
                persistConfig(current.copy(pluginPaths = current.pluginPaths - path))
                container.pluginImporter.delete(path)
                _message.value = "Plugin removed; restart the server to unload it"
            } catch (_: Exception) {
                _message.value = "Could not remove that plugin"
            }
        }
    }

    private companion object {
        const val MAX_HISTORY = 24
    }
}
