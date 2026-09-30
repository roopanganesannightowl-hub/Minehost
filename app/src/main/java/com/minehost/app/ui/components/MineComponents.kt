package com.minehost.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.minehost.app.data.ConsoleLevel
import com.minehost.app.data.ServerPhase

@Composable
fun MineHostMark(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(46.dp),
        shape = RoundedCornerShape(15.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.GridView,
                contentDescription = "MineHost",
                modifier = Modifier.size(25.dp)
            )
        }
    }
}

@Composable
fun StatusOrb(
    phase: ServerPhase,
    modifier: Modifier = Modifier,
    size: Dp = 74.dp
) {
    val active = phase == ServerPhase.RUNNING
    val busy = phase == ServerPhase.STARTING || phase == ServerPhase.STOPPING
    val color = when (phase) {
        ServerPhase.RUNNING -> MaterialTheme.colorScheme.primary
        ServerPhase.ERROR -> MaterialTheme.colorScheme.error
        ServerPhase.STARTING, ServerPhase.STOPPING -> MaterialTheme.colorScheme.tertiary
        ServerPhase.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Canvas(modifier = modifier.size(size)) {
        val radius = this.size.minDimension / 2f
        drawCircle(color.copy(alpha = 0.12f), radius)
        drawCircle(color.copy(alpha = 0.18f), radius * 0.72f)
        drawCircle(color, radius * 0.42f)
        if (active || busy) {
            drawArc(
                color = color.copy(alpha = 0.8f),
                startAngle = -55f,
                sweepAngle = 110f,
                useCenter = false,
                style = Stroke(width = radius * 0.09f)
            )
        }
        drawCircle(Color.White.copy(alpha = 0.35f), radius * 0.13f, center = center.copy(x = center.x - radius * 0.11f, y = center.y - radius * 0.13f))
    }
}

@Composable
fun StatusPill(
    phase: ServerPhase,
    modifier: Modifier = Modifier
) {
    val (label, color) = when (phase) {
        ServerPhase.RUNNING -> "Online" to MaterialTheme.colorScheme.primary
        ServerPhase.STARTING -> "Starting" to MaterialTheme.colorScheme.tertiary
        ServerPhase.STOPPING -> "Stopping" to MaterialTheme.colorScheme.tertiary
        ServerPhase.ERROR -> "Needs attention" to MaterialTheme.colorScheme.error
        ServerPhase.STOPPED -> "Offline" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.13f),
        contentColor = color
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        action?.invoke()
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(19.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(
            modifier = Modifier.padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier.size(28.dp),
                shape = RoundedCornerShape(9.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) { icon() }
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    value,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = valueColor
        )
    }
}

@Composable
fun ConsoleLevelIcon(level: ConsoleLevel, modifier: Modifier = Modifier) {
    val (icon, tint) = when (level) {
        ConsoleLevel.SUCCESS -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
        ConsoleLevel.ERROR -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
        ConsoleLevel.WARNING -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.tertiary
        ConsoleLevel.INFO -> Icons.Rounded.GridView to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(icon, contentDescription = null, modifier = modifier.size(15.dp), tint = tint)
}

@Composable
fun EmptyConsole(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Surface(
            modifier = Modifier.size(54.dp),
            shape = CircleShape,
            color = Color(0xFF1B2B2D)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color(0xFFB8F36B),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
        Text("Console is quiet", style = MaterialTheme.typography.titleMedium, color = Color(0xFFD7E4DC))
        Text(
            "Server output appears here.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF9AAEA3)
        )
    }
}
