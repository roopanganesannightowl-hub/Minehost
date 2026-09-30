package com.minehost.app

import android.app.Application
import com.minehost.app.data.CoreImporter
import com.minehost.app.data.PlayitTunnelClient
import com.minehost.app.data.ProfilesRepository
import com.minehost.app.data.SettingsRepository
import com.minehost.app.data.RouterDiagnosticsManager
import com.minehost.app.data.TunnelManager
import com.minehost.app.data.WorldBackupManager
import com.minehost.app.service.ServerRuntime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MineHostApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(private val application: Application) {
    val settings = SettingsRepository(application)
    val profiles = ProfilesRepository(application)
    val coreImporter = CoreImporter(application)
    val runtime = ServerRuntime(application)
    val routerDiagnostics = RouterDiagnosticsManager(application)
    val worldBackup = WorldBackupManager(application)

    init {
        // One-time wrap of the legacy single-server config into the first
        // profile; blocking is safe during Application startup.
        runBlocking {
            runCatching { profiles.migrateLegacyIfNeeded(settings.current()) }
        }
    }

    val tunnel = TunnelManager {
        TunnelManager.TunnelSettings(localPort = profiles.activeConfig().port)
    }

    /** Persistent playit.gg relay tunnel; secret comes from the claim flow. */
    val playitTunnel = PlayitTunnelClient {
        PlayitTunnelClient.PlayitTunnelSettings(
            localPort = profiles.activeConfig().port,
            secretKey = settings.playitSecretKey.first()
        )
    }
}
