package com.noop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// MARK: - AppRoot
//
// The desktop app shell: a fixed sidebar (navigation rail) on the left and the
// active screen on the right. Replaces the Android NavHost + floating bottom bar
// with a simple `when(route)` state switch — no androidx.navigation dependency,
// no NavHostController. A single [DesktopAppViewModel] is created outside and
// shared with every screen.
//
// The sidebar mirrors the macOS sidebar: icon + label rows for every top-level
// destination (Today · Live · Sleep · Health · Workouts · Devices · Settings · Support).
// The active row is highlighted with the accent; inactive rows use textSecondary.

/** Route constants for the sidebar destinations. */
object Routes {
    const val TODAY = "today"
    const val LIVE = "live"
    const val SLEEP = "sleep"
    const val HEALTH = "health"
    const val WORKOUTS = "workouts"
    const val INTELLIGENCE = "intelligence"
    const val TRENDS = "trends"
    const val STRESS = "stress"
    const val BREATHE = "breathe"
    const val COACH = "coach"
    const val INSIGHTS = "insights"
    const val COMPARE = "compare"
    const val DEVICES = "devices"
    const val SETTINGS = "settings"
    const val SUPPORT = "support"
}

/** One sidebar navigation item: route, label, icon. */
private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

/** The sidebar items in display order. */
private val navItems = listOf(
    NavItem(Routes.TODAY, "Today", Icons.Filled.Home),
    NavItem(Routes.LIVE, "Live", Icons.Filled.MonitorHeart),
    NavItem(Routes.SLEEP, "Sleep", Icons.Filled.Bedtime),
    NavItem(Routes.HEALTH, "Health", Icons.Filled.HealthAndSafety),
    NavItem(Routes.WORKOUTS, "Workouts", Icons.Filled.FitnessCenter),
    NavItem(Routes.INTELLIGENCE, "Intelligence", Icons.AutoMirrored.Filled.TrendingUp),
    NavItem(Routes.TRENDS, "Trends", Icons.AutoMirrored.Filled.TrendingUp),
    NavItem(Routes.STRESS, "Stress", Icons.Filled.Psychology),
    NavItem(Routes.BREATHE, "Breathe", Icons.Filled.Air),
    NavItem(Routes.COACH, "Coach", Icons.Filled.AutoAwesome),
    NavItem(Routes.INSIGHTS, "Insights", Icons.Filled.Insights),
    NavItem(Routes.COMPARE, "Compare", Icons.Filled.CompareArrows),
    NavItem(Routes.DEVICES, "Devices", Icons.Filled.Sensors),
    NavItem(Routes.SETTINGS, "Settings", Icons.Filled.Settings),
    NavItem(Routes.SUPPORT, "Support", Icons.AutoMirrored.Filled.Help),
)

/**
 * The app shell. A [NoopTheme]-wrapped [Row]: sidebar on the left, the active
 * screen on the right with a crossfade between routes.
 */
@Composable
fun AppRoot(
    viewModel: DesktopAppViewModel,
    dbPath: String = "",
    onReinit: () -> Unit = {},
) {
    var route by rememberSaveable { mutableStateOf(Routes.TODAY) }

    NoopTheme {
        Row(
            modifier = Modifier.fillMaxSize().background(Palette.surfaceBase),
        ) {
            Sidebar(
                currentRoute = route,
                onNavigate = { route = it },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Palette.surfaceBase),
            ) {
                Crossfade(
                    targetState = route,
                    animationSpec = tween(durationMillis = 240),
                    label = "screenFade",
                ) { current ->
                    when (current) {
                        Routes.TODAY -> TodayScreen(viewModel = viewModel)
                        Routes.LIVE -> LiveScreen(viewModel = viewModel)
                        Routes.SLEEP -> SleepScreen(viewModel = viewModel)
                        Routes.HEALTH -> HealthScreen(viewModel = viewModel)
                        Routes.WORKOUTS -> WorkoutsScreen(viewModel = viewModel)
                        Routes.INTELLIGENCE -> IntelligenceScreen(viewModel = viewModel)
                        Routes.TRENDS -> TrendsScreen(viewModel = viewModel)
                        Routes.STRESS -> StressScreen(viewModel = viewModel)
                        Routes.BREATHE -> BreatheScreen(viewModel = viewModel)
                        Routes.COACH -> CoachScreen(viewModel = viewModel)
                        Routes.INSIGHTS -> InsightsScreen(viewModel = viewModel)
                        Routes.COMPARE -> CompareScreen(viewModel = viewModel)
                        Routes.DEVICES -> DevicesScreen(viewModel = viewModel)
                        Routes.SETTINGS -> SettingsScreen(viewModel = viewModel, dbPath = dbPath, onReinit = onReinit)
                        Routes.SUPPORT -> SupportScreen()
                        else -> ComingSoon(current)
                    }
                }
            }
        }
    }
}

