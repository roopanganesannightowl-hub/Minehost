package com.minehost.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.minehost.app.data.CoreCatalogRepository
import com.minehost.app.data.CoreDownloader
import com.minehost.app.data.CorePlatformKind
import com.minehost.app.data.CatalogUiState
import com.minehost.app.data.MinecraftVersion
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class CatalogViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MineHostApplication
    private val repository = CoreCatalogRepository()
    private val downloader = CoreDownloader(app)
    private val settings = app.container.settings
    private val profiles = app.container.profiles
    private val _state = MutableStateFlow(CatalogUiState())
    private var loadJob: Job? = null
    private var resolveJob: Job? = null
    private var resolveGeneration = 0L

    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    fun refreshVersions() {
        loadJob?.cancel()
        resolveJob?.cancel()
        resolveGeneration++
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoadingVersions = true, error = null, notice = null) }
            try {
                val versions = withTimeoutOrNull(15_000) { repository.loadVersions() }
                if (versions == null) {
                    _state.update {
                        it.copy(
                            isLoadingVersions = false,
                            error = "The version list timed out. Tap retry."
                        )
                    }
                    return@launch
                }
                val selected = versions.firstOrNull { it.type == "release" } ?: versions.firstOrNull()
                _state.update {
                    it.copy(
                        isLoadingVersions = false,
                        versions = versions,
                        selectedVersion = selected
                    )
                }
                resolveSelected()
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        isLoadingVersions = false,
                        error = error.message ?: "Could not load Minecraft versions"
                    )
                }
            }
        }
    }

    fun selectVersion(version: MinecraftVersion) {
        _state.update { it.copy(selectedVersion = version, asset = null, downloadedName = null, error = null, notice = null) }
        resolveSelected()
    }

    fun selectPlatform(platformId: String) {
        _state.update { it.copy(selectedPlatformId = platformId, asset = null, downloadedName = null, error = null, notice = null) }
        resolveSelected()
    }

    fun downloadSelected() {
        val asset = _state.value.asset ?: return
        if (_state.value.isDownloading) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isDownloading = true,
                    downloadProgress = 0f,
                    downloadedName = null,
                    error = null,
                    notice = null
                )
            }
            try {
                var lastReportedProgress = -1f
                val imported = downloader.download(asset) { progress ->
                    val step = progress?.let { (it * 100).toInt() / 100f }
                    if (step != null && step != lastReportedProgress) {
                        lastReportedProgress = step
                        _state.update { it.copy(downloadProgress = step) }
                    }
                }
                if (asset.canInstallAsCore) {
                    // The runtime reads the active profile's config, so a core
                    // downloaded from the catalog must be recorded there.
                    val profile = profiles.current().active
                    if (profile != null) {
                        profiles.saveConfig(
                            profile.id,
                            profile.config.copy(
                                corePath = imported.path,
                                coreName = imported.displayName
                            )
                        )
                    } else {
                        val current = settings.current()
                        settings.save(
                            current.copy(
                                corePath = imported.path,
                                coreName = imported.displayName
                            )
                        )
                    }
                }
                _state.update {
                    it.copy(
                        isDownloading = false,
                        downloadProgress = 1f,
                        downloadedName = imported.displayName,
                        notice = if (asset.canInstallAsCore) {
                            "${imported.displayName} is installed and selected."
                        } else {
                            "${imported.displayName} downloaded. It still needs to be set up by hand."
                        }
                    )
                }
            } catch (error: Exception) {
                _state.update {
                    it.copy(
                        isDownloading = false,
                        downloadProgress = null,
                        error = error.message ?: "The download could not be completed"
                    )
                }
            }
        }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, notice = null) }
    }

    private fun resolveSelected() {
        resolveJob?.cancel()
        val generation = ++resolveGeneration
        val platform = CoreCatalogRepository.platform(_state.value.selectedPlatformId)
        val version = _state.value.selectedVersion
        if (version == null) {
            _state.update { it.copy(isResolving = false, asset = null) }
            return
        }
        if (platform.kind == CorePlatformKind.MANUAL || platform.kind == CorePlatformKind.CLIENT_ONLY) {
            _state.update {
                it.copy(
                    isResolving = false,
                    asset = null,
                    notice = when (platform.kind) {
                        CorePlatformKind.CLIENT_ONLY -> "Client-only project — it cannot host a server."
                        else -> "MineHost will not guess a binary for this project."
                    }
                )
            }
            return
        }

        resolveJob = viewModelScope.launch {
            _state.update { it.copy(isResolving = true, asset = null, error = null) }
            try {
                val asset = withTimeoutOrNull(15_000) { repository.resolve(platform, version) }
                if (generation != resolveGeneration) return@launch
                if (asset == null) {
                    _state.update {
                        it.copy(
                            isResolving = false,
                            asset = null,
                            error = "Could not resolve the ${platform.name} build. Tap retry."
                        )
                    }
                    return@launch
                }
                _state.update { it.copy(isResolving = false, asset = asset, error = null) }
            } catch (error: Exception) {
                if (generation != resolveGeneration) return@launch
                _state.update {
                    it.copy(
                        isResolving = false,
                        asset = null,
                        error = error.message ?: "No matching build was found"
                    )
                }
            }
        }
    }
}
