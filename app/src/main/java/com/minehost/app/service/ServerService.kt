package com.minehost.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.minehost.app.MainActivity
import com.minehost.app.MineHostApplication
import com.minehost.app.R
import com.minehost.app.data.ServerPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ServerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime
        get() = (application as MineHostApplication).container.runtime
    private val tunnel
        get() = (application as MineHostApplication).container.tunnel
    private var wakeLock: PowerManager.WakeLock? = null
    private var observer: kotlinx.coroutines.Job? = null
    private var isForeground = false
    private var startRequested = false
    private var restarting = false
    private var lastNotifiedState: String? = null
    @Volatile private var autoTunnelAttempted = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        observer = serviceScope.launch {
            runtime.snapshot.collectLatest { snapshot ->
                updateNotification(snapshot.phase, snapshot.onlinePlayers, snapshot.maxPlayers)
                // The playit tunnel is what friends join through; when auto-open
                // is on (default) it connects the moment the server is live.
                // Flag goes up synchronously so a burst of snapshot emissions
                // cannot double-fire or cancel the attempt mid-way.
                if (snapshot.phase == ServerPhase.RUNNING && !autoTunnelAttempted) {
                    autoTunnelAttempted = true
                    serviceScope.launch { autoOpenPlayitTunnel() }
                }
                // A restart passes through STOPPED on purpose, so don't tear the
                // service down mid-restart.
                if (isForeground && startRequested && !restarting && (snapshot.phase == ServerPhase.STOPPED || snapshot.phase == ServerPhase.ERROR)) {
                    startRequested = false
                    tunnel.resetForServerStop()
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isForeground = false
                    stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                startRequested = isForeground
                runtime.requestStop()
                if (!isForeground) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                startRequested = true
                lastNotifiedState = null
                if (!isForeground) {
                    startHosting()
                } else {
                    // Stop gracefully, then bring the same configuration back up.
                    restarting = true
                    serviceScope.launch {
                        try {
                            runtime.stop()
                            val config = (application as MineHostApplication).container.profiles.activeConfig()
                            runtime.start(config)
                        } catch (_: Exception) {
                            startRequested = false
                        } finally {
                            restarting = false
                        }
                    }
                }
            }
            ACTION_START, null -> {
                startRequested = true
                lastNotifiedState = null
                startHosting()
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Opens the playit tunnel once per service life when the server is up, if
     * the agent is linked and the user has not turned auto-open off. Failure to
     * open is silent here — the Settings card already reports tunnel errors.
     */
    private suspend fun autoOpenPlayitTunnel() {
        val container = (application as MineHostApplication).container
        val configured = runCatching { container.settings.playitSecretKey.first().isNotBlank() }
            .getOrDefault(false)
        val autoOpen = runCatching { container.settings.autoOpenTunnel.first() }.getOrDefault(true)
        if (configured && autoOpen) container.playitTunnel.start()
    }

    private fun startHosting() {
        if (!isForeground) {
            val notification = buildNotification(ServerPhase.STARTING, 0, 20)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        }

        serviceScope.launch {
            try {
                val config = (application as MineHostApplication).container.profiles.activeConfig()
                acquireWakeLock(config.keepAwake)
                runtime.start(config)
            } catch (_: Exception) {
                startRequested = false
                releaseWakeLock()
                if (isForeground) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isForeground = false
                }
                stopSelf()
            }
        }
    }

    private fun acquireWakeLock(enabled: Boolean) {
        if (!enabled) {
            releaseWakeLock()
            return
        }
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "${packageName}:MinecraftServer"
        ).apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun updateNotification(phase: ServerPhase, players: Int, maxPlayers: Int) {
        if (!isForeground) return
        // The snapshot emits on every console line; only redraw the notification
        // when the user-visible state actually changed (phase or player counts).
        val stateKey = "$phase:$players:$maxPlayers"
        if (stateKey == lastNotifiedState) return
        lastNotifiedState = stateKey
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(phase, players, maxPlayers))
    }

    private fun buildNotification(phase: ServerPhase, players: Int, maxPlayers: Int): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val restartIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, ServerService::class.java).setAction(ACTION_RESTART),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when (phase) {
            ServerPhase.RUNNING -> "MineHost is online"
            ServerPhase.STARTING -> "Starting Minecraft server…"
            ServerPhase.STOPPING -> "Stopping server…"
            ServerPhase.ERROR -> "Minecraft server needs attention"
            ServerPhase.STOPPED -> "Minecraft server stopped"
        }
        val text = when (phase) {
            ServerPhase.RUNNING -> "$players/$maxPlayers players online • tap to open console"
            ServerPhase.STARTING -> "Preparing your world and server core"
            ServerPhase.STOPPING -> "Saving the world safely"
            ServerPhase.ERROR -> "Tap to review the console"
            ServerPhase.STOPPED -> "Tap to start again"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_minehost)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(phase == ServerPhase.STARTING || phase == ServerPhase.RUNNING || phase == ServerPhase.STOPPING)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stat_minehost, "Stop", stopIntent)
        if (phase == ServerPhase.RUNNING) {
            builder.addAction(R.drawable.ic_stat_minehost, "Restart", restartIntent)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Server status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows whether your Minecraft server is running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        releaseWakeLock()
        observer?.cancel()
        serviceScope.cancel()
        if (runtime.snapshot.value.isActive) runtime.requestStop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.minehost.app.action.START_SERVER"
        const val ACTION_STOP = "com.minehost.app.action.STOP_SERVER"
        const val ACTION_RESTART = "com.minehost.app.action.RESTART_SERVER"
        private const val CHANNEL_ID = "server_status"
        private const val NOTIFICATION_ID = 41
        private const val WAKELOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
    }
}
