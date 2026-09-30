package com.minehost.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minehost.app.data.ServerAuthMode
import com.minehost.app.data.ServerConfig
import com.minehost.app.data.ServerPhase
import com.minehost.app.data.ServerSnapshot
import com.minehost.app.ui.components.InfoRow
import com.minehost.app.ui.components.MetricCard
import com.minehost.app.ui.components.MineHostMark
import com.minehost.app.ui.components.SectionHeader
import com.minehost.app.ui.components.StatusOrb
import com.minehost.app.ui.components.StatusPill
import com.minehost.app.util.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun DashboardScreen(
    config: ServerConfig,
    snapshot: ServerSnapshot,
    snackbarHostState: SnackbarHostState,
    profiles: List<com.minehost.app.data.ServerProfile>,
    activeProfileId: String?,
    onSelectProfile: (String) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConsole: () -> Unit,
    onOpenCatalog: () -> Unit,
    onCopyAddress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalView.current.context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    var serverAddress by remember(config.port) { mutableStateOf("localhost:${config.port}") }
    // Interface enumeration is blocking I/O — keep it off the main thread.
    LaunchedEffect(config.port) {
        val ip = withContext(Dispatchers.IO) { NetworkUtils.localIpv4Address() } ?: "localhost"
        serverAddress = "$ip:${config.port}"
    }
    val copyToClipboard: (String) -> Unit = { value ->
        clipboard?.setPrimaryClip(ClipData.newPlainText("server address", value))
        // Android 13+ already shows the system "Copied" chip — avoid double feedback.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onCopyAddress()
    }
    val startedAt = snapshot.startedAt
    var uptime by remember(startedAt) { mutableStateOf("—") }
    LaunchedEffect(startedAt) {
        if (startedAt == null) {
            uptime = "—"
        } else {
            while (true) {
                uptime = formatUptime(System.currentTimeMillis() - startedAt)
                delay(1_000)
            }
        }
    }
    val isRunning = snapshot.phase == ServerPhase.RUNNING
    val isStopping = snapshot.phase == ServerPhase.STOPPING
    val canStop = snapshot.phase == ServerPhase.RUNNING || snapshot.phase == ServerPhase.STARTING
    val setupComplete = config.isConfigured && config.acceptEula &&
        (!config.enableRcon || config.rconPassword.isNotBlank())

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MineHostMark()
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "YOUR SERVER",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        config.serverName,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1
                    )
                }
                if (profiles.size > 1) {
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Rounded.AccountTree, contentDescription = "Switch server")
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            profiles.forEach { profile ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(profile.name)
                                            Text(
                                                "port ${profile.config.port}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    trailingIcon = if (profile.id == activeProfileId) {
                                        { Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }
                                    } else null,
                                    onClick = {
                                        expanded = false
                                        if (profile.id != activeProfileId) onSelectProfile(profile.id)
                                    }
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Open settings")
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.72f)
                                )
                            )
                        )
                        .padding(20.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusOrb(snapshot.phase, size = 68.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                StatusPill(snapshot.phase)
                                Text(
                                    when (snapshot.phase) {
                                        ServerPhase.RUNNING -> "Your world is live"
                                        ServerPhase.STARTING -> "Waking up your world"
                                        ServerPhase.STOPPING -> "Saving everything safely"
                                        ServerPhase.ERROR -> "A little attention needed"
                                        ServerPhase.STOPPED -> "Ready when you are"
                                    },
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                if (isRunning) {
                                    Text(
                                        "${snapshot.onlinePlayers}/${config.maxPlayers} players" +
                                            (snapshot.currentTps?.let { "  •  %.1f TPS".format(it) } ?: ""),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                        when {
                            isStopping -> OutlinedButton(
                                onClick = {},
                                enabled = false,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Text("Stopping server…")
                            }

                            isRunning -> Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onStop,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                ) {
                                    Icon(Icons.Rounded.Stop, contentDescription = null)
                                    Spacer(Modifier.width(7.dp))
                                    Text("Stop")
                                }
                                OutlinedButton(
                                    onClick = onRestart,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                ) {
                                    Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                                    Spacer(Modifier.width(7.dp))
                                    Text("Restart")
                                }
                            }

                            canStop -> OutlinedButton(
                                onClick = onStop,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            ) {
                                Icon(Icons.Rounded.Stop, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Cancel start")
                            }

                            else -> Button(
                                onClick = onStart,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    contentColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Start server")
                            }
                        }
                    }
                }
            }
        }

        if (snapshot.phase == ServerPhase.ERROR && snapshot.errorMessage != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Server error", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(snapshot.errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        IconButton(onClick = onOpenConsole) {
                            Icon(Icons.Rounded.ChevronRight, contentDescription = "Open console", tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                }
            }
        }

        item {
            SectionHeader(title = "Quick actions")
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    QuickAction(
                        title = if (isRunning) "Copy IP" else "Server IP",
                        subtitle = serverAddress.substringBefore(':').take(18),
                        icon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                        onClick = { copyToClipboard(serverAddress) },
                        modifier = Modifier.weight(1f)
                    )
                    QuickAction(
                        title = "Console",
                        subtitle = "Log & commands",
                        icon = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
                        onClick = onOpenConsole,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    QuickAction(
                        title = "Catalog",
                        subtitle = "Download cores",
                        icon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                        onClick = onOpenCatalog,
                        modifier = Modifier.weight(1f)
                    )
                    QuickAction(
                        title = "Settings",
                        subtitle = "Java & options",
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        onClick = onOpenSettings,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            SectionHeader(title = "At a glance")
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricCard(
                        label = "Players",
                        value = "${snapshot.onlinePlayers}/${config.maxPlayers}",
                        icon = { Icon(Icons.Rounded.People, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = "Uptime",
                        value = uptime,
                        icon = { Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    MetricCard(
                        label = "Server RAM",
                        value = snapshot.memoryUsedMb?.let { "$it MB" } ?: "—",
                        icon = { Icon(Icons.Rounded.Memory, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = "CPU load",
                        value = snapshot.cpuPercent?.let { "%.0f%%".format(it) } ?: "—",
                        icon = { Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            SectionHeader(title = "Server profile")
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(
                    modifier = Modifier.padding(17.dp),
                    verticalArrangement = Arrangement.spacedBy(13.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(42.dp),
                            shape = RoundedCornerShape(13.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Public, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(config.motd, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                            Text(
                                "Port ${config.port}  •  ${config.levelName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    InfoRow(
                        label = "Address",
                        value = serverAddress,
                        modifier = Modifier.clickable { copyToClipboard(serverAddress) }
                    )
                    InfoRow("Core", config.coreName ?: "Not selected")
                    InfoRow(
                        "Authentication",
                        if (config.authMode == ServerAuthMode.ONLINE) "Online accounts" else "Offline LAN mode",
                        valueColor = if (config.authMode == ServerAuthMode.ONLINE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                    InfoRow("Memory limit", "${config.memoryMb} MB")
                    InfoRow("View distance", "${config.viewDistance} chunks")
                    InfoRow("Performance", config.performanceMode.name.lowercase().replaceFirstChar { it.uppercase() })
                    InfoRow(
                        "CPU wake lock",
                        if (config.keepAwake) "Enabled" else "Off",
                        valueColor = if (config.keepAwake) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (config.authMode == ServerAuthMode.OFFLINE_LAN) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "Offline LAN mode is on — keep this network trusted.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        if (!setupComplete) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSettings),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.62f))
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                        Spacer(Modifier.width(13.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                if (config.isConfigured) "Finish setup" else "Add a server core",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                if (config.isConfigured) "Accept the EULA to start."
                                else "Download one in Catalog.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.78f)
                            )
                        }
                        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickAction(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.size(30.dp),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) { icon() }
            }
            Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private fun formatUptime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000).coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%dh %02dm".format(hours, minutes)
    } else {
        "%02dm %02ds".format(minutes, seconds)
    }
}
