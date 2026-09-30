package com.minehost.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.minehost.app.ui.MineHostApp
import com.minehost.app.ui.theme.MineHostTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MineHostTheme {
                // Notification permission is requested after the first-launch
                // walkthrough, from MineHostApp.
                MineHostApp()
            }
        }
    }
}
