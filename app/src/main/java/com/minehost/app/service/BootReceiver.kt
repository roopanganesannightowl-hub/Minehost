package com.minehost.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.minehost.app.MineHostApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val pendingResult = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val config = (app as MineHostApplication).container.profiles.activeConfig()
                if (config.startOnBoot && config.isConfigured && config.acceptEula) {
                    runCatching {
                        ContextCompat.startForegroundService(
                            app,
                            Intent(app, ServerService::class.java).setAction(ServerService.ACTION_START)
                        )
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
