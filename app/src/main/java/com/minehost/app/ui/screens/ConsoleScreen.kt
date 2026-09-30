package com.minehost.app.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.minehost.app.data.ConsoleLevel
import com.minehost.app.data.ConsoleLine
import com.minehost.app.data.ServerPhase
import com.minehost.app.data.ServerSnapshot
import com.minehost.app.ui.components.ConsoleLevelIcon
import com.minehost.app.ui.components.EmptyConsole
import com.minehost.app.ui.components.MineHostMark
import com.minehost.app.ui.components.StatusPill
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ConsoleFilter { ALL, ISSUES }

private val QUICK_COMMANDS = listOf("save-all", "list", "tps", "who", "whitelist list")

private val CONSOLE_BACKGROUND = Color(0xFF0A1014)
private val CONSOLE_BORDER = Color(0xFF26363A)
private val CONSOLE_TEXT = Color(0xFFD7E4DC)
private val CONSOLE_TIMESTAMP = Color(0xFF71857A)

@Composable
fun ConsoleScreen(
    snapshot: ServerSnapshot,
    commandHistory: List<String>,
    onClear: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onSendCommand: (String) -> Unit,
    onCopyLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    var filter by remember { mutableStateOf(ConsoleFilter.ALL) }
    var command by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    // Keyed on logsRevision so phase/player updates don't re-filter the list.
    val visibleLines = remember(snapshot.logsRevision, filter) {
        if (filter == ConsoleFilter.ISSUES) {
            snapshot.logs.filter { it.level == ConsoleLevel.ERROR || it.level == ConsoleLevel.WARNING }
        } else {
            snapshot.logs
        }
    }
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val isRunning = snapshot.phase == ServerPhase.RUNNING
    val canStop = isRunning || snapshot.phase == ServerPhase.STARTING

    LaunchedEffect(snapshot.logsRevision, filter) {
        if (visibleLines.isEmpty()) return@LaunchedEffect
        // Follow new output only when the user is already near the bottom, so
        // reading history isn't interrupted — and keep following once the log
        // buffer reaches its cap and the size stops changing.
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (lastVisible >= visibleLines.lastIndex - 2) {
            listState.animateScrollToItem(visibleLines.lastIndex)
        }
    }

    val submit: () -> Unit = {
        val trimmed = command.trim()
        if (trimmed.isNotEmpty() && isRunning) {
            onSendCommand(trimmed)
            command = ""
        }
    }

    val logPane: @Composable (Modifier) -> Unit = { paneModifier ->
        ConsoleLogPane(
            lines = visibleLines,
            listState = listState,
            formatter = timeFormatter,
            modifier = paneModifier
        )
    }
    val controls: @Composable (Boolean) -> Unit = { compact ->
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 10.dp)) {
            ConsoleCommandBar(
                command = command,
                onCommandChange = { command = it },
                history = commandHistory,
                enabled = isRunning,
                onSubmit = submit
            )
            ConsoleActions(
                snapshot = snapshot,
                canStop = canStop,
                onStart = onStart,
                onStop = onStop,
                onRestart = onRestart
            )
        }
    }

    // Landscape phones and short windows get a side-by-side layout: a full-height
    // log on the left and scrollable controls on the right, so nothing is clipped.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    if (isLandscape) {
        Row(
            modifier = modifier
                .fillMaxSize()
                .imePadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ConsoleHeader(snapshot = snapshot, onClear = onClear, onCopyLog = onCopyLog)
                ConsoleStatusRow(
                    snapshot = snapshot,
                    filter = filter,
                    onFilterChange = { filter = it }
                )
                logPane(Modifier.fillMaxWidth().weight(1f))
            }
            Column(
                modifier = Modifier
                    .width(320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                controls(true)
            }
        }
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .imePadding()
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ConsoleHeader(snapshot = snapshot, onClear = onClear, onCopyLog = onCopyLog)
            ConsoleStatusRow(
                snapshot = snapshot,
                filter = filter,
                onFilterChange = { filter = it }
            )
            logPane(Modifier.fillMaxWidth().weight(1f))
            controls(false)
        }
    }
}

