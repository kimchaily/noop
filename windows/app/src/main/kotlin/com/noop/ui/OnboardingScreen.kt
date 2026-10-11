package com.noop.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Onboarding — a simple welcome screen shown on first launch. Walks the user
 * through what NOOP does (pages: Welcome → Connect → Done) and hands off to
 * the app shell.
 *
 * Adapted from the Android [OnboardingScreen]:
 *  - `LocalContext` / `rememberLauncherForActivityResult` → removed
 *  - Android BLE permissions → removed (desktop has no runtime permission gate)
 *  - `HealthConnectClient` / `PermissionController` → removed
 *  - `rememberSaveable` kept (Compose Desktop compatible)
 *  - `collectAsStateWithLifecycle` → `collectAsState`
 *  - Profile capture + history import → removed (simplified desktop port)
 */
@Composable
fun OnboardingScreen(
    viewModel: DesktopAppViewModel,
    onFinished: () -> Unit,
) {
    val pages = remember { OnboardingPage.entries }
    var pageIndex by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[pageIndex]
    val live by viewModel.live.collectAsState()

    // Auto-advance to Done when the strap bonds on the Connect page.
    androidx.compose.runtime.LaunchedEffect(live.bonded) {
        if (live.bonded && page == OnboardingPage.Connect) {
            pageIndex++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.surfaceBase),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top: brand + page content
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.padding(top = 48.dp),
            ) {
                BrandMark(size = 56.dp)
                Text(
                    "NOOP",
                    style = NoopType.display(48f),
                    color = Palette.textPrimary,
                )
                when (page) {
                    OnboardingPage.Welcome -> WelcomePage()
                    OnboardingPage.Connect -> ConnectPage(
                        live = live,
                        onConnect = { viewModel.connect() },
                    )
                    OnboardingPage.Done -> DonePage()
                }
            }

            // Bottom: navigation buttons + page indicator
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Page indicator dots
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pages.forEachIndexed { i, _ ->
                        val active = i == pageIndex
                        Box(
                            modifier = Modifier
                                .size(if (active) 10.dp else 8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (active) Palette.accent else Palette.hairline),
                        )
                    }
                }

                when (page) {
                    OnboardingPage.Welcome -> NoopButton(
                        text = "Get started",
                        kind = NoopButtonKind.Primary,
                        onClick = { pageIndex++ },
                    )
                    OnboardingPage.Connect -> Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
                        NoopButton(
                            text = "Skip",
                            kind = NoopButtonKind.Tertiary,
                            onClick = { pageIndex++ },
                        )
                        NoopButton(
                            text = if (live.connected) "Connected" else "Connect strap",
                            kind = NoopButtonKind.Primary,
                            leadingIcon = Icons.Filled.Bluetooth,
                            onClick = { viewModel.connect() },
                            enabled = !live.connected,
                        )
                    }
                    OnboardingPage.Done -> NoopButton(
                        text = "Enter NOOP",
                        kind = NoopButtonKind.Primary,
                        leadingIcon = Icons.Filled.Check,
                        onClick = onFinished,
                    )
                }
            }
        }
    }
}

// MARK: - Onboarding pages

private enum class OnboardingPage { Welcome, Connect, Done }

@Composable
private fun WelcomePage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Your offline WHOOP companion",
            style = NoopType.title1,
            color = Palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            "NOOP brings your WHOOP strap's data to the desktop — recovery, sleep, health vitals, and workouts, all stored locally on your device. No cloud, no account, just you and your data.",
            style = NoopType.subhead,
            color = Palette.textSecondary,
            textAlign = TextAlign.Center,
        )
        // Feature highlights
        FeatureRow(Icons.Filled.MonitorHeart, "Real-time heart rate")
        FeatureRow(Icons.Filled.FavoriteBorder, "Recovery & sleep insights")
        FeatureRow(Icons.Filled.Sensors, "Device management")
    }
}

@Composable
private fun ConnectPage(live: com.noop.ble.LiveState, onConnect: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Connect your WHOOP",
            style = NoopType.title1,
            color = Palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            "Pair your WHOOP strap via Bluetooth to start streaming live heart rate and syncing your data.",
            style = NoopType.subhead,
            color = Palette.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (live.bonded) {
            StatePill("Strap bonded!", tone = StrandTone.Positive, pulsing = true)
        } else if (live.connected) {
            StatePill("Connecting…", tone = StrandTone.Accent, pulsing = true)
        } else {
            StatePill("No strap connected", tone = StrandTone.Neutral)
        }
    }
}

@Composable
private fun DonePage() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = Palette.statusPositive,
            modifier = Modifier.size(48.dp),
        )
        Text(
            "You're all set",
            style = NoopType.title1,
            color = Palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            "NOOP is ready. Your recovery, sleep, and health data will appear on the Today screen.",
            style = NoopType.subhead,
            color = Palette.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(20.dp))
        Text(text, style = NoopType.body, color = Palette.textPrimary)
    }
}