// MARK: - Sidebar

/** The fixed navigation sidebar: a brand header over the nav-item list. */
@Composable
private fun Sidebar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
) {
    Surface(
        color = Palette.surfaceRaised,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxHeight()
            .width(220.dp),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 20.dp, horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Brand header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 4.dp, bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BrandMark(size = 24.dp)
                Text(
                    text = "NOOP",
                    style = NoopType.title2.copy(fontWeight = FontWeight.Bold),
                    color = Palette.textPrimary,
                )
            }

            // Nav items (scrollable for 15 items)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(navItems.size) { index ->
                    val item = navItems[index]
                    NavRow(
                        item = item,
                        active = currentRoute == item.route,
                        onClick = { onNavigate(item.route) },
                    )
                }
            }

            // Build stamp at the bottom
            Text(
                text = "NOOP Desktop",
                style = NoopType.footnote,
                color = Palette.textTertiary,
                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
            )
        }
    }
}

/** One sidebar row: icon + label, highlighted when active. */
@Composable
private fun NavRow(
    item: NavItem,
    active: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (active) Palette.accent.copy(alpha = 0.12f) else Palette.surfaceRaised
    val tint = if (active) Palette.accent else Palette.textSecondary
    val labelColor = if (active) Palette.textPrimary else Palette.textSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            item.icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            item.label,
            style = NoopType.body.copy(
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = labelColor,
        )
    }
}

// MARK: - BrandMark

/**
 * The NOOP logo glyph: an OPEN recovery ring (~80% arc, round caps, 12 o'clock
 * start, clockwise) in the gold gradient with a solid core dot at the centre.
 * Same glyph as the Android twin's [BrandMark], shrunk for the sidebar header.
 */
@Composable
internal fun BrandMark(size: androidx.compose.ui.unit.Dp = 22.dp) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.13f
        val radius = (this.size.minDimension - stroke) / 2f
        val topLeft = androidx.compose.ui.geometry.Offset(center.x - radius, center.y - radius)
        val arcSize = androidx.compose.ui.geometry.Size(radius * 2f, radius * 2f)
        val capStroke = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)

        drawCircle(
            color = Palette.hairline.copy(alpha = 0.5f),
            radius = radius,
            center = center,
            style = capStroke,
        )
        drawArc(
            color = Palette.chargeColor,
            startAngle = -90f,
            sweepAngle = 288f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = capStroke,
        )
        drawCircle(color = androidx.compose.ui.graphics.Color.White, radius = stroke * 0.62f, center = center)
    }
}

// MARK: - ComingSoon placeholder

/** Placeholder for routes that haven't been built yet. */
@Composable
fun ComingSoon(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        NoopCard(padding = 28.dp) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Sensors,
                    contentDescription = null,
                    tint = Palette.textTertiary,
                )
                Spacer(Modifier.height(4.dp))
                Text(text, style = NoopType.title2, color = Palette.textPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Overline("Coming soon", color = Palette.textSecondary)
                Text(
                    "This section is on the way.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}