@Composable
private fun ConsoleHeader(
    snapshot: ServerSnapshot,
    onClear: () -> Unit,
    onCopyLog: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MineHostMark()
        Spacer(Modifier.width(12.dp))
        Text(
            "Server console",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2
        )
        IconButton(onClick = onCopyLog, enabled = snapshot.logs.isNotEmpty()) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy log")
        }
        IconButton(onClick = onClear, enabled = snapshot.logs.isNotEmpty()) {
            Icon(Icons.Rounded.ClearAll, contentDescription = "Clear console")
        }
    }
}

@Composable
private fun ConsoleStatusRow(
    snapshot: ServerSnapshot,
    filter: ConsoleFilter,
    onFilterChange: (ConsoleFilter) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusPill(snapshot.phase)
        if (snapshot.phase == ServerPhase.RUNNING || snapshot.phase == ServerPhase.STARTING) {
            Text(
                "${snapshot.onlinePlayers}/${snapshot.maxPlayers}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Spacer(Modifier.weight(1f))
        FilterChip(
            selected = filter == ConsoleFilter.ISSUES,
            onClick = {
                onFilterChange(if (filter == ConsoleFilter.ISSUES) ConsoleFilter.ALL else ConsoleFilter.ISSUES)
            },
            label = { Text(if (filter == ConsoleFilter.ISSUES) "Issues" else "All events") },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
            )
        )
    }
}

@Composable
private fun ConsoleLogPane(
    lines: List<ConsoleLine>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    formatter: SimpleDateFormat,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = CONSOLE_BACKGROUND,
        contentColor = CONSOLE_TEXT,
        border = BorderStroke(1.dp, CONSOLE_BORDER)
    ) {
        if (lines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyConsole()
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(
                    items = lines,
                    key = { it.seq },
                    contentType = { "consoleRow" }
                ) { line ->
                    ConsoleRow(line, formatter)
                }
            }
        }
    }
}

@Composable
private fun ConsoleCommandBar(
    command: String,
    onCommandChange: (String) -> Unit,
    history: List<String>,
    enabled: Boolean,
    onSubmit: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = command,
                onValueChange = onCommandChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Server command") },
                placeholder = { Text(if (enabled) "save-all" else "Start the server first") },
                enabled = enabled,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSubmit() })
            )
            FilledTonalIconButton(
                onClick = onSubmit,
                enabled = enabled && command.isNotBlank(),
                modifier = Modifier.size(52.dp)
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send command")
            }
        }
        if (enabled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (QUICK_COMMANDS + history.take(2)).distinct().forEach { quickCommand ->
                    FilterChip(
                        selected = false,
                        onClick = { onCommandChange(quickCommand) },
                        label = { Text(quickCommand) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsoleActions(
    snapshot: ServerSnapshot,
    canStop: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit
) {
    when {
        snapshot.phase == ServerPhase.STOPPING -> {
            OutlinedButton(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(15.dp)
            ) {
                Text("Stopping…")
            }
        }
        snapshot.phase == ServerPhase.RUNNING -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(15.dp)
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Stop")
                }
                OutlinedButton(
                    onClick = onRestart,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(15.dp)
                ) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                    Spacer(Modifier.width(7.dp))
                    Text("Restart")
                }
            }
        }
        canStop -> {
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(15.dp)
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("Cancel start")
            }
        }
        else -> {
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(15.dp),
                colors = ButtonDefaults.buttonColors()
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(7.dp))
                Text("Start server")
            }
        }
    }
}

@Composable
private fun ConsoleRow(line: ConsoleLine, formatter: SimpleDateFormat) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = formatter.format(Date(line.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = CONSOLE_TIMESTAMP
        )
        ConsoleLevelIcon(line.level)
        Text(
            text = line.text,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = when (line.level) {
                ConsoleLevel.ERROR -> Color(0xFFFF9E92)
                ConsoleLevel.WARNING -> Color(0xFFFFD18A)
                ConsoleLevel.SUCCESS -> Color(0xFFA9E6B9)
                ConsoleLevel.INFO -> CONSOLE_TEXT
            }
        )
    }
}
