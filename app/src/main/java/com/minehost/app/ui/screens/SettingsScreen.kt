package com.minehost.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.minehost.app.BuildConfig
import com.minehost.app.data.JavaRuntimeCheck
import com.minehost.app.data.JavaRuntimeChecker
import com.minehost.app.data.JavaRuntimeInstaller
import com.minehost.app.data.JavaRuntimeStatus
import com.minehost.app.data.CgnatState
import com.minehost.app.data.PerformanceMode
import com.minehost.app.data.PlayitTunnelState
import com.minehost.app.data.RouterCheckResult
import com.minehost.app.data.RouterNetworkType
import com.minehost.app.data.ServerAuthMode
import com.minehost.app.data.ServerConfig
import com.minehost.app.data.TunnelState
import com.minehost.app.data.TunnelStatus
import com.minehost.app.ui.components.SectionHeader
import com.minehost.app.util.NetworkUtils
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    config: ServerConfig,
    onSave: (ServerConfig) -> Unit,
    onImportCore: (Uri) -> Unit,
    onExportWorld: (Uri) -> Unit,
    onImportWorld: (Uri) -> Unit,
    routerCheck: RouterCheckResult?,
    routerChecking: Boolean,
    routerMappingBusy: Boolean,
    onCheckAndMap: (Int) -> Unit,
    onRequestRouterMapping: () -> Unit,
    onRemoveRouterMapping: () -> Unit,
    profiles: List<com.minehost.app.data.ServerProfile>,
    activeProfileId: String?,
    onSelectProfile: (String) -> Unit,
    onCreateProfile: (String) -> Unit,
    onDuplicateProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    tunnelState: TunnelState,
    onStartTunnel: () -> Unit,
    onStopTunnel: () -> Unit,
    playitTunnelState: PlayitTunnelState,
    playitConfigured: Boolean,
    playitClaimBusy: Boolean,
    onStartPlayitClaim: () -> Unit,
    onStartPlayitTunnel: () -> Unit,
    onStopPlayitTunnel: () -> Unit,
    onUnlinkPlayit: () -> Unit,
    autoOpenTunnel: Boolean,
    onSetAutoOpenTunnel: (Boolean) -> Unit,
    onImportResourcePack: (Uri) -> Unit,
    onImportModpack: (Uri) -> Unit,
    onImportPlugin: (Uri) -> Unit,
    onRemovePlugin: (String) -> Unit,
    serverRunning: Boolean,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    val copyText: (String, String) -> Unit = { value, message ->
        clipboardManager?.setPrimaryClip(android.content.ClipData.newPlainText("MineHost", value))
        onMessage(message)
    }
    // Interface enumeration is blocking I/O — resolve it off the main thread.
    var lanEndpoint by remember(config.port) { mutableStateOf("phone-ip:${config.port}") }
    androidx.compose.runtime.LaunchedEffect(config.port) {
        val ip = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            NetworkUtils.localIpv4Address()
        } ?: "phone-ip"
        lanEndpoint = "$ip:${config.port}"
    }
    val localEndpoint = "127.0.0.1:${config.port}"
    // The draft is seeded per profile. While this screen is open, external
    // saves (core import, world restore, pack imports) must NOT wipe what the
    // user already typed: fields the user left untouched follow the saved
    // config, edited fields win. A profile switch starts from a clean draft.
    var baseline by remember(config.profileId) { mutableStateOf(config) }
    var draft by remember(config.profileId) { mutableStateOf(config) }
    androidx.compose.runtime.LaunchedEffect(config) {
        if (config == baseline) return@LaunchedEffect
        val old = baseline
        baseline = config
        // Base is the draft (keeps every edit); only fields the user never
        // touched are refreshed from the newly saved config.
        draft = draft.copy(
            corePath = if (draft.corePath == old.corePath) config.corePath else draft.corePath,
            coreName = if (draft.coreName == old.coreName) config.coreName else draft.coreName,
            resourcePackPath = if (draft.resourcePackPath == old.resourcePackPath) config.resourcePackPath else draft.resourcePackPath,
            modpackPath = if (draft.modpackPath == old.modpackPath) config.modpackPath else draft.modpackPath,
            // Plugins are added and removed outside this screen, so they must
            // follow the saved config or a later "Save changes" would drop the
            // plugin the user just imported.
            pluginPaths = if (draft.pluginPaths == old.pluginPaths) config.pluginPaths else draft.pluginPaths
        )
    }
    var javaCheck by remember { mutableStateOf<JavaRuntimeCheck?>(null) }
    var checkingJava by remember { mutableStateOf(false) }
    var installingJava by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf<Float?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    var showJavaInstallConfirmation by remember { mutableStateOf(false) }
    var showAdvancedProperties by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var newProfileName by remember { mutableStateOf("") }
    val javaChecker = remember { JavaRuntimeChecker() }
    val javaInstaller = remember { JavaRuntimeInstaller(context) }
    val scope = rememberCoroutineScope()
    val corePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImportCore) }
    val resourcePackPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImportResourcePack) }
    val modpackPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImportModpack) }
    val pluginPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onImportPlugin) }
    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(onExportWorld) }
    val restorePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { pendingRestoreUri = it } }
    val tailscaleInstalled = remember(context) { isTailscaleInstalled(context) }
    val openTailscale: () -> Unit = {
        if (!openTailscaleApp(context)) openExternalUrl(context, TAILSCALE_DOWNLOAD_URL)
    }
    val publicStatus = when {
        routerChecking -> "Checking your network…"
        routerMappingBusy -> "Mapping the port…"
        routerCheck == null -> "Not checked yet"
        routerCheck?.mappingActive == true -> "Mapped on your router — share the address below"
        routerCheck?.cgnatState == CgnatState.LIKELY -> "Carrier NAT detected — see the options below"
        routerCheck?.networkType != RouterNetworkType.WIFI -> "Connect to Wi-Fi to map a port automatically"
        routerCheck?.hasInternet == false -> "No active internet connection"
        else -> "Not reachable from the internet yet"
    }
    val publicStatusColor = when {
        routerCheck?.mappingActive == true -> MaterialTheme.colorScheme.primary
        routerCheck?.cgnatState == CgnatState.LIKELY -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    LazyColumn(
        modifier = modifier.imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 42.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f))
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(Modifier.width(11.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("Quick start", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            "1. Download a core in Catalog\n2. Test Java and accept the EULA\n3. Save, then press Start",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.84f)
                        )
                    }
                }
            }
        }

        item {
            SectionHeader(title = "Servers")
        }
        item {
            SettingsCard {
                profiles.forEach { profile ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = profile.id == activeProfileId,
                            onClick = { onSelectProfile(profile.id) }
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                buildString {
                                    append("port ${profile.config.port}")
                                    if (profile.config.isConfigured) append(" · core ready")
                                    if (profile.id == activeProfileId) append(" · editing this one")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = { onDuplicateProfile(profile.id) }) { Text("Copy") }
                        TextButton(onClick = { onDeleteProfile(profile.id) }) { Text("Delete") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newProfileName,
                        onValueChange = { newProfileName = it },
                        label = { Text("New server name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            onCreateProfile(newProfileName)
                            newProfileName = ""
                        },
                        enabled = newProfileName.isNotBlank(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Add") }
                }
            }
        }

        item {
            SectionHeader(title = "Server identity")
        }
        item {
            SettingsCard {
                OutlinedTextField(
                    value = draft.serverName,
                    onValueChange = { draft = draft.copy(serverName = it) },
                    label = { Text("Server name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = draft.motd,
                    onValueChange = { draft = draft.copy(motd = it) },
                    label = { Text("MOTD") },
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            SectionHeader(title = "World & network")
        }
        item {
            SettingsCard {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    EditableNumberField(
                        label = "Port",
                        value = draft.port,
                        maxDigits = 5,
                        coerce = { it.coerceIn(1, 65535) },
                        modifier = Modifier.weight(1f),
                        onValueChange = { draft = draft.copy(port = it) }
                    )
                    EditableNumberField(
                        label = "Max players",
                        value = draft.maxPlayers,
                        maxDigits = 3,
                        coerce = { it.coerceIn(1, 500) },
                        modifier = Modifier.weight(1f),
                        onValueChange = { draft = draft.copy(maxPlayers = it) }
                    )
                }
                Spacer(Modifier.height(13.dp))
                OutlinedTextField(
                    value = draft.levelName,
                    onValueChange = { draft = draft.copy(levelName = it) },
                    label = { Text("Level name") },
                    supportingText = { Text("The folder created inside MineHost's private workspace") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            SectionHeader(title = "Performance")
        }
        item {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingIcon(Icons.Rounded.Memory)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Memory limit", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Heap size for the server process",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        formatMemory(draft.memoryMb),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = (draft.memoryMb / 1024f).coerceIn(0.5f, 16f),
                    onValueChange = { value ->
                        draft = draft.copy(memoryMb = ((value * 2).roundToInt() * 512).coerceIn(512, 16384))
                    },
                    valueRange = 0.5f..16f,
                    steps = 30,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    listOf(1, 2, 4, 8, 16).forEach { gigabytes ->
                        FilterChip(
                            selected = draft.memoryMb == gigabytes * 1024,
                            onClick = { draft = draft.copy(memoryMb = gigabytes * 1024) },
                            label = { Text("${gigabytes} GB") }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Performance profile", style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    listOf(
                        PerformanceMode.BATSAVER to "Battery saver",
                        PerformanceMode.BALANCED to "Balanced",
                        PerformanceMode.PERFORMANCE to "Performance"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = draft.performanceMode == mode,
                            onClick = { draft = draft.copy(performanceMode = mode) },
                            label = { Text(label) }
                        )
                    }
                }
                Text(
                    when (draft.performanceMode) {
                        PerformanceMode.BATSAVER -> "Lower memory use and battery impact."
                        PerformanceMode.BALANCED -> "Recommended for most phones."
                        PerformanceMode.PERFORMANCE -> "Maximum server throughput; uses more battery and heat."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionHeader(title = "Server core")
        }
        item {
            SettingsCard {
                if (draft.isConfigured) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            modifier = Modifier.size(43.dp),
                            shape = RoundedCornerShape(13.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Code, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                draft.coreName ?: "Imported server core",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "Ready to launch",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(Modifier.height(13.dp))
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SettingIcon(Icons.Rounded.FolderOpen)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("No core selected", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Import a JAR or native host executable.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(13.dp))
                }
                OutlinedButton(
                    onClick = { corePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (draft.isConfigured) "Replace server core" else "Import server core")
                }
            }
        }

        item {
            SectionHeader(title = "Java runtime")
        }
        item {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingIcon(Icons.Rounded.Code)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Required for Paper and other .jar servers", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Test the executable before starting a server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(13.dp))
                OutlinedTextField(
                    value = draft.javaExecutable,
                    onValueChange = {
                        draft = draft.copy(javaExecutable = it)
                        javaCheck = null
                        installError = null
                    },
                    label = { Text("Java executable") },
                    supportingText = { Text("Use java or an absolute path to an Android-compatible executable") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                javaCheck?.let { result ->
                    Spacer(Modifier.height(10.dp))
                    val container = when (result.status) {
                        JavaRuntimeStatus.WORKING -> MaterialTheme.colorScheme.primaryContainer
                        JavaRuntimeStatus.UNAVAILABLE -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.errorContainer
                    }
                    val content = when (result.status) {
                        JavaRuntimeStatus.WORKING -> MaterialTheme.colorScheme.onPrimaryContainer
                        JavaRuntimeStatus.UNAVAILABLE -> MaterialTheme.colorScheme.onSecondaryContainer
                        else -> MaterialTheme.colorScheme.onErrorContainer
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = container
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(result.message, style = MaterialTheme.typography.bodyMedium, color = content)
                            result.version?.let { version ->
                                Text(version, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.78f))
                            }
                        }
                    }
                }
                if (BuildConfig.LOCAL_RUNTIME_BUILD && installingJava) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { installProgress ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        if (installProgress == null) "Preparing Android Java…" else "Installing Android Java… ${((installProgress ?: 0f) * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (BuildConfig.LOCAL_RUNTIME_BUILD) {
                    installError?.let { error ->
                        Spacer(Modifier.height(8.dp))
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (!BuildConfig.LOCAL_RUNTIME_BUILD) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                "One-tap Java install lives in the MineHost local build",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                "Android does not let this build run programs it downloaded itself, so it cannot " +
                                    "install Java. The MineHost local APK has an \"Install Android Java\" button that " +
                                    "fetches a checksum-verified OpenJDK 21 from GitHub, tests it, and selects it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.82f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            checkingJava = true
                            scope.launch {
                                try {
                                    val result = javaChecker.check(draft.javaExecutable)
                                    javaCheck = result
                                    onMessage(result.message)
                                } finally {
                                    checkingJava = false
                                }
                            }
                        },
                        enabled = !checkingJava && !installingJava,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text(if (checkingJava) "Checking…" else "Test Java")
                    }
                    if (BuildConfig.LOCAL_RUNTIME_BUILD) {
                        OutlinedButton(
                            onClick = { showJavaInstallConfirmation = true },
                            enabled = !installingJava,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(if (installingJava) "Installing…" else "Install Android Java")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { openExternalUrl(context, JAVA_SETUP_URL) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Open Java setup guide")
                }
            }
        }

        item {
            SectionHeader(title = "Launch options")
        }
        item {
            SettingsCard {
                OutlinedTextField(
                    value = draft.launchArgs,
                    onValueChange = { draft = draft.copy(launchArgs = it) },
                    label = { Text("Launch arguments") },
                    supportingText = { Text("MineHost adds -Xms/-Xmx automatically for JAR cores") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        item {
            SectionHeader(title = "Advanced server properties")
        }
        item {
            SettingsCard {
                Text("Generated server.properties", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { showAdvancedProperties = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Open advanced properties")
                }
            }
        }

        item {
            SectionHeader(title = "World data and server packs")
        }
        item {
            SettingsCard {
                Text("Worlds and server packs", style = MaterialTheme.typography.titleMedium)
                Text(
                    "ZIP export and restore. Stop the server first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { exportPicker.launch("minehost-world-backup.zip") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Export backup") }
                    OutlinedButton(
                        onClick = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Restore / import") }
                }
            }
        }

        item {
            SectionHeader(title = "Public access")
        }
        item {
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SettingIcon(Icons.Rounded.Public)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Let friends join online", style = MaterialTheme.typography.titleMedium)
                        Text(
                            publicStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = publicStatusColor
                        )
                    }
                }
                if (routerChecking) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                routerCheck?.let { result ->
                    Spacer(Modifier.height(12.dp))
                    RouterDiagnosticRow("Network", result.networkType.label)
                    RouterDiagnosticRow(
                        "Internet",
                        when {
                            result.internetValidated -> "Validated"
                            result.hasInternet -> "Connected"
                            else -> "Unavailable"
                        },
                        positive = result.hasInternet,
                        warning = !result.hasInternet
                    )
                    RouterDiagnosticRow("Local IPv4", result.localIpv4 ?: "Unavailable")
                    RouterDiagnosticRow("Router gateway", result.gatewayIpv4 ?: "Unavailable")
                    RouterDiagnosticRow(
                        "Public IPv4",
                        result.publicIpv4 ?: "Unavailable",
                        positive = result.publicIpv4 != null
                    )
                    RouterDiagnosticRow("Public IPv6", result.publicIpv6 ?: "Unavailable", positive = result.publicIpv6 != null)
                    RouterDiagnosticRow("Router WAN IPv4", result.routerExternalIpv4 ?: "Not reported")
                    RouterDiagnosticRow(
                        "CGNAT",
                        result.cgnatState.label,
                        positive = result.cgnatState == CgnatState.UNLIKELY,
                        warning = result.cgnatState == CgnatState.LIKELY
                    )
                    RouterDiagnosticRow(
                        "Port ${result.serverPort}",
                        when (result.portReachable) {
                            true -> "Listening locally"
                            false -> "Not reachable"
                            null -> "Start server to test"
                        },
                        positive = result.portReachable == true,
                        warning = result.portReachable == false
                    )
                    RouterDiagnosticRow("Port mapping", result.mappingMethod ?: "Not detected", positive = result.mappingMethod != null)
                    result.errorMessage?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    result.mappingMessage?.let { message ->
                        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val publicEndpoint = result.publicEndpoint
                    if (!publicEndpoint.isNullOrBlank() && result.cgnatState != CgnatState.LIKELY) {
                        RouterEndpointCard(
                            label = "Public IPv4 candidate",
                            endpoint = publicEndpoint,
                            onCopy = {
                                copyText(publicEndpoint, "Public server address copied")
                            }
                        )
                    }
                    result.ipv6Endpoint?.let { ipv6Endpoint ->
                        RouterEndpointCard(
                            label = "Public IPv6 candidate",
                            endpoint = ipv6Endpoint,
                            onCopy = {
                                copyText(ipv6Endpoint, "Public IPv6 address copied")
                            }
                        )
                    }
                    if (result.cgnatState == CgnatState.LIKELY && result.ipv6Endpoint == null) {
                        Text(
                            "This connection is likely behind CGNAT. Direct IPv4 port forwarding will not reach the phone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onCheckAndMap(draft.port) },
                    enabled = !routerChecking && !routerMappingBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        when {
                            routerChecking -> "Checking your network…"
                            routerMappingBusy -> "Mapping the port…"
                            routerCheck?.mappingActive == true -> "Re-check and re-map"
                            else -> "Go public"
                        }
                    )
                }
                if (routerCheck?.mappingActive == true) {
                    TextButton(
                        onClick = onRemoveRouterMapping,
                        enabled = !routerMappingBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Remove router mapping") }
                }
            }
        }

        item {
            SectionHeader(title = "On the same Wi-Fi")
        }
        item {
            SettingsCard {
                Text("LAN address", style = MaterialTheme.typography.titleMedium)
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Friends on this network", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(lanEndpoint, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text("This device only: $localEndpoint", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f))
                    }
                }
                Spacer(Modifier.height(9.dp))
                OutlinedButton(
                    onClick = { copyText(lanEndpoint, "LAN address copied") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Copy LAN address") }
            }
        }

        item {
            SectionHeader(title = "Manual port forwarding")
        }
        item {
            SettingsCard {
                Text("Router values", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                CopyableValueRow("Protocol", "TCP", onCopy = { copyText("TCP", "Copied protocol") })
                CopyableValueRow("External port", draft.port.toString(), onCopy = { copyText(draft.port.toString(), "Copied external port") })
                CopyableValueRow(
                    "Internal address",
                    routerCheck?.localIpv4 ?: "Press Go public first",
                    onCopy = {
                        routerCheck?.localIpv4?.let { copyText(it, "Copied internal address") }
                    }
                )
                CopyableValueRow("Internal port", draft.port.toString(), onCopy = { copyText(draft.port.toString(), "Copied internal port") })
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = {
                            val address = routerCheck?.localIpv4 ?: "unknown"
                            copyText(
                                "Minecraft Java port forwarding\nProtocol: TCP\nExternal port: ${draft.port}\nInternal address: $address\nInternal port: ${draft.port}",
                                "Forwarding settings copied"
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Copy settings") }
                    OutlinedButton(
                        onClick = onRequestRouterMapping,
                        enabled = routerCheck?.canRequestMapping == true && !routerMappingBusy,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Try automatic") }
                }
                routerCheck?.gatewayIpv4?.let { gateway ->
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = { openExternalUrl(context, "http://$gateway") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Open router settings") }
                }
            }
        }

        routerCheck?.tailnetIpv4?.let { tailnetAddress ->
            item {
                SectionHeader(title = "Tailscale tailnet")
            }
            item {
                SettingsCard {
                    Text("Share over your tailnet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Friends join your Tailscale network, then connect with this address. No port forwarding, and it works behind carrier NAT.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("Tailnet address", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("$tailnetAddress:${draft.port}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { copyText("$tailnetAddress:${draft.port}", "Tailnet address copied") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text("Copy address") }
                        OutlinedButton(
                            onClick = openTailscale,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp)
                        ) { Text(if (tailscaleInstalled) "Open Tailscale" else "Get Tailscale") }
                    }
                }
            }
        }

        if (routerCheck?.cgnatState == CgnatState.LIKELY) {
            item {
                SectionHeader(title = "Carrier NAT")
            }
            item {
                SettingsCard {
                    Text(
                        "Your carrier shares one public IPv4 address between many customers, so port forwarding cannot reach this phone. Pick a path that does not need one:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    PublicOptionRow(
                        title = "Free tunnel",
                        detail = "Open a public relay tunnel below and share the address it gives you. No port forwarding, works on any network."
                    )
                    PublicOptionRow(
                        title = "Use IPv6",
                        detail = "Works when your provider hands out a public IPv6 address. Friends join with the bracketed address shown above."
                    )
                    PublicOptionRow(
                        title = "Share a tailnet",
                        detail = "Everyone installs Tailscale, joins your tailnet, and connects to your 100.x address. Nothing to forward."
                    )
                }
            }
        }

        item {
            SectionHeader(title = "Free public tunnel")
        }
        item {
            TunnelCard(
                tunnelState = tunnelState,
                serverRunning = serverRunning,
                serverPort = draft.port,
                onStartTunnel = onStartTunnel,
                onStopTunnel = onStopTunnel,
                onCopy = { value -> copyText(value, "Tunnel address copied") }
            )
        }
        item {
            PlayitCard(
                state = playitTunnelState,
                configured = playitConfigured,
                claimBusy = playitClaimBusy,
                serverRunning = serverRunning,
                onLinkAgent = onStartPlayitClaim,
                onStartTunnel = onStartPlayitTunnel,
                onStopTunnel = onStopPlayitTunnel,
                onUnlink = onUnlinkPlayit,
                autoOpen = autoOpenTunnel,
                onSetAutoOpen = onSetAutoOpenTunnel,
                onCopy = { value -> copyText(value, "playit.gg address copied") }
            )
        }

        item {
            SectionHeader(title = "Mods and packs")
        }
        item {
            PacksCard(
                config = draft,
                onPickResourcePack = {
                    runCatching { resourcePackPicker.launch(arrayOf("application/zip", "application/octet-stream")) }
                        .onFailure { onMessage("No file picker is available") }
                },
                onPickModpack = {
                    runCatching { modpackPicker.launch(arrayOf("application/zip", "application/octet-stream")) }
                        .onFailure { onMessage("No file picker is available") }
                },
                onClearResourcePack = { draft = draft.copy(resourcePackPath = null) },
                onClearModpack = { draft = draft.copy(modpackPath = null) },
                onPickPlugin = {
                    runCatching { pluginPicker.launch(arrayOf("application/java-archive", "application/zip", "application/octet-stream")) }
                        .onFailure { onMessage("No file picker is available") }
                },
                onRemovePlugin = onRemovePlugin,
                serverRunning = serverRunning
            )
        }

        item {
            SectionHeader(title = "Hosting options")
        }
        item {
            SettingsCard {
                Text("Authentication", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Online verifies Java accounts. Offline LAN skips verification.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.authMode == ServerAuthMode.ONLINE,
                        onClick = { draft = draft.copy(authMode = ServerAuthMode.ONLINE) },
                        label = { Text("Online") }
                    )
                    FilterChip(
                        selected = draft.authMode == ServerAuthMode.OFFLINE_LAN,
                        onClick = { draft = draft.copy(authMode = ServerAuthMode.OFFLINE_LAN) },
                        label = { Text("Offline LAN") }
                    )
                }
                if (draft.authMode == ServerAuthMode.OFFLINE_LAN) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Offline mode disables account checks — trusted networks only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                SettingDivider()
                SwitchPreference(
                    icon = { SettingIcon(Icons.Rounded.Security) },
                    title = "Accept Minecraft EULA",
                    subtitle = "Required before a Minecraft server core can start",
                    checked = draft.acceptEula,
                    onCheckedChange = { draft = draft.copy(acceptEula = it) }
                )
                SettingDivider()
                SwitchPreference(
                    icon = { SettingIcon(Icons.Rounded.Bolt) },
                    title = "Keep CPU awake",
                    subtitle = "Uses a partial wake lock while the server runs",
                    checked = draft.keepAwake,
                    onCheckedChange = { draft = draft.copy(keepAwake = it) }
                )
                SettingDivider()
                SwitchPreference(
                    icon = { SettingIcon(Icons.Rounded.RestartAlt) },
                    title = "Start after reboot",
                    subtitle = "Only starts when a core and EULA are configured",
                    checked = draft.startOnBoot,
                    onCheckedChange = { draft = draft.copy(startOnBoot = it) }
                )
                SettingDivider()
                SwitchPreference(
                    icon = { SettingIcon(Icons.Rounded.Security) },
                    title = "Allow flight",
                    subtitle = "Enable creative-style flight for players",
                    checked = draft.allowFlight,
                    onCheckedChange = { draft = draft.copy(allowFlight = it) }
                )
                SettingDivider()
                SwitchPreference(
                    icon = { SettingIcon(Icons.Rounded.BatteryAlert) },
                    title = "Enable Query",
                    subtitle = "Allow status queries from compatible tools",
                    checked = draft.enableQuery,
                    onCheckedChange = { draft = draft.copy(enableQuery = it) }
                )
                SettingDivider()
                SwitchPreference(
                    icon = { SettingIcon(Icons.Rounded.Code) },
                    title = "Enable RCON",
                    subtitle = "Remote console with a private password",
                    checked = draft.enableRcon,
                    onCheckedChange = { draft = draft.copy(enableRcon = it) }
                )
                if (draft.enableRcon) {
                    OutlinedTextField(
                        value = draft.rconPassword,
                        onValueChange = { draft = draft.copy(rconPassword = it) },
                        label = { Text("RCON password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.58f))
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(11.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Battery optimization", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(
                            "Exempt MineHost if Android sleeps the server.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                        )
                        OutlinedButton(
                            onClick = {
                                openBatteryOptimizationSettings(context)
                                onMessage("Battery settings opened")
                            },
                            shape = RoundedCornerShape(13.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onTertiaryContainer)
                        ) {
                            Text("Open battery settings")
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = { onSave(draft) },
                modifier = Modifier.fillMaxWidth().height(53.dp),
                shape = RoundedCornerShape(17.dp)
            ) {
                Text("Save changes", fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showAdvancedProperties) {
        AdvancedPropertiesSheet(
            config = draft,
            onDismiss = { showAdvancedProperties = false },
            onApply = {
                draft = it
                showAdvancedProperties = false
            }
        )
    }

    pendingRestoreUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestoreUri = null },
            title = { Text("Replace server workspace?") },
            text = {
                Text("Restoring replaces the current world, config, plugins and mods. The previous workspace is kept as a rollback copy.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingRestoreUri = null
                        onImportWorld(uri)
                    }
                ) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreUri = null }) { Text("Cancel") }
            }
        )
    }

    if (BuildConfig.LOCAL_RUNTIME_BUILD && showJavaInstallConfirmation) {
        AlertDialog(
            onDismissRequest = { showJavaInstallConfirmation = false },
            title = { Text("Install Android Java?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Downloads an ARM64 Android OpenJDK 21 runtime (~197 MB) from a pinned GitHub release.")
                    Text("About 700 MB of free space is needed while installing. MineHost verifies the archive and self-tests it before selecting it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("This Local Runtime build is for personal sideloading and targets a legacy Android level so the runtime may execute.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showJavaInstallConfirmation = false
                        installingJava = true
                        installProgress = 0f
                        installError = null
                        scope.launch {
                            try {
                                val result = javaInstaller.install { progress ->
                                    installProgress = progress
                                }
                                draft = draft.copy(javaExecutable = result.executablePath)
                                javaCheck = JavaRuntimeCheck(
                                    status = JavaRuntimeStatus.WORKING,
                                    message = "Android Java is installed and selected.",
                                    version = result.versionOutput
                                )
                                onMessage("Android Java runtime installed")
                            } catch (error: Exception) {
                                installError = error.message ?: "Android Java installation failed."
                            } finally {
                                installingJava = false
                            }
                        }
                    }
                ) {
                    Text("Download and install")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJavaInstallConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedPropertiesSheet(
    config: ServerConfig,
    onDismiss: () -> Unit,
    onApply: (ServerConfig) -> Unit
) {
    var value by remember(config) { mutableStateOf(config) }

    // Horizontal padding > 16.dp keeps content clear of the sheet's rounded
    // window edges, which visually clip text on narrow screens otherwise.
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Advanced server properties", style = MaterialTheme.typography.headlineSmall)
                    Text("Applied when you save Settings and restart the server.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }

            Text("Distance and performance", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NumberSetting(
                    label = "View distance",
                    value = value.viewDistance,
                    range = "2–32 chunks",
                    modifier = Modifier.weight(1f),
                    onValueChange = { value = value.copy(viewDistance = it) }
                )
                NumberSetting(
                    label = "Simulation",
                    value = value.simulationDistance,
                    range = "2–32 chunks",
                    modifier = Modifier.weight(1f),
                    onValueChange = { value = value.copy(simulationDistance = it) }
                )
            }
            NumberSetting(
                label = "Network compression threshold",
                value = value.networkCompressionThreshold,
                range = "-1 disables compression; 0–65536 bytes",
                onValueChange = { value = value.copy(networkCompressionThreshold = it) }
            )

            Text("World and gameplay", style = MaterialTheme.typography.titleMedium)
            Text("Difficulty", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("peaceful", "easy", "normal", "hard").forEach { difficulty ->
                    FilterChip(
                        selected = value.difficulty == difficulty,
                        onClick = { value = value.copy(difficulty = difficulty) },
                        label = { Text(difficulty.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            Text("Default gamemode", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("survival", "creative", "adventure", "spectator").forEach { gamemode ->
                    FilterChip(
                        selected = value.gamemode == gamemode,
                        onClick = { value = value.copy(gamemode = gamemode) },
                        label = { Text(gamemode.replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            OutlinedTextField(
                value = value.levelType,
                onValueChange = { value = value.copy(levelType = it) },
                label = { Text("Level type") },
                supportingText = { Text("For example: minecraft:normal") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = value.levelSeed,
                onValueChange = { value = value.copy(levelSeed = it) },
                label = { Text("Level seed (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                NumberSetting(
                    label = "Spawn protection",
                    value = value.spawnProtection,
                    range = "Radius in blocks",
                    modifier = Modifier.weight(1f),
                    onValueChange = { value = value.copy(spawnProtection = it) }
                )
                NumberSetting(
                    label = "Idle timeout",
                    value = value.playerIdleTimeout,
                    range = "Minutes; 0 disables",
                    modifier = Modifier.weight(1f),
                    onValueChange = { value = value.copy(playerIdleTimeout = it) }
                )
            }
            NumberSetting(
                label = "Pause when empty",
                value = value.pauseWhenEmptySeconds,
                range = "Seconds; 0 disables pausing",
                onValueChange = { value = value.copy(pauseWhenEmptySeconds = it) }
            )

            Text("Resource pack", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = value.resourcePackUrl,
                onValueChange = { value = value.copy(resourcePackUrl = it) },
                label = { Text("Resource pack URL") },
                supportingText = { Text("Use HTTPS for a pack hosted outside MineHost") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = value.resourcePackSha1,
                onValueChange = { value = value.copy(resourcePackSha1 = it) },
                label = { Text("Resource pack SHA-1 (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = value.resourcePackPrompt,
                onValueChange = { value = value.copy(resourcePackPrompt = it) },
                label = { Text("Resource pack prompt") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            SwitchPreference(
                icon = { SettingIcon(Icons.Rounded.Security) },
                title = "Require resource pack",
                subtitle = "Clients must accept the pack before joining",
                checked = value.requireResourcePack,
                onCheckedChange = { value = value.copy(requireResourcePack = it) }
            )

            Text("Server behavior", style = MaterialTheme.typography.titleMedium)
            SwitchPreference(
                icon = { SettingIcon(Icons.Rounded.Code) },
                title = "Enable command blocks",
                subtitle = "Allow commands from the world",
                checked = value.enableCommandBlock,
                onCheckedChange = { value = value.copy(enableCommandBlock = it) }
            )
            SettingDivider()
            SwitchPreference(
                icon = { SettingIcon(Icons.Rounded.Bolt) },
                title = "Enable server status",
                subtitle = "Allow compatible status checks",
                checked = value.enableStatus,
                onCheckedChange = { value = value.copy(enableStatus = it) }
            )
            SettingDivider()
            SwitchPreference(
                icon = { SettingIcon(Icons.Rounded.Security) },
                title = "Hide player list",
                subtitle = "Hide online players from server status",
                checked = value.hideOnlinePlayers,
                onCheckedChange = { value = value.copy(hideOnlinePlayers = it) }
            )
            SettingDivider()
            SwitchPreference(
                icon = { SettingIcon(Icons.Rounded.Memory) },
                title = "Synchronous chunk writes",
                subtitle = "Safer writes, potentially slower saves",
                checked = value.syncChunkWrites,
                onCheckedChange = { value = value.copy(syncChunkWrites = it) }
            )
            SettingDivider()
            SwitchPreference(
                icon = { SettingIcon(Icons.Rounded.Bolt) },
                title = "Native transport",
                subtitle = "Use the server's optimized network transport",
                checked = value.useNativeTransport,
                onCheckedChange = { value = value.copy(useNativeTransport = it) }
            )
            SettingDivider()
            SwitchPreference(
                icon = { SettingIcon(Icons.Rounded.Public) },
                title = "Allow Nether",
                subtitle = "Allow players to travel through the Nether",
                checked = value.allowNether,
                onCheckedChange = { value = value.copy(allowNether = it) }
            )
            SettingDivider()
            SwitchPreference(
                icon = { SettingIcon(Icons.Rounded.Public) },
                title = "Generate structures",
                subtitle = "Generate villages, strongholds, and other structures",
                checked = value.generateStructures,
                onCheckedChange = { value = value.copy(generateStructures = it) }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(15.dp)
                ) { Text("Cancel") }
                Button(
                    onClick = { onApply(value) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(15.dp)
                ) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun NumberSetting(
    label: String,
    value: Int,
    range: String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    // Local text state so the field can actually be cleared and negative
    // values (like -1 for "compression off") can be typed; a fully controlled
    // field snaps back on every keystroke that does not parse yet.
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filterIndexed { index, character ->
                character.isDigit() || (character == '-' && index == 0)
            }.take(7)
            text.toIntOrNull()?.let(onValueChange)
        },
        label = { Text(label) },
        supportingText = { Text(range) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier.fillMaxWidth()
    )
}

/**
 * Digits-only field with the same forgiving editing model as [NumberSetting]:
 * the box can be emptied mid-edit, and the value is clamped once it parses.
 */
@Composable
private fun EditableNumberField(
    label: String,
    value: Int,
    maxDigits: Int,
    coerce: (Int) -> Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input.filter { it.isDigit() }.take(maxDigits)
            text.toIntOrNull()?.let { onValueChange(coerce(it)) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
private fun TunnelCard(
    tunnelState: TunnelState,
    serverRunning: Boolean,
    serverPort: Int,
    onStartTunnel: () -> Unit,
    onStopTunnel: () -> Unit,
    onCopy: (String) -> Unit
) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingIcon(Icons.Rounded.Language)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Public relay tunnel", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (tunnelState.status) {
                        TunnelStatus.IDLE -> "Runs inside MineHost — nothing to install"
                        TunnelStatus.CONNECTING -> "Contacting the relay…"
                        TunnelStatus.ACTIVE -> "Online — share the address below"
                        TunnelStatus.RECONNECTING -> "Reconnecting…"
                        TunnelStatus.FAILED -> tunnelState.error ?: "Could not connect"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when (tunnelState.status) {
                        TunnelStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                        TunnelStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        tunnelState.publicEndpoint?.let { endpoint ->
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(start = 13.dp, top = 9.dp, end = 7.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Friends join with", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            endpoint,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { onCopy(endpoint) }) { Text("Copy") }
                }
            }
        }
        tunnelState.error?.let { error ->
            if (tunnelState.status == TunnelStatus.RECONNECTING) {
                Spacer(Modifier.height(6.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val canStart = serverRunning &&
                tunnelState.status.let { it == TunnelStatus.IDLE || it == TunnelStatus.FAILED }
            Button(
                onClick = onStartTunnel,
                enabled = canStart,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(
                    when {
                        tunnelState.isActive -> "Tunnel open"
                        !serverRunning -> "Start the server first"
                        else -> "Open tunnel"
                    }
                )
            }
            if (tunnelState.isActive) {
                OutlinedButton(
                    onClick = onStopTunnel,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("Close tunnel") }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Forwards TCP ${serverPort} to a free public relay. Latency is a little higher than a direct connection, and the port changes each time you open the tunnel.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PacksCard(
    config: ServerConfig,
    onPickResourcePack: () -> Unit,
    onPickModpack: () -> Unit,
    onClearResourcePack: () -> Unit,
    onClearModpack: () -> Unit,
    onPickPlugin: () -> Unit,
    onRemovePlugin: (String) -> Unit,
    serverRunning: Boolean
) {
    SettingsCard {
        Text("Modpacks and resource packs", style = MaterialTheme.typography.titleMedium)
        Text(
            "Imported files are installed into the server workspace the next time it starts.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        PackRow(
            title = "Resource pack",
            detail = if (config.resourcePackPath != null) {
                "Installed: " + (config.resourcePackPath.substringAfterLast('/') + " — players are offered it on join")
            } else {
                "A ZIP offered to every player when they join"
            },
            picked = config.resourcePackPath != null,
            onPick = onPickResourcePack,
            onClear = onClearResourcePack
        )
        Spacer(Modifier.height(8.dp))
        PackRow(
            title = "Mod / plugin pack",
            detail = if (config.modpackPath != null) {
                "Installed: " + config.modpackPath.substringAfterLast('/') + " — mods/ and plugins/ merge in on start"
            } else {
                "A ZIP with mods/ or plugins/ (Forge, Fabric, Paper) or loose server JARs"
            },
            picked = config.modpackPath != null,
            onPick = onPickModpack,
            onClear = onClearModpack
        )
        Spacer(Modifier.height(8.dp))
        SettingDivider()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Plugins", style = MaterialTheme.typography.bodyLarge)
                Text(
                    when {
                        config.pluginPaths.isNotEmpty() ->
                            "${config.pluginPaths.size} installed — copied into plugins/ on start"
                        serverRunning -> "Paper/Spigot .jar files, loaded on the next start"
                        else -> "Paper/Spigot .jar files, loaded when the server starts"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onPickPlugin) { Text("Add plugin") }
        }
        if (config.pluginPaths.isEmpty()) {
            Text(
                "Needs a Paper or Spigot core — vanilla and Fabric servers ignore plugins/.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            config.pluginPaths.forEach { path ->
                PluginRow(
                    path = path,
                    onRemove = { onRemovePlugin(path) }
                )
            }
            if (serverRunning) {
                Text(
                    "Restart the server to load the changes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PluginRow(path: String, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Text(
            path.substringAfterLast('/'),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

@Composable
private fun PackRow(
    title: String,
    detail: String,
    picked: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = if (picked) onClear else onPick) {
            Text(if (picked) "Remove" else "Import")
        }
    }
}

@Composable
private fun PlayitCard(
    state: PlayitTunnelState,
    configured: Boolean,
    claimBusy: Boolean,
    serverRunning: Boolean,
    onLinkAgent: () -> Unit,
    onStartTunnel: () -> Unit,
    onStopTunnel: () -> Unit,
    onUnlink: () -> Unit,
    autoOpen: Boolean,
    onSetAutoOpen: (Boolean) -> Unit,
    onCopy: (String) -> Unit
) {
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettingIcon(Icons.Rounded.Language)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("playit.gg network", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        !configured -> "Persistent public address, survives restarts — needs a one-time link"
                        state.status == TunnelStatus.IDLE -> "Linked. playit.gg shows the agent online only while this tunnel is open."
                        state.status == TunnelStatus.CONNECTING -> "Contacting the playit network…"
                        state.status == TunnelStatus.ACTIVE -> "Online — share the address below"
                        state.status == TunnelStatus.RECONNECTING -> "Reconnecting…"
                        else -> state.error ?: "Could not connect"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        configured && state.status == TunnelStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                        state.status == TunnelStatus.FAILED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
        state.publicEndpoint?.let { endpoint ->
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.padding(start = 13.dp, top = 9.dp, end = 7.dp, bottom = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Friends join with", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text(
                            endpoint,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { onCopy(endpoint) }) { Text("Copy") }
                }
            }
        }
        state.error?.let { error ->
            if (state.status == TunnelStatus.RECONNECTING) {
                Spacer(Modifier.height(6.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (!configured) {
            Button(
                onClick = onLinkAgent,
                enabled = !claimBusy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(if (claimBusy) "Waiting for you to confirm in the browser…" else "Link a playit.gg agent")
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Opens playit.gg in your browser. A free guest account is created automatically — add an email on their site to keep the same address forever.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                val canStart = serverRunning &&
                    state.status.let { it == TunnelStatus.IDLE || it == TunnelStatus.FAILED }
                Button(
                    onClick = onStartTunnel,
                    enabled = canStart,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(
                        when {
                            state.isActive -> "Tunnel open"
                            !serverRunning -> "Start the server first"
                            else -> "Open tunnel"
                        }
                    )
                }
                if (state.isActive) {
                    OutlinedButton(
                        onClick = onStopTunnel,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) { Text("Close tunnel") }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Open automatically", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Connects the tunnel as soon as the server is live",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = autoOpen, onCheckedChange = onSetAutoOpen)
            }
            Text(
                "Create (or assign) a Minecraft Java tunnel on playit.gg, then open it here. The agent reports the address it is given.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TextButton(onClick = onUnlink) { Text("Unlink agent") }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Bounces Minecraft traffic through the playit.gg relay network: persistent address, DDoS protection, no port forwarding. The Minecraft Java tier is free.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CopyableValueRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onCopy) { Text("Copy") }
    }
}

@Composable
private fun PublicOptionRow(title: String, detail: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Rounded.Info,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(9.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RouterEndpointCard(
    label: String,
    endpoint: String,
    onCopy: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 7.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.62f)
    ) {
        Row(
            modifier = Modifier.padding(start = 13.dp, top = 9.dp, end = 7.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(endpoint, style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = onCopy) { Text("Copy") }
        }
    }
}

@Composable
private fun RouterDiagnosticRow(
    label: String,
    value: String,
    positive: Boolean = false,
    warning: Boolean = false
) {
    val valueColor = when {
        positive -> MaterialTheme.colorScheme.primary
        warning -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            modifier = Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = valueColor
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.46f)),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SettingIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(
        modifier = Modifier.size(36.dp),
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(19.dp))
        }
    }
}

@Composable
private fun SwitchPreference(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon()
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingDivider() {
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    )
}

private const val JAVA_SETUP_URL = "https://wiki.termux.dev/wiki/Development_Environments#OpenJDK"
private const val TAILSCALE_PACKAGE = "com.tailscale.ipn"
private const val TAILSCALE_DOWNLOAD_URL = "https://tailscale.com/download/android"

/**
 * Tailscale ships as a separate VpnService app, so MineHost can only hand off
 * to it. Its CLI-only Funnel feature cannot run on Android at all.
 */
private fun isTailscaleInstalled(context: android.content.Context): Boolean =
    runCatching { context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE) }.getOrNull() != null

private fun openTailscaleApp(context: android.content.Context): Boolean {
    val intent = runCatching { context.packageManager.getLaunchIntentForPackage(TAILSCALE_PACKAGE) }
        .getOrNull() ?: return false
    return runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

private fun openExternalUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

private fun formatMemory(memoryMb: Int): String = if (memoryMb % 1024 == 0) {
    "${memoryMb / 1024} GB"
} else {
    "%.1f GB".format(memoryMb / 1024f)
}

private fun openBatteryOptimizationSettings(context: android.content.Context) {
    val intent = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
