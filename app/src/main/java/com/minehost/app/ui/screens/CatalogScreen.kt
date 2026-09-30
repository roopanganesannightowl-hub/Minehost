package com.minehost.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.minehost.app.data.CatalogUiState
import com.minehost.app.data.CoreAsset
import com.minehost.app.data.CoreCatalogRepository
import com.minehost.app.data.CorePlatform
import com.minehost.app.data.CorePlatformKind
import com.minehost.app.data.MinecraftVersion

@Composable
fun CatalogScreen(
    state: CatalogUiState,
    onRefresh: () -> Unit,
    onSelectPlatform: (String) -> Unit,
    onSelectVersion: (MinecraftVersion) -> Unit,
    onDownload: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var versionQuery by remember { mutableStateOf("") }
    var showSnapshots by remember { mutableStateOf(false) }
    var versionLimit by remember { mutableIntStateOf(40) }
    var confirmationAsset by remember { mutableStateOf<CoreAsset?>(null) }
    val selectedPlatform = CoreCatalogRepository.platform(state.selectedPlatformId)
    val filteredVersions = remember(state.versions, versionQuery, showSnapshots) {
        state.versions
            .filter { showSnapshots || it.type == "release" }
            .filter { versionQuery.isBlank() || it.id.contains(versionQuery.trim(), ignoreCase = true) }
    }
    val visibleVersions = filteredVersions.take(versionLimit)

    LaunchedEffect(versionQuery, showSnapshots, filteredVersions) {
        versionLimit = 40
    }

    LaunchedEffect(Unit) {
        if (state.versions.isEmpty() && !state.isLoadingVersions) onRefresh()
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "CORE CATALOG",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Text("Choose a server core", style = MaterialTheme.typography.headlineSmall)
                }
                IconButton(onClick = onRefresh, enabled = !state.isLoadingVersions) {
                    if (state.isLoadingVersions) {
                        CircularProgressIndicator(modifier = Modifier.size(21.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh catalog")
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    MaterialTheme.colorScheme.tertiaryContainer,
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f)
                                )
                            )
                        )
                        .padding(20.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                modifier = Modifier.size(45.dp),
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Official release feeds", style = MaterialTheme.typography.titleLarge)
                                Text(
                                    "Verified builds, installed privately.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CatalogMetric("${state.versions.size}", "versions")
                            CatalogMetric("${CoreCatalogRepository.platforms.size}", "platforms")
                            CatalogMetric("SHA", "checks")
                        }
                    }
                }
            }
        }

        item {
            SectionHeaderCompat("Server platforms")
        }
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(end = 4.dp)
            ) {
                items(CoreCatalogRepository.platforms, key = { it.id }) { platform ->
                    PlatformCard(
                        platform = platform,
                        selected = platform.id == state.selectedPlatformId,
                        onClick = { onSelectPlatform(platform.id) }
                    )
                }
            }
        }

        item {
            PlatformDetail(
                platform = selectedPlatform,
                minecraftVersion = state.selectedVersion?.id,
                asset = state.asset,
                errorMessage = state.error,
                isLoadingVersions = state.isLoadingVersions,
                isResolving = state.isResolving,
                isDownloading = state.isDownloading,
                downloadProgress = state.downloadProgress,
                onDownload = { state.asset?.let { confirmationAsset = it } },
                onRetry = onRefresh,
                onOpenUrl = onOpenUrl
            )
        }

        item {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(15.dp)
            ) {
                Icon(Icons.Rounded.Code, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Import a custom server JAR")
            }
        }

        item {
            SectionHeaderCompat(
                title = "Minecraft versions",
                action = {
                    Text(
                        "${filteredVersions.size} shown",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
        item {
            OutlinedTextField(
                value = versionQuery,
                onValueChange = { versionQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search versions") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (versionQuery.isNotBlank()) {
                        IconButton(onClick = { versionQuery = "" }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Clear version search")
                        }
                    }
                }
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = !showSnapshots,
                    onClick = { showSnapshots = false },
                    label = { Text("Releases") }
                )
                FilterChip(
                    selected = showSnapshots,
                    onClick = { showSnapshots = true },
                    label = { Text("Releases + snapshots") }
                )
            }
        }
        if (filteredVersions.isEmpty() && !state.isLoadingVersions) {
            item {
                Text(
                    "No versions match that search.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 14.dp)
                )
            }
        }
        items(visibleVersions, key = { it.id }) { version ->
            VersionRow(
                version = version,
                selected = version.id == state.selectedVersion?.id,
                onClick = { onSelectVersion(version) }
            )
        }
        if (filteredVersions.size > versionLimit) {
            item {
                OutlinedButton(
                    onClick = { versionLimit += 40 },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Show more versions")
                }
            }
        }

        state.notice?.let { message ->
            item {
                MessageCard(
                    icon = Icons.Rounded.Info,
                    title = "Catalog note",
                    message = message,
                    isError = false
                )
            }
        }
        state.downloadedName?.let { name ->
            item {
                MessageCard(
                    icon = Icons.Rounded.Verified,
                    title = "Download verified",
                    message = "$name is installed and ready.",
                    isError = false
                )
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(11.dp))
                    Text(
                        "Checksum verified and never executed on its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    confirmationAsset?.let { asset ->
        AlertDialog(
            onDismissRequest = { confirmationAsset = null },
            title = { Text("Download this core?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(asset.fileName, style = MaterialTheme.typography.titleMedium)
                    Text(asset.note, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        buildString {
                            append(asset.sourceLabel)
                            if (asset.sizeBytes > 0) append("  •  ${formatBytes(asset.sizeBytes)}")
                            if (!asset.checksum.isNullOrBlank()) append("  •  ${asset.checksumAlgorithm} published")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (asset.canInstallAsCore) {
                            "Stored privately and selected in Settings. Nothing runs until you press Start."
                        } else {
                            "This is an installer, not a runnable core. MineHost will not execute it."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmationAsset = null
                        onDownload()
                    }
                ) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Confirm download")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmationAsset = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun CatalogMetric(value: String, label: String) {
    Surface(
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.48f)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlatformCard(
    platform: CorePlatform,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(164.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(31.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) MaterialTheme.colorScheme.surface.copy(alpha = 0.65f) else MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(platform.shortName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = "Selected", modifier = Modifier.size(18.dp))
            }
            Text(platform.name, style = MaterialTheme.typography.titleMedium)
            Text(
                platform.tagline,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PlatformDetail(
    platform: CorePlatform,
    minecraftVersion: String?,
    asset: CoreAsset?,
    errorMessage: String?,
    isLoadingVersions: Boolean,
    isResolving: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float?,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(platform.name, style = MaterialTheme.typography.titleLarge)
                    Text(platform.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CatalogTag(platform.kindLabel())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Verified, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(platform.sourceLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            minecraftVersion?.let { version ->
                Text(
                    "Target: Minecraft $version",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isLoadingVersions) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Loading versions…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (isResolving) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Resolving the ${platform.name} build…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (errorMessage != null) {
                Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Retry")
                }
            } else if (isDownloading) {
                if (downloadProgress == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { downloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    if (downloadProgress == null) {
                        "Downloading…"
                    } else {
                        "Downloading… ${(downloadProgress * 100).toInt()}%"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (asset != null) {
                AssetDetails(asset)
                Button(
                    onClick = onDownload,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(15.dp)
                ) {
                    Icon(Icons.Rounded.CloudDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (asset.canInstallAsCore) "Download and install" else "Download installer")
                }
            } else if (platform.kind == CorePlatformKind.CLIENT_ONLY || platform.kind == CorePlatformKind.MANUAL) {
                OutlinedButton(
                    onClick = { onOpenUrl(platform.officialUrl) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(15.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Open official project")
                }
            }
        }
    }
}

@Composable
private fun AssetDetails(asset: CoreAsset) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Code, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(asset.fileName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            buildString {
                append(asset.sourceLabel)
                if (asset.sizeBytes > 0) append("  •  ${formatBytes(asset.sizeBytes)}")
                if (!asset.checksum.isNullOrBlank()) append("  •  ${asset.checksumAlgorithm} verified")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(asset.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VersionRow(version: MinecraftVersion, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        border = if (selected) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(version.id, style = MaterialTheme.typography.titleMedium)
                Text(version.type.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) Icon(Icons.Rounded.CheckCircle, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun MessageCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    message: String,
    isError: Boolean
) {
    val container = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val content = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Card(shape = RoundedCornerShape(19.dp), colors = CardDefaults.cardColors(containerColor = container)) {
        Row(modifier = Modifier.padding(15.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = content)
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = content)
                Text(message, style = MaterialTheme.typography.bodySmall, color = content.copy(alpha = 0.82f))
            }
        }
    }
}

@Composable
private fun CatalogTag(text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun SectionHeaderCompat(title: String, action: (@Composable () -> Unit)? = null) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        action?.invoke()
    }
}

private fun CorePlatform.kindLabel(): String = when (kind) {
    CorePlatformKind.SERVER -> "SERVER"
    CorePlatformKind.INSTALLER -> "INSTALLER"
    CorePlatformKind.CLIENT_ONLY -> "CLIENT ONLY"
    CorePlatformKind.MANUAL -> "MANUAL"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000f)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000f)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000f)
    else -> "$bytes B"
}
