package com.minehost.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.minehost.app.ui.components.MineHostMark
import kotlinx.coroutines.launch

private data class OnboardingPage(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val bullets: List<String>
)

private val PAGES = listOf(
    OnboardingPage(
        icon = Icons.Rounded.PlayArrow,
        title = "Your server, in your pocket",
        subtitle = "Host a Minecraft world directly on this phone.",
        bullets = listOf(
            "Paper, Purpur, Leaf, Fabric, Forge & vanilla",
            "Start, stop and watch it live",
            "LAN play with no extra hardware"
        )
    ),
    OnboardingPage(
        icon = Icons.Rounded.CloudDownload,
        title = "Get a server core",
        subtitle = "Catalog pulls builds straight from official release feeds.",
        bullets = listOf(
            "Pick a platform, then a Minecraft version",
            "Downloads are checksum verified",
            "Everything stays in MineHost's private storage"
        )
    ),
    OnboardingPage(
        icon = Icons.Rounded.Memory,
        title = "Make it run",
        subtitle = "Set the memory limit and point MineHost at Java.",
        bullets = listOf(
            "Test your Java runtime before starting",
            "Accept the Minecraft EULA in Settings",
            "Battery saver mode for low-end phones"
        )
    ),
    OnboardingPage(
        icon = Icons.Rounded.Terminal,
        title = "Watch it work",
        subtitle = "The console shows every event and takes commands.",
        bullets = listOf(
            "Live players, TPS, RAM and CPU",
            "Send commands and read the log",
            "Keep the notification so Android won't sleep it"
        )
    )
)

@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pagerState = rememberPagerState(pageCount = { PAGES.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == PAGES.size - 1

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MineHostMark()
            Spacer(Modifier.width(12.dp))
            Text(
                "MineHost",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            if (!isLastPage) {
                TextButton(onClick = onFinish) { Text("Skip") }
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            OnboardingPageContent(PAGES[page])
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(PAGES.size) { index ->
                val active = pagerState.currentPage == index
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (active) 9.dp else 7.dp)
                        .clip(CircleShape)
                        .background(
                            if (active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant
                        )
                )
            }
        }

        Button(
            onClick = {
                if (isLastPage) {
                    onFinish()
                } else {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                }
            },
            modifier = Modifier.fillMaxWidth().height(53.dp),
            shape = RoundedCornerShape(17.dp)
        ) {
            Text(if (isLastPage) "Start hosting" else "Next", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun OnboardingPageContent(page: OnboardingPage) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(72.dp),
            shape = RoundedCornerShape(23.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(page.icon, contentDescription = null, modifier = Modifier.size(34.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            page.title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Start
        )
        Spacer(Modifier.height(8.dp))
        Text(
            page.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(22.dp))
        page.bullets.forEach { bullet ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(24.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(15.dp))
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(bullet, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
