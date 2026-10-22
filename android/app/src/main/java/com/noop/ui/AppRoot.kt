package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Hexagon
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.noop.BuildConfig
import com.noop.R
import com.noop.analytics.FusionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlin.math.roundToInt
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

// MARK: - Navigation model
//
// The macOS app's sidebar holds many sections; on Android (mirroring the iOS RootTabView) we surface
// them through a unified floating "glass" bottom bar (Today · Trends · Sleep · More) for the everyday
// screens, with a "More" sheet that lists the full grouped set — so every destination is one tap away
// without a global hamburger/drawer. Destinations are grouped exactly as the sidebar groups them.
// Routes whose screens belong to later waves point at a ComingSoon placeholder so the app compiles today.

/** A single drawer destination: stable route, display title (localized via [titleRes]), sidebar icon. */
private enum class Destination(
    val route: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
) {
    // Group: Today
    Today("today", R.string.nav_today, Icons.Filled.Home),
    Intelligence("intelligence", R.string.nav_intelligence, Icons.Filled.Psychology),
    // Optional, default-OFF (task #43): the Coupled view (WHOOP-style day read). Reached ONLY via the
    // Today dashboard "Coupled view" card tap-through, so it is deliberately NOT in any [DrawerGroup].
    CoupledView("coupled_view", R.string.nav_coupled_view, Icons.Filled.Hexagon),

    // Group: Live
    Live("live", R.string.nav_live, Icons.Filled.FavoriteBorder),
    Intervals("intervals", R.string.nav_intervals, Icons.Filled.Timeline),

    // Group: Recovery
    Sleep("sleep", R.string.nav_sleep, Icons.Filled.Bedtime),
    Breathe("breathe", R.string.nav_breathe, Icons.Filled.Air),
    // Its own destination rather than a fourth Breathe mode: the protocol is the opposite of Breathe's
    // (fast power breathing, not a downshift), its HRV readout would be actively misleading there, and
    // it carries a first-run safety gate that has no business on the calm-breathing screen.
    WimHof("wim_hof", R.string.nav_wim_hof, Icons.Filled.AcUnit),
    Stress("stress", R.string.nav_stress, Icons.Filled.Spa),

    // Group: Activity
    Workouts("workouts", R.string.nav_workouts, Icons.Filled.FitnessCenter),
    Trends("trends", R.string.nav_trends, Icons.AutoMirrored.Filled.TrendingUp),

    // Group: Insight
    Coach("coach", R.string.nav_coach, Icons.Filled.AutoAwesome),
    InsightsHub("insights_hub", R.string.nav_insights_hub, Icons.Filled.Insights),
    Insights("insights", R.string.nav_insights, Icons.Filled.Insights),
    Explore("explore", R.string.nav_explore, Icons.Filled.Explore),
    Compare("compare", R.string.nav_compare, Icons.AutoMirrored.Filled.CompareArrows),

    // Group: Health
    Health("health", R.string.nav_health, Icons.Filled.MonitorHeart),
    Hydration("hydration", R.string.nav_hydration, Icons.Filled.WaterDrop),
    VitalSigns("vital_signs", R.string.nav_vital_signs, Icons.Filled.HealthAndSafety),
    VitalSignsDetail("vital_detail/{key}", R.string.nav_vital_signs, Icons.Filled.HealthAndSafety),
    LabBook("lab_book", R.string.nav_lab_book, Icons.Filled.HealthAndSafety),
    Rhythm("rhythm", R.string.nav_rhythm, Icons.Filled.MonitorHeart),
    AppleHealth("apple_health", R.string.nav_apple_health, Icons.Filled.HealthAndSafety),

    // Group: System
    Automations("automations", R.string.nav_automations, Icons.Filled.Bolt),
    // "Alarms" is the ONE alarm surface (#766): the phone-based Wake Window (light-sleep detection with a
    // guaranteed OS backup), the strap's own firmware wake-alarm, and the wind-down reminder, all in one
    // place. Previously "Wake Window" (#730), but the strap alarm moved in from Automations so the broader
    // name fits. Route id stays "smart_alarm" (display string only).
    SmartAlarm("smart_alarm", R.string.nav_alarms, Icons.Filled.Alarm),
    Devices("devices", R.string.nav_devices, Icons.Filled.Sensors),
    DataSources("data_sources", R.string.nav_data_sources, Icons.Filled.Storage),
    BackupSync("backup_sync", R.string.nav_backup_sync, Icons.Filled.CloudSync),
    FusedRecord("fused_record", R.string.nav_fused_record, Icons.AutoMirrored.Filled.CompareArrows),
    Notifications("notifications", R.string.nav_notifications, Icons.Filled.Notifications),
    Support("support", R.string.nav_support, Icons.Filled.Tune),
    Settings("settings", R.string.nav_settings, Icons.Filled.Settings),
    // Profile is its own page (the "account" surface), reached from the Today header avatar — not listed
    // in any [DrawerGroup], like CoupledView. A Settings gear on the Profile screen hops to Settings.
    Profile("profile", R.string.nav_profile, Icons.Filled.AccountCircle),
    TestCentre("test_centre", R.string.nav_test_centre, Icons.Filled.BugReport),

    // The "More" tab: its own navigated page (mirroring the iOS More tab) that hosts the full
    // grouped destination list. It is NOT itself in any [DrawerGroup] — it's the door to them.
    More("more", R.string.nav_more, Icons.Filled.MoreHoriz);

    companion object {
        /** Resolve the destination owning the current back-stack route (defaults to Today). */
        fun forRoute(route: String?): Destination =
            entries.firstOrNull {
                // Match parameterised routes (e.g. "vital_detail/rhr" vs "vital_detail/{key}") by
                // base path so the top-bar title resolves correctly on a detail screen, not "Today".
                it.route == route || it.route.substringBefore('/') == route?.substringBefore('/')
            } ?: Today
    }
}

/** More-page groups, mirroring the iOS More tab exactly: Insights · Body · Data · App. `defaultExpanded`
 *  mirrors the iOS S2 default: Insights + Body open at rest, Data + App collapsed to just their header. */
// [header] is the STABLE persistence key (stored in SharedPreferences and kept byte-identical to iOS's
// `more.expandedSections` CSV — see [MoreSectionPrefs]); it must NEVER be localized. [headerRes] is the
// localized DISPLAY label the More page shows. Decoupling the two lets the label translate without
// touching the persisted open/closed state or the iOS parity of the stored string.
private data class DrawerGroup(
    val header: String,
    @StringRes val headerRes: Int,
    val items: List<Destination>,
    val defaultExpanded: Boolean,
)

// Mirrors the iOS RootTabView `moreTab` grouping + order one-for-one. Today / Trends / Sleep are NOT
// listed (they're bottom-bar tabs, exactly as on iOS). Android-only screens (Vital Signs, Wake Window,
// Notifications, Devices) are slotted into the matching iOS group.
private val drawerGroups: List<DrawerGroup> = listOf(
    DrawerGroup("Insights", R.string.more_group_insights, listOf(
        Destination.InsightsHub, Destination.Intelligence, Destination.Coach,
        Destination.Insights, Destination.Explore, Destination.Compare,
    ), defaultExpanded = true),
    DrawerGroup("Body", R.string.more_group_body, listOf(
        Destination.Live, Destination.Workouts, Destination.Health, Destination.VitalSigns,
        Destination.LabBook, Destination.Stress, Destination.Breathe, Destination.WimHof,
        Destination.Intervals, Destination.Rhythm,
    ), defaultExpanded = true),
    DrawerGroup("Data", R.string.more_group_data, listOf(
        Destination.FusedRecord, Destination.AppleHealth, Destination.DataSources,
        Destination.BackupSync, Destination.Devices,
    ), defaultExpanded = false),
    DrawerGroup("App", R.string.more_group_app, listOf(
        Destination.Automations, Destination.SmartAlarm, Destination.Notifications,
        Destination.TestCentre, Destination.Settings, Destination.Support,
    ), defaultExpanded = false),
)

/** The headers open by default at first run, derived from [drawerGroups.defaultExpanded] (Insights +
 *  Body), so the seed lives in one place and the persistence default can't drift from the UI default. */
private fun defaultExpandedHeaders(): Set<String> =
    drawerGroups.filter { it.defaultExpanded }.map { it.header }.toSet()

/**
 * Persisted open/closed state of the More page's collapsible groups (#860 item 2) - the Android twin of
 * the iOS `MoreSectionPrefs`. The set of EXPANDED group headers is stored as one sorted comma-joined
 * string under a single SharedPreferences key, encoded identically to iOS (same `more.expandedSections`
 * suffix, same CSV-of-headers, same Insights+Body default) so the two platforms behave the same. An empty
 * stored string is a valid state (everything collapsed), distinct from "never set" (which yields the seed).
 */
internal object MoreSectionPrefs {
    const val KEY = "noop.more.expandedSections"

    /** Read the expanded-header set; returns [default] when the key was never written (first run). */
    fun read(prefs: android.content.SharedPreferences, default: Set<String>): Set<String> {
        val raw = prefs.getString(KEY, null) ?: return default
        return decode(raw)
    }

    /** Persist the expanded-header set as a sorted, comma-joined string. */
    fun write(prefs: android.content.SharedPreferences, headers: Set<String>) {
        prefs.edit().putString(KEY, encode(headers)).apply()
    }

    /** Encode the set of expanded headers to a sorted, comma-joined string. */
    fun encode(headers: Set<String>): String = headers.sorted().joinToString(",")

    /** Decode the stored string to a set of expanded headers; blank tokens dropped, empty string -> empty set. */
    fun decode(raw: String): Set<String> =
        raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

/**
 * App shell: a single [Scaffold] with a floating [GlassBottomBar] (Today · Trends · Sleep · More)
 * driving one [NavHost], mirroring the iOS RootTabView. There is NO global toolbar and no nav drawer
 * — every screen self-titles via [ScreenScaffold], and the "More" sheet (opened from the bar) reaches
 * every destination in [drawerGroups], so nothing is lost. A single [AppViewModel] is created here and
 * shared with every screen, so the BLE connection and cached metrics stay app-wide singletons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    viewModel: AppViewModel = viewModel(),
    deepLink: MutableState<String?>? = null,
) {
    val nav = rememberNavController()

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val current = Destination.forRoute(currentRoute)

    // Home-screen launcher shortcut: MainActivity stages the requested route here (see ShortcutRoutes);
    // jump to it once, then clear the flag so a recomposition / config change can't re-fire the jump.
    // Uses the same top-level nav as the bottom bar, so the target reads as a normal tab switch.
    LaunchedEffect(deepLink?.value) {
        val dl = deepLink ?: return@LaunchedEffect
        val route = dl.value ?: return@LaunchedEffect
        if (route != currentRoute) nav.navigateTopLevel(route)
        dl.value = null
    }
    var showQuickActions by remember { mutableStateOf(false) }
    // The Updates inbox sheet (opened by the Today header bell). The store is a process singleton so
    // the Today cards and the import path post to the same inbox this sheet renders.
    val context = androidx.compose.ui.platform.LocalContext.current
    val updateStore = remember { UpdateStore.from(context) }
    var showUpdatesInbox by remember { mutableStateOf(false) }
    // The full changelog sheet, reachable from a release-note row in the inbox (and, independently,
    // from Settings → About → What's new — both render the same [WhatsNewSheet]).
    var showWhatsNew by remember { mutableStateOf(false) }

    // LIST-DETAIL (foldables / tablets, AdaptiveLayout.kt): on a wide window the More list stays on the left
    // while the screen it opened fills the right, the way Material's list-detail layout works. Shown for More
    // and every screen reached through it; Today / Trends / Sleep keep the whole width. When Settings is open
    // beside it, the list also shows Settings' groups and the screen shows just the picked one.
    val windowWidthDp = LocalConfiguration.current.screenWidthDp
    // SIDE RAIL (Material's navigation rail): on a wide window the four tabs move from the floating bottom bar
    // to a glass rail on the leading edge, unless Settings → Appearance asks to keep the bar. Its width comes
    // off everything beside it, so the list pane and the screens count their space from what is left.
    val useRail = AdaptiveLayout.usesNavRail(windowWidthDp, WideLayoutPrefs.navRail)
    val railWidthDp = if (useRail) AdaptiveLayout.RAIL_WIDTH_DP else 0
    val listDetail = AdaptiveLayout.usesListDetail(windowWidthDp - railWidthDp)
    // SHORTCUTS: a wide window's navigation (rail or bar) carries Today, the user's pinned screens, then More;
    // a phone keeps Today · Trends · Sleep · More.
    val wideNav = AdaptiveLayout.usesWideNav(windowWidthDp)
    val wideTabs = wideNavTabs(WideLayoutPrefs.navShortcuts, NoopPrefs.showSupport(context))
    val pinnedRoutes = if (wideNav) wideTabs.map { it.dest.route }.toSet() else emptySet()
    var showNavShortcuts by remember { mutableStateOf(false) }
    val navOptions: @Composable () -> Unit = {
        NavOptionsButton(
            railMode = useRail,
            onToggleMode = { WideLayoutPrefs.setNavRail(context, !WideLayoutPrefs.navRail) },
            onChooseShortcuts = { showNavShortcuts = true },
        )
    }
    val inMoreGroup = drawerGroups.any { current in it.items }
    // A screen pinned in the navigation is a top-level destination of its own there, so it fills the width
    // rather than opening beside the More list.
    val showMorePane = listDetail &&
        (current == Destination.More || (inMoreGroup && current.route !in pinnedRoutes))
    // Standing half-open like a book (FoldPosture.kt), the list fills the left half up to the fold.
    val fold = LocalFoldState.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val paneWidthDp = AdaptiveLayout.listPaneWidthDp(
        if (fold.posture == FoldPosture.BOOK) {
            with(density) { fold.hingeLeftPx.toDp().value.toInt() } - railWidthDp
        } else {
            null
        },
    )
    var settingsFocus by rememberSaveable { mutableStateOf(settingsGroups.first().key) }
    // Remember the last screen opened through More, so tapping More on a wide screen reopens it beside the
    // list instead of showing the list twice (once in the pane, once as the page).
    LaunchedEffect(current) {
        if (inMoreGroup && current.route !in pinnedRoutes) MoreLastRoute.write(context, current.route)
    }
    LaunchedEffect(current, listDetail) {
        if (listDetail && current == Destination.More) nav.navigateTopLevel(MoreLastRoute.read(context, pinnedRoutes))
    }

    run {
        Scaffold(
            containerColor = Palette.surfaceBase,
            bottomBar = {
                // One unified "glass" bar: four evenly-spaced tabs — Today · Trends · Sleep · More
                // (matches the iOS FloatingTabBar). The quick-action "+" lives in the Today header's
                // top-right (balancing the avatar), so the bar is clean tabs only. "More" navigates to
                // its own page (mirroring the iOS More tab) that reaches every grouped destination, so no
                // destination is lost without the drawer. On a wide window the same tabs live in the side
                // rail instead (below), so the bar steps aside.
                if (!useRail) {
                    GlassBottomBar(
                        current = current,
                        // A wide bar carries the shortcuts that fit its width; a phone keeps its four tabs.
                        tabs = if (wideNav) {
                            AdaptiveLayout.fitNavTabs(wideTabs, AdaptiveLayout.barSlotCapacity(windowWidthDp.toFloat()))
                        } else {
                            phoneNavTabs
                        },
                        onTabSelected = { dest ->
                            if (dest.route != currentRoute) nav.navigateTopLevel(dest.route)
                        },
                        options = if (wideNav) navOptions else null,
                    )
                }
            },
        ) { inner ->
            // Shell body. On the PREVIEW channel a fixed build band ([PreviewChannelBand]) is pinned to
            // the very top OVER the NavHost — so it stays put while any screen scrolls AND persists across
            // every tab/drill navigation (Settings · Today · Trends · Sleep · …) — and the NavHost gets
            // matching extra top padding so no screen's content hides behind it. Stable renders neither,
            // so stable's layout is byte-for-byte unchanged.
            val isPreviewChannel = BuildConfig.CHANNEL == "preview"
            Box(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .then(if (isPreviewChannel) Modifier.padding(top = PreviewBandContentHeight) else Modifier),
            ) {
            if (useRail) {
                GlassSideRail(
                    current = current,
                    tabs = wideTabs,
                    onTabSelected = { dest ->
                        if (dest.route != currentRoute) nav.navigateTopLevel(dest.route)
                    },
                    options = navOptions,
                    modifier = Modifier
                        .width(AdaptiveLayout.RAIL_WIDTH_DP.dp)
                        .fillMaxHeight(),
                )
            }
            if (showMorePane) {
                MorePane(
                    current = current,
                    settingsFocus = settingsFocus,
                    onNavigate = { route -> if (route != currentRoute) nav.navigateTopLevel(route) },
                    onSettingsGroup = { key ->
                        settingsFocus = key
                        if (current != Destination.Settings) nav.navigateTopLevel(Destination.Settings.route)
                    },
                    modifier = Modifier
                        .width(paneWidthDp.dp)
                        .fillMaxHeight(),
                )
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(Palette.hairline),
                )
            }
            // The screen beside the pane counts its columns from the width it really gets (AdaptiveLayout.kt),
            // and Settings shows just the group picked in the pane. Always provided (null = whole window) so
            // the NavHost keeps its place in the composition when the pane comes and goes.
            CompositionLocalProvider(
                LocalAvailableWidthDp provides
                    if (useRail || showMorePane) {
                        windowWidthDp - railWidthDp - (if (showMorePane) paneWidthDp + 1 else 0)
                    } else {
                        null
                    },
                LocalSettingsFocus provides if (showMorePane) settingsFocus else null,
            ) {
            NavHost(
                navController = nav,
                startDestination = Destination.Today.route,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                // README motion: top-level destinations crossfade (~240ms) on the calm,
                // decelerating global easing — nothing slides or bounces between tabs. The
                // same fade is used for back (pop) so the bar never feels jerky. Drill-ins
                // (e.g. vital_detail) are pushed by the same NavHost, so they inherit the
                // same restrained crossfade rather than a hard cut.
                enterTransition = { fadeIn(navFadeSpec) },
                exitTransition = { fadeOut(navFadeSpec) },
                popEnterTransition = { fadeIn(navFadeSpec) },
                popExitTransition = { fadeOut(navFadeSpec) },
            ) {
                // --- Live, working screens (existing waves) ---
                composable(Destination.Today.route) {
                    TodayScreen(
                        viewModel = viewModel,
                        onSupport = { nav.navigateTopLevel(Destination.Support.route) },
                        // The quick-action "+" lives in the Today header's top-right now (off the
                        // bottom bar) — it opens the same quick-action sheet the bar used to.
                        onQuickActions = { showQuickActions = true },
                        // The Updates "ringer" — the bell sits between the Support heart and the +,
                        // and opens the inbox sheet AppRoot presents (it owns the nav for deep-links).
                        updateStore = updateStore,
                        onOpenUpdates = { showUpdatesInbox = true },
                        // The leading avatar now opens the Profile page (the "account" surface); Settings is
                        // reached from a gear there, and still lives in More → App. onOpenSettings stays for
                        // the other Today entry points (e.g. the battery ring's Devices default).
                        onOpenProfile = { nav.navigateTopLevel(Destination.Profile.route) },
                        onOpenSettings = { nav.navigateTopLevel(Destination.Settings.route) },
                        // The opt-in Hydration card (only shown when Hydration tracking is on) pushes its
                        // detail. A normal push so the back-stack returns to Today.
                        onOpenHydration = { nav.navigate(Destination.Hydration.route) },
                        // #706/#684: the dashboard cards draw a tappable chevron; wire each to its detail,
                        // matching iOS. Stress + the vitals are pushes; Sleep is a top-level tab switch.
                        onOpenStress = { nav.navigate(Destination.Stress.route) },
                        onOpenHealth = { nav.navigate(Destination.Health.route) },
                        // Every metric/vital card opens its OWN focused detail trend (vital_detail/<key>),
                        // not the shared Health hub (2026-07-03). Mirrors the iOS liquidCard metricDetail.
                        onOpenMetric = { key -> nav.navigate("vital_detail/$key") },
                        onOpenSleep = { nav.navigateTopLevel(Destination.Sleep.route) },
                        // Optional Coupled view card (task #43): a normal push so back returns to Today.
                        onOpenCoupled = { nav.navigate(Destination.CoupledView.route) },
                        // The "workout in progress" indicator: raise the one-shot the Live screen consumes to
                        // re-open the in-exercise overlay, then route to Live. One tap from Today (iOS parity).
                        onOpenActiveWorkout = {
                            viewModel.openActiveWorkout()
                            nav.navigate(Destination.Live.route)
                        },
                        // The liquid header's strap battery ring taps through to Devices (iOS parity: the
                        // battery ring → router.openDevices()).
                        onOpenDevices = { nav.navigateTopLevel(Destination.Devices.route) },
                    )
                }
                composable(Destination.Live.route) {
                    LiveScreen(
                        viewModel = viewModel,
                        onManageDevices = { nav.navigateTopLevel(Destination.Devices.route) },
                    )
                }
                composable(Destination.Sleep.route) {
                    SleepScreen(
                        vm = viewModel,
                        onOpenJournal = { nav.navigateTopLevel(Destination.Insights.route) },
                    )
                }
                composable(Destination.CoupledView.route) {
                    CoupledScreen(
                        vm = viewModel,
                        // Tapping Sleep in the coupled read opens the full Sleep screen (iOS parity).
                        onOpenSleep = { nav.navigateTopLevel(Destination.Sleep.route) },
                    )
                }
                composable(Destination.Intervals.route) { IntervalsScreen(viewModel) }
                composable(Destination.Breathe.route) {
                    BreatheScreen(
                        viewModel,
                        onOpenWimHof = { nav.navigateTopLevel(Destination.WimHof.route) },
                    )
                }
                composable(Destination.WimHof.route) {
                    WimHofScreen(
                        viewModel,
                        // The journal-item pickers live in Settings, so both the pre-session status card
                        // and the post-session summary can hop straight there.
                        onOpenSettings = {
                            // On a wide screen, land on the Wim Hof group beside the list.
                            settingsFocus = "wimhof"
                            nav.navigateTopLevel(Destination.Settings.route)
                        },
                    )
                }
                composable(Destination.Coach.route) { CoachScreen() }
                composable(Destination.Explore.route) { TrendsExploreScreen(viewModel) }
                composable(Destination.Automations.route) { AutomationsScreen(viewModel) }
                composable(Destination.SmartAlarm.route) { SmartAlarmScreen(viewModel) }
                composable(Destination.Workouts.route) { WorkoutsScreen(viewModel) }
                composable(Destination.Support.route) { SupportScreen() }
                composable(Destination.Intelligence.route) { IntelligenceScreen(viewModel) }

                // --- Placeholder routes (later waves fill these in) ---
                composable(Destination.Stress.route) {
                    StressScreen(
                        vm = viewModel,
                        onBreathe = { nav.navigateTopLevel(Destination.Breathe.route) },
                    )
                }
                composable(Destination.Trends.route) { TrendsScreen(viewModel) }
                composable(Destination.Insights.route) { InsightsScreen(viewModel, onOpenInsightsHub = { nav.navigateTopLevel(Destination.InsightsHub.route) }) }
                composable(Destination.Compare.route) { CompareScreen(viewModel) }
                composable(Destination.Health.route) {
                    HealthScreen(
                        vm = viewModel,
                        onVitalClick = { nav.navigate("vital_detail/$it") },
                        onOpenLabBook = { nav.navigateTopLevel(Destination.LabBook.route) },
                        onOpenFusedRecord = { nav.navigateTopLevel(Destination.FusedRecord.route) },
                    )
                }
                composable(Destination.Hydration.route) { HydrationScreen(viewModel) }
                composable(Destination.VitalSigns.route) {
                    VitalSignsScreen(
                        vm = viewModel,
                        onVitalClick = { nav.navigate("vital_detail/$it") },
                    )
                }
                composable(Destination.VitalSignsDetail.route) { backStackEntry ->
                    VitalDetailScreen(
                        vm = viewModel,
                        key = backStackEntry.arguments?.getString("key").orEmpty(),
                    )
                }
                // --- v5 pillar screens (Wave 3 wiring) ---
                composable(Destination.InsightsHub.route) { InsightsHubScreen(viewModel) }
                composable(Destination.LabBook.route) { LabBookScreen(viewModel) }
                composable(Destination.Rhythm.route) {
                    // EXPERIMENTAL: self-gates on its own consent clickwrap (default OFF). The night
                    // summary + per-window Poincaré results land with the rhythm capture pipeline; until
                    // then it renders its honest "no clear reading yet" empty state behind the gate.
                    RhythmScreen(night = null, windows = emptyList())
                }
                composable(Destination.FusedRecord.route) { FusedRecordRoute(viewModel) }
                composable(Destination.AppleHealth.route) { AppleHealthScreen(viewModel) }
                composable(Destination.Devices.route) {
                    DevicesScreen(
                        viewModel,
                        onUseFileImport = { nav.navigateTopLevel(Destination.DataSources.route) },
                    )
                }
                composable(Destination.DataSources.route) { DataSourcesScreen(viewModel) }
                composable(Destination.BackupSync.route) { BackupSyncScreen() }
                composable(Destination.Notifications.route) { NotificationsSettingsScreen(viewModel) }
                composable(Destination.Settings.route) {
                    SettingsScreen(viewModel, onOpenTestCentre = { nav.navigate(Destination.TestCentre.route) })
                }
                composable(Destination.Profile.route) {
                    ProfileScreen(viewModel, onOpenSettings = { nav.navigateTopLevel(Destination.Settings.route) })
                }
                composable(Destination.TestCentre.route) { TestCentreScreen(viewModel) }
                // The "More" page — the iOS More tab's twin: a navigated ScreenScaffold page hosting the
                // full grouped destination list (was a pull-up sheet). A row navigates top-level.
                composable(Destination.More.route) {
                    MoreScreen(onNavigate = { nav.navigateTopLevel(it) })
                }
            }
            } // CompositionLocalProvider
            } // Row (list pane + NavHost)
            if (isPreviewChannel) {
                PreviewChannelBand(modifier = Modifier.align(Alignment.TopCenter))
            }
            // Long-running actions (import · export · full Charge rescore) report HERE, above the
            // NavHost, for the same reason the preview band does: it has to survive every navigation.
            // A Toast in Settings vanished in seconds and only existed while that screen did, so an
            // action you walked away from left no trace at all.
            BackgroundActionBanners(
                viewModel = viewModel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(inner),
            )
            }
        }

        // Quick-actions sheet, opened by the raised gold centre FAB. Each row routes to an
        // existing destination — nothing new is built here, the FAB is just a faster door in.
        if (showQuickActions) {
            ModalBottomSheet(
                onDismissRequest = { showQuickActions = false },
                containerColor = Palette.surfaceRaised,
                contentColor = Palette.textPrimary,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 24.dp),
                ) {
                    Overline(
                        "Quick actions",
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 6.dp),
                        color = Palette.textTertiary,
                    )
                    // Updates inbox — relocated here off the Today header (the liquid Today header mirrors iOS,
                    // which has no notifications bell). The feature is fully intact and one tap away: this row
                    // opens the same inbox sheet, showing the unread count as a trailing badge.
                    NavigationDrawerItem(
                        selected = false,
                        onClick = {
                            showQuickActions = false
                            showUpdatesInbox = true
                        },
                        icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                        label = { Text("Updates", style = NoopType.body) },
                        badge = {
                            val unread = updateStore.unreadCount
                            if (unread > 0) {
                                Text(
                                    if (unread > 99) "99+" else unread.toString(),
                                    style = NoopType.captionNumber,
                                    color = Palette.statusCritical,
                                )
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Palette.surfaceRaised,
                            unselectedIconColor = Palette.accent,
                            unselectedTextColor = Palette.textPrimary,
                        ),
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    )
                    quickActions.forEach { action ->
                        NavigationDrawerItem(
                            selected = false,
                            onClick = {
                                showQuickActions = false
                                if (action.route != currentRoute) {
                                    nav.navigateTopLevel(action.route)
                                }
                            },
                            icon = { Icon(action.icon, contentDescription = null) },
                            label = { Text(stringResource(action.titleRes), style = NoopType.body) },
                            colors = NavigationDrawerItemDefaults.colors(
                                unselectedContainerColor = Palette.surfaceRaised,
                                unselectedIconColor = Palette.accent,
                                unselectedTextColor = Palette.textPrimary,
                            ),
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                        )
                    }
                }
            }
        }

        // The Updates inbox (opened by the Today header bell). Presented here so it has the nav for
        // deep-links — a row's "trends" key switches the bottom tab, mirroring the iOS NavRouter route.
        if (showUpdatesInbox) {
            ModalBottomSheet(
                onDismissRequest = { showUpdatesInbox = false },
                // Open full-height (no half-pull) so it reads like the iOS Updates sheet, and use the
                // BEIGE surfaceBase so the white NoopCards POP — surfaceRaised made white cards sit on a
                // white sheet (no contrast), which is why the Android inbox looked flat vs iOS.
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Palette.surfaceBase,
                contentColor = Palette.textPrimary,
            ) {
                UpdatesInboxScreen(
                    store = updateStore,
                    onClose = { showUpdatesInbox = false },
                    onDeepLink = { key ->
                        // Map the inbox deep-link key to a destination (only known keys resolve;
                        // unknown ones just close the sheet). "whatsNew" opens the changelog sheet
                        // in place rather than navigating — it's the target of every release-note row.
                        if (key == UpdateStore.DEEP_LINK_WHATS_NEW) {
                            showWhatsNew = true
                        } else {
                            val route = when (key) {
                                "trends" -> Destination.Trends.route
                                else -> null
                            }
                            if (route != null && route != currentRoute) nav.navigateTopLevel(route)
                        }
                    },
                    onRestore = { cardId ->
                        // Flip the shared dismissed flag back off so the card reappears, and signal a
                        // mounted Today to re-read it immediately (SharedPreferences isn't reactive).
                        TodayCardDismissal.setDismissed(context, cardId, false)
                        updateStore.restoreRequest = cardId
                    },
                )
            }
        }

        // The navigation shortcut editor, opened from the rail's / wide bar's options button.
        if (showNavShortcuts) {
            NavShortcutsEditorDialog(onDismiss = { showNavShortcuts = false })
        }

        // The full changelog, opened by tapping a release-note row in the inbox. Same full-screen
        // Dialog idiom Settings uses for its What's new / scoring-guide sheets.
        if (showWhatsNew) {
            Dialog(
                onDismissRequest = { showWhatsNew = false },
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Palette.surfaceBase) {
                    WhatsNewSheet(onClose = { showWhatsNew = false })
                }
            }
        }
    }
}

// MARK: - Background action banners
//
// The in-app half of the long-running-action feedback (the other half is [ActionProgressNotifier]).
// Deliberately the RELIABLE half: it needs no notification permission, so even with notifications
// denied there is always somewhere to see that an import/export/rescore is running and how it ended.
//
// Sticky by design. A finished action stays until the user taps the ✕ — that is the fix for "I
// triggered something and never found out whether it worked". Running actions have no ✕: there is
// nothing to acknowledge yet, and dismissing one would not stop the work.
//
// Sits at the BOTTOM (above the tab bar) rather than the top: the top strip already belongs to the
// preview build band, and a bottom banner is where Android users look for transient status anyway.

/** Every running action plus every finished-but-undismissed one, newest last. Renders nothing when empty. */
@Composable
private fun BackgroundActionBanners(viewModel: AppViewModel, modifier: Modifier = Modifier) {
    val actions by viewModel.backgroundActions.collectAsStateWithLifecycle()
    if (actions.isEmpty()) return
    Column(
        modifier = modifier
            // On a wide window (unfolded foldable, tablet) keep the banners a centred card rather than a strip
            // across the whole screen; a phone is narrower than the cap, so it is unchanged there.
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (action in actions) {
            BackgroundActionBanner(action) { viewModel.dismissAction(action.id) }
        }
    }
}

@Composable
private fun BackgroundActionBanner(
    action: AppViewModel.BackgroundAction,
    onDismiss: () -> Unit,
) {
    // Colour carries the outcome at a glance: accent while working, still-accent on success, the
    // warning tint on failure. Never red-on-red — a failed export is a "try again", not an alarm.
    val tint = when {
        action.running -> Palette.accent
        action.ok -> Palette.accent
        else -> Palette.statusWarning
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Palette.surfaceRaised.copy(alpha = 0.96f),
        contentColor = Palette.textPrimary,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (action.running) {
                CircularProgressIndicator(color = tint, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(
                    imageVector = if (action.ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(action.label, style = NoopType.subhead, color = Palette.textPrimary)
                val detail = action.detail
                    ?: if (action.running) "Running… you can keep using Choop." else null
                if (detail != null) {
                    Text(detail, style = NoopType.footnote, color = Palette.textSecondary)
                }
            }
            // No dismiss while running: there is nothing to acknowledge yet, and hiding the banner
            // would not stop the work — it would just recreate the "did anything happen?" problem.
            if (!action.running) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss",
                        tint = Palette.textTertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

// MARK: - Preview channel build band
//
// A slim, always-on build band shown ONLY on the preview channel (BuildConfig.CHANNEL == "preview").
// It carries the same channel-aware string as the Today build stamp ([buildStamp]) — version · build ·
// branch@sha — but lives in [AppRoot] ABOVE the NavHost, so it stays fixed while a screen scrolls and
// persists across every tab/drill navigation (Settings, Trends, Sleep, …). Stable never renders it, so
// stable's layout is byte-for-byte unchanged. Edge/Chrome-Canary-style channel chrome: a diagnostic
// ribbon, deliberately NOT a title bar — no wordmark, just the build identity for a bug report.

/** Height of the band's CONTENT strip (excludes the status-bar inset the band also fills). The NavHost
 *  reserves this as extra top padding on the preview channel so no screen hides behind the fixed band. */
private val PreviewBandContentHeight: Dp = 30.dp

@Composable
private fun PreviewChannelBand(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        // Match the glass bar's language: a translucent raised surface with a hairline edge, so the band
        // reads as the same floating-chrome family — here pinned to the top instead of the bottom.
        color = Palette.surfaceRaised.copy(alpha = 0.94f),
        contentColor = Palette.textSecondary,
        shadowElevation = 3.dp,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Fill + clear the status bar so the band reads as a coloured top strip with its text
                    // below the system clock (edge-to-edge: the activity draws under the status bar).
                    .statusBarsPadding()
                    .height(PreviewBandContentHeight)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Preview · " + buildStamp(
                        channel = BuildConfig.CHANNEL,
                        versionName = BuildConfig.VERSION_NAME,
                        versionCode = BuildConfig.VERSION_CODE,
                        branch = BuildConfig.GIT_BRANCH,
                        sha = BuildConfig.GIT_SHA,
                    ),
                    style = NoopType.number(9.5f),
                    color = Palette.textSecondary,
                    // Two lines: a branch preview's "… · <branch>@<sha>" easily outgrows one line; the
                    // " · " separators are natural wrap points so the source lands on a centred second
                    // line rather than being clipped. Ellipsis only for pathological lengths.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Preview build" },
                )
            }
            HorizontalDivider(color = Palette.hairline)
        }
    }
}

// MARK: - More page
//
// The "More" tab's destination — a full navigated page (mirroring the iOS More tab's NavigationStack
// List), replacing the old pull-up ModalBottomSheet. It hosts the SAME grouped destinations
// ([drawerGroups]) inside a [ScreenScaffold], with the exact section-header + row styling the sheet
// used (uppercase [Overline] group labels, icon + label [NavigationDrawerItem] rows) — now with a
// trailing chevron so each row reads as a navigation push, matching the iOS disclosure rows. Tapping a
// row navigates top-level; there is no sheet to dismiss. The floating bottom bar stays visible because
// this is just another NavHost destination under the same Scaffold.

/** The full grouped destination list as a navigated page (the iOS More tab's twin). */
@Composable
private fun MoreScreen(onNavigate: (String) -> Unit) {
    ScreenScaffold(
        title = "More",
        subtitle = "Everything else, one tap away",
    ) {
        MoreGroups(selected = null, settingsFocus = null, onNavigate = onNavigate, onSettingsGroup = null)
    }
}

/**
 * The More list as a pane beside the screen it opened (list-detail on a wide window, see AppRoot). Same
 * groups and rows as the More page, with the open screen highlighted; when that screen is Settings, its
 * groups appear under it so the pane drives Settings one level down too. Scrolls on its own, so the list
 * stays put while the screen beside it scrolls.
 */
@Composable
private fun MorePane(
    current: Destination,
    settingsFocus: String,
    onNavigate: (String) -> Unit,
    onSettingsGroup: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(Palette.surfaceBase)
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 12.dp, top = 20.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(Metrics.screenRowSpacing),
    ) {
        Text("More", style = NoopType.title1, color = Palette.textPrimary)
        MoreGroups(
            selected = current,
            settingsFocus = if (current == Destination.Settings) settingsFocus else null,
            onNavigate = onNavigate,
            onSettingsGroup = onSettingsGroup,
        )
    }
}

/** The grouped destination list shared by the More page and the More pane. [selected] highlights the open
 *  screen (pane only) and keeps its group open; [settingsFocus] non-null lists Settings' groups under the
 *  Settings row with that one highlighted. Each group is its own child, so the More page's groups flow into
 *  columns on a wide window. */
@Composable
private fun MoreGroups(
    selected: Destination?,
    settingsFocus: String?,
    onNavigate: (String) -> Unit,
    onSettingsGroup: ((String) -> Unit)?,
) {
    // S2 parity: each group's open/closed state, seeded from `defaultExpanded` (Insights + Body open,
    // Data + App collapsed). PERSISTED (#860 item 2): the user's open/closed choice must survive leaving
    // and re-entering the More page (and relaunch), not reset to the seed every visit. Backed by
    // [MoreSectionPrefs] (a CSV of expanded headers in SharedPreferences), mirroring the iOS
    // @AppStorage("more.expandedSections"). Seeded ONCE from the stored value so first run still shows the
    // Insights+Body default; every toggle writes through so the next visit reflects the saved state.
    val context = androidx.compose.ui.platform.LocalContext.current
    // Support/donation off (Settings → Appearance) drops the More → Support entry too, so nothing points at
    // the orphaned Support page. Read fresh each composition so a toggle in Settings shows on return here.
    val showSupport = NoopPrefs.showSupport(context)
    val expanded = remember {
        val stored = MoreSectionPrefs.read(NoopPrefs.of(context), defaultExpandedHeaders())
        androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply {
            drawerGroups.forEach { put(it.header, stored.contains(it.header)) }
        }
    }
    // Mirror the iOS More page: each group is a tappable UPPERCASE overline header (with a disclosure
    // chevron) over a single grouped white NoopCard whose rows are tight (accent icon + title +
    // chevron) and separated by inset hairlines (NOT loose NavigationDrawerItems on the bare surface).
    drawerGroups.forEach { group ->
        // The group holding the open screen always shows, so its highlighted row is never hidden.
        val isOpen = (expanded[group.header] ?: group.defaultExpanded) || (selected != null && selected in group.items)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MoreGroupHeader(
                title = stringResource(group.headerRes),
                expanded = isOpen,
                onToggle = {
                    expanded[group.header] = !isOpen
                    // Persist the new open set so the choice survives leaving + re-entering the page
                    // and relaunch (#860 item 2), mirroring the iOS @AppStorage write.
                    val open = drawerGroups.map { it.header }.filter { expanded[it] == true }.toSet()
                    MoreSectionPrefs.write(NoopPrefs.of(context), open)
                },
            )
            if (isOpen) {
                val items = group.items.filter { showSupport || it != Destination.Support }
                NoopCard(padding = 0.dp) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        items.forEachIndexed { i, dest ->
                            MoreRow(dest = dest, selected = dest == selected, onClick = { onNavigate(dest.route) })
                            if (dest == Destination.Settings && settingsFocus != null && onSettingsGroup != null) {
                                settingsGroups.forEach { info ->
                                    MoreSubRow(
                                        title = info.title,
                                        icon = info.icon,
                                        selected = info.key == settingsFocus,
                                        onClick = { onSettingsGroup(info.key) },
                                    )
                                }
                            }
                            if (i < items.lastIndex) {
                                HorizontalDivider(
                                    color = Palette.hairline,
                                    modifier = Modifier.padding(start = 50.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The last screen opened through More, so More on a wide screen reopens it beside the list. */
internal object MoreLastRoute {
    private const val KEY = "noop.more.lastRoute"

    fun read(context: android.content.Context, pinned: Set<String> = emptySet()): String {
        val stored = NoopPrefs.of(context).getString(KEY, null)
        // Only a route that is still in the More groups (Support can be switched off) and not pinned in the
        // navigation (that one opens full width, without the list); else the first such entry.
        val valid = drawerGroups.flatMap { it.items }.map { it.route }.filter { it !in pinned }
        return stored?.takeIf { it in valid } ?: valid.firstOrNull() ?: Destination.InsightsHub.route
    }

    fun write(context: android.content.Context, route: String) {
        NoopPrefs.of(context).edit().putString(KEY, route).apply()
    }
}

/** A tappable group header for the More page (S2): the same UPPERCASE [Overline] label as before, now
 *  with a trailing chevron that rotates between open (0deg) and closed (-90deg), mirroring the iOS
 *  collapsible More sections. Tapping toggles the group; the whole row is the tap target. */
@Composable
private fun MoreGroupHeader(title: String, expanded: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        animationSpec = tween(durationMillis = 240, easing = NavEasing),
        label = "moreGroupChevron",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .semantics {
                contentDescription = title
                stateDescription = if (expanded) "Expanded" else "Collapsed"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Overline(title, modifier = Modifier.weight(1f), color = Palette.textTertiary)
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier
                .size(Metrics.iconSmall)
                .rotate(rotation),
        )
    }
}

/** One tappable destination row in the More page — accent icon + title + trailing chevron in a
 *  comfortable tap target, mirroring the iOS MoreRow. */
@Composable
private fun MoreRow(dest: Destination, selected: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The open screen beside the More pane reads as selected: a soft accent wash, accent title.
            .then(if (selected) Modifier.background(Palette.accent.copy(alpha = 0.12f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { if (selected) stateDescription = "Selected" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(dest.icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            stringResource(dest.titleRes),
            style = if (selected) NoopType.body.copy(fontWeight = FontWeight.SemiBold) else NoopType.body,
            color = if (selected) Palette.accent else Palette.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = Palette.textTertiary,
            modifier = Modifier.size(Metrics.iconSmall),
        )
    }
}

/** One Settings group listed under the Settings row in the More pane (list-detail one level down). */
@Composable
private fun MoreSubRow(title: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(Palette.accent.copy(alpha = 0.12f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(start = 50.dp, end = 16.dp, top = 10.dp, bottom = 10.dp)
            .semantics { if (selected) stateDescription = "Selected" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.textSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            style = if (selected) NoopType.subhead.copy(fontWeight = FontWeight.SemiBold) else NoopType.subhead,
            color = if (selected) Palette.accent else Palette.textSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

// MARK: - Glass bottom bar
//
// The signature bar, ported from iOS's FloatingTabBar: ONE rounded "glass" island holding four
// evenly-spaced inline slots — Today · Trends · Sleep · More. The quick-action "+" now lives in the
// Today header's top-right (it left the bar to balance the avatar), so the bar is clean tabs only.
// The "glass" feel is a translucent raised surface with a low elevation and a subtle hairline border
// — frosted, not a hard opaque slab and not a glow. Each nav slot is an icon over a small label;
// active = gold accent, inactive = textSecondary. All routing is unchanged: the four tabs switch the
// same destinations.

/** A single navigation slot (bottom bar or side rail): the destination it switches to, plus its icon/label. */
private data class BarTab(val dest: Destination, val icon: ImageVector, @StringRes val labelRes: Int)

/** The nav slots in iOS order: Today · Trends · Sleep · More.
 *  More is special-cased (it opens the sheet rather than a route), so it is appended at the call site. */
private val barLeadingTabs = listOf(
    BarTab(Destination.Today, Icons.Outlined.GridView, R.string.nav_today),
    // chart.line.uptrend.xyaxis on iOS — the rising-trend glyph, not a flat bar chart.
    BarTab(Destination.Trends, Icons.AutoMirrored.Filled.TrendingUp, R.string.nav_trends),
)
private val barTrailingTabs = listOf(
    BarTab(Destination.Sleep, Icons.Filled.Bedtime, R.string.nav_sleep),
)

/** The phone bar's tabs, Today · Trends · Sleep (More appended by the bar). Phones always use exactly these. */
private val phoneNavTabs: List<BarTab> = barLeadingTabs + barTrailingTabs

/** The slot for any destination: the bar's own glyph/label for Today, Trends and Sleep, else the screen's. */
private fun navTabFor(dest: Destination): BarTab =
    phoneNavTabs.firstOrNull { it.dest == dest } ?: BarTab(dest, dest.icon, dest.titleRes)

// MARK: - Wide-screen navigation shortcuts
//
// A phone bar has room for four tabs; an unfolded foldable or a tablet has room for many more, down the rail or
// along a wider bar. So on a wide window the navigation shows Today, then the user's own SHORTCUTS (up to
// AdaptiveLayout.MAX_NAV_SHORTCUTS, default Trends · Sleep · Live · Workouts · Health), then More, which still
// reaches everything. Shortcuts that don't fit the space (a half-open phone's lower half, a narrow tablet
// window) simply drop off the end and stay one tap away in More. Picked in the shortcut editor, opened from the
// navigation's own options button or Settings → Appearance.

/** Screens that can be pinned: Trends, Sleep and every More entry (Today and More are always there). */
private val navShortcutCandidates: List<Destination> =
    (listOf(Destination.Trends, Destination.Sleep) + drawerGroups.flatMap { it.items }).distinct()

/** The wide-window tabs (More appended by the bar/rail): Today, then the stored shortcuts in their order. Unknown
 *  routes are skipped, and Support when the support surfaces are switched off. */
private fun wideNavTabs(routes: List<String>, showSupport: Boolean): List<BarTab> =
    listOf(navTabFor(Destination.Today)) +
        routes.mapNotNull { r -> navShortcutCandidates.firstOrNull { it.route == r } }
            .filter { showSupport || it != Destination.Support }
            .map { navTabFor(it) }

@Composable
private fun GlassBottomBar(
    current: Destination,
    tabs: List<BarTab>,
    onTabSelected: (Destination) -> Unit,
    // Wide windows only: the navigation options button beside the pill (move to the side, pick shortcuts).
    options: (@Composable () -> Unit)? = null,
) {
    val barShape = RoundedCornerShape(50)
    // A phone's four tabs keep the original 480dp cap; a wide window's longer list grows the pill to fit.
    val barMaxWidth = maxOf(480, (tabs.size + 1) * 80 + 16).dp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Clear the gesture-nav bar (home indicator) first, then add breathing room so the capsule
            // floats free of the bottom edge rather than jamming against it — iOS clears the home-indicator
            // safe area + 4pt; here navigationBarsPadding + 12dp gives the same lift.
            .navigationBarsPadding()
            .padding(horizontal = 22.dp)
            .padding(top = 4.dp, bottom = Metrics.space12),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Surface(
            shape = barShape,
            // "Glass": a translucent raised surface — a frosted island, not a hard slab. Compose has no
            // cheap blur, so translucency (≈0.80) + a hairline rim is the Liquid-Glass stand-in. A soft,
            // low drop shadow reads as floating without a glow.
            color = Palette.surfaceRaised.copy(alpha = 0.80f),
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
            modifier = Modifier
                // Cap the width so the pill stays a centred floating island on tablets, not a full-bleed bar.
                .weight(1f, fill = false)
                .widthIn(max = barMaxWidth)
                .border(0.5.dp, Palette.hairline.copy(alpha = 0.6f), barShape),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                tabs.forEach { tab ->
                    BarSlot(
                        icon = tab.icon,
                        label = stringResource(tab.labelRes),
                        active = current == tab.dest,
                        modifier = Modifier.weight(1f),
                        onClick = { onTabSelected(tab.dest) },
                    )
                }
                BarSlot(
                    icon = Icons.Filled.MoreHoriz,
                    label = stringResource(R.string.nav_more),
                    // Selected on the More page itself, and also kept lit whenever the current screen is
                    // one reached THROUGH More (i.e. not one of the bar's own tabs) — so drilling
                    // into any grouped destination still reads as "you're in More", never "nowhere".
                    active = tabs.none { it.dest == current },
                    modifier = Modifier.weight(1f),
                    onClick = { onTabSelected(Destination.More) },
                )
            }
        }
        if (options != null) {
            Spacer(Modifier.width(8.dp))
            options()
        }
        }
    }
}

/**
 * The side navigation rail for wide windows: the same tabs as the wide [GlassBottomBar] — Today, the user's
 * shortcuts, More — stacked in one vertical glass island on the leading edge. Same translucent raised surface,
 * hairline rim and soft shadow as the bar, the same [BarSlot]s and the same "More stays lit for anything reached
 * through More" rule, so switching between the two reads as the bar turning on its side.
 *
 * Vertically centred, EXCEPT half-open on a table (tabletop posture): the fold then runs across the middle of the
 * screen, right through the rail, so the island moves to the LOWER half — below the fold, where the hands are —
 * and only as many shortcuts as fit there show (the rest stay in More). [options] sits under the tabs.
 */
@Composable
private fun GlassSideRail(
    current: Destination,
    tabs: List<BarTab>,
    onTabSelected: (Destination) -> Unit,
    options: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val railShape = RoundedCornerShape(32.dp)
    val fold = LocalFoldState.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    var topPx by remember { androidx.compose.runtime.mutableIntStateOf(-1) }
    var heightPx by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Tabletop: skip everything above the fold (plus a physical hinge's gap, if it has one).
    val skipPx = if (fold.posture == FoldPosture.TABLETOP && heightPx > 0) {
        FoldPostures.topPaneHeightPx(fold, topPx, heightPx) +
            (fold.hingeBottomPx - fold.hingeTopPx).coerceAtLeast(0)
    } else {
        0
    }
    // Before the first measure, show everything; after it, only what fits the rail's (remaining) height.
    val capacity = if (heightPx > 0) {
        AdaptiveLayout.railSlotCapacity(with(density) { (heightPx - skipPx).coerceAtLeast(0).toDp() }.value)
    } else {
        tabs.size + 1
    }
    val shown = AdaptiveLayout.fitNavTabs(tabs, capacity)
    Column(
        modifier = modifier
            .onGloballyPositioned { topPx = it.positionInWindow().y.roundToInt() }
            .onSizeChanged { heightPx = it.height },
    ) {
        if (skipPx > 0) Spacer(Modifier.height(with(density) { skipPx.toDp() }))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = railShape,
                color = Palette.surfaceRaised.copy(alpha = 0.80f),
                tonalElevation = 2.dp,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, Palette.hairline.copy(alpha = 0.6f), railShape),
            ) {
                Column(
                    modifier = Modifier
                        // Scrolls only if the space estimate is ever short, rather than clipping a tab.
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(AdaptiveLayout.RAIL_SLOT_GAP_DP.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    shown.forEach { tab ->
                        BarSlot(
                            icon = tab.icon,
                            label = stringResource(tab.labelRes),
                            active = current == tab.dest,
                            modifier = Modifier.fillMaxWidth().height(AdaptiveLayout.RAIL_SLOT_HEIGHT_DP.dp),
                            onClick = { onTabSelected(tab.dest) },
                        )
                    }
                    BarSlot(
                        icon = Icons.Filled.MoreHoriz,
                        label = stringResource(R.string.nav_more),
                        active = shown.none { it.dest == current },
                        modifier = Modifier.fillMaxWidth().height(AdaptiveLayout.RAIL_SLOT_HEIGHT_DP.dp),
                        onClick = { onTabSelected(Destination.More) },
                    )
                    HorizontalDivider(
                        color = Palette.hairline,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    options()
                }
            }
        }
    }
}

/**
 * The navigation's own options button (wide windows only): a small disc under the rail's tabs, or beside the
 * bottom bar's pill. Its menu flips the navigation between the side rail and the bottom bar on the spot (the
 * same switch as Settings → Appearance → Wide-screen navigation) and opens the shortcut editor.
 */
@Composable
private fun NavOptionsButton(railMode: Boolean, onToggleMode: () -> Unit, onChooseShortcuts: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(Palette.surfaceRaised.copy(alpha = 0.80f))
                .border(0.5.dp, Palette.hairline.copy(alpha = 0.6f), androidx.compose.foundation.shape.CircleShape)
                .clickable { open = true }
                .semantics { contentDescription = "Navigation options" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Tune, contentDescription = null, tint = Palette.textSecondary, modifier = Modifier.size(18.dp))
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = {
                    Text(
                        if (railMode) "Move navigation to the bottom" else "Move navigation to the side",
                        style = NoopType.body,
                        color = Palette.textPrimary,
                    )
                },
                onClick = { open = false; onToggleMode() },
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { Text("Choose shortcuts…", style = NoopType.body, color = Palette.textPrimary) },
                onClick = { open = false; onChooseShortcuts() },
            )
        }
    }
}

/**
 * The shortcut editor: every screen that can be pinned, with a switch to show it in the wide-screen navigation
 * and arrows to order it, like the Today layout editor. Up to [AdaptiveLayout.MAX_NAV_SHORTCUTS]; Today and More
 * are always there. Opened from the navigation's options button and from Settings → Appearance.
 */
@Composable
internal fun NavShortcutsEditorDialog(onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val showSupport = NoopPrefs.showSupport(context)
    val candidates = navShortcutCandidates.filter { showSupport || it != Destination.Support }
    // Pinned ones first in their saved order, then the rest in More's order.
    val items = remember {
        val pinned = WideLayoutPrefs.navShortcuts.mapNotNull { r -> candidates.firstOrNull { it.route == r } }
        androidx.compose.runtime.mutableStateListOf<Pair<Destination, Boolean>>().apply {
            pinned.forEach { add(it to true) }
            candidates.filter { it !in pinned }.forEach { add(it to false) }
        }
    }
    val pinnedCount = items.count { it.second }
    fun move(from: Int, to: Int) {
        if (from in items.indices && to in items.indices) items.add(to, items.removeAt(from))
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Palette.surfaceOverlay, shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Navigation shortcuts", style = NoopType.title2, color = Palette.textPrimary)
                    Text(
                        "When the phone is open or on a tablet, these sit in the navigation between Today and More " +
                            "(up to ${AdaptiveLayout.MAX_NAV_SHORTCUTS}). Order them with the arrows. Anything that " +
                            "doesn't fit the space stays one tap away in More. A folded phone keeps its four tabs.",
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                    )
                }
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    items.forEachIndexed { index, (dest, pinned) ->
                        val title = stringResource(dest.titleRes)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Switch(
                                checked = pinned,
                                onCheckedChange = { items[index] = dest to it },
                                // Full: a new pin waits until one is switched off.
                                enabled = pinned || pinnedCount < AdaptiveLayout.MAX_NAV_SHORTCUTS,
                                colors = androidx.compose.material3.SwitchDefaults.colors(
                                    checkedThumbColor = Palette.surfaceBase,
                                    checkedTrackColor = Palette.accent,
                                    uncheckedThumbColor = Palette.textSecondary,
                                    uncheckedTrackColor = Palette.surfaceInset,
                                    uncheckedBorderColor = Palette.hairline,
                                ),
                                modifier = Modifier.semantics { contentDescription = "Pin $title" },
                            )
                            Spacer(Modifier.width(12.dp))
                            Icon(dest.icon, contentDescription = null, tint = Palette.accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                title,
                                style = NoopType.body,
                                color = if (pinned) Palette.textPrimary else Palette.textTertiary,
                                modifier = Modifier.weight(1f),
                            )
                            if (pinned) {
                                IconButton(
                                    onClick = { move(index, index - 1) },
                                    enabled = index > 0,
                                    modifier = Modifier.size(Metrics.iconButton),
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowUp,
                                        contentDescription = "Move $title up",
                                        tint = if (index > 0) Palette.textSecondary else Palette.textTertiary,
                                        modifier = Modifier.size(Metrics.iconSmall),
                                    )
                                }
                                IconButton(
                                    onClick = { move(index, index + 1) },
                                    // Only within the pinned block: the unpinned rest has no order to keep.
                                    enabled = index < pinnedCount - 1,
                                    modifier = Modifier.size(Metrics.iconButton),
                                ) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowDown,
                                        contentDescription = "Move $title down",
                                        tint = if (index < pinnedCount - 1) Palette.textSecondary else Palette.textTertiary,
                                        modifier = Modifier.size(Metrics.iconSmall),
                                    )
                                }
                            }
                        }
                        if (index < items.lastIndex) HorizontalDivider(color = Palette.hairline, thickness = 1.dp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            val defaults = AdaptiveLayout.DEFAULT_NAV_SHORTCUTS
                                .mapNotNull { r -> candidates.firstOrNull { it.route == r } }
                            items.clear()
                            defaults.forEach { items.add(it to true) }
                            candidates.filter { it !in defaults }.forEach { items.add(it to false) }
                        },
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = Palette.textSecondary,
                        ),
                    ) { Text("Reset", style = NoopType.body) }
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.Button(
                        onClick = {
                            // Pinned ones keep the order they sit in, wherever a switch was flipped.
                            WideLayoutPrefs.setNavShortcuts(context, items.filter { it.second }.map { it.first.route })
                            onDismiss()
                        },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = Palette.accent,
                            contentColor = Palette.surfaceBase,
                        ),
                    ) { Text("Done", style = NoopType.captionNumber) }
                }
            }
        }
    }
}

/** One nav slot: an icon over a small label. Active = gold accent (semibold), inactive = textSecondary.
 *  No selection pill, no glow — just the colour swap, matching the iOS bar. */
@Composable
private fun BarSlot(
    icon: ImageVector,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (active) Palette.accent else Palette.textSecondary
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 3.dp)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        // Centred, so a fixed-height rail slot keeps its icon + label in the middle.
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Metrics.iconSmall))
        Text(
            label,
            style = NoopType.footnote.copy(
                fontSize = 10.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            ),
            color = tint,
            // One line: a pinned shortcut's longer name ellipsizes rather than growing the slot.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A centre-FAB quick action: a display title, an icon and the destination route it opens. */
private data class QuickAction(@StringRes val titleRes: Int, val icon: ImageVector, val route: String)

/** The quick actions on the gold centre FAB, each routing to an existing destination. Live HR leads
 *  — it moved off the bottom bar (so the FAB no longer overlaps a tab) but stays one tap away here. */
private val quickActions: List<QuickAction> = listOf(
    QuickAction(R.string.action_live_hr, Destination.Live.icon, Destination.Live.route),
    QuickAction(R.string.action_start_workout, Icons.Filled.FitnessCenter, Destination.Workouts.route),
    QuickAction(R.string.action_log_journal, Icons.Filled.Edit, Destination.Insights.route),
    QuickAction(R.string.action_breathe, Icons.Filled.Air, Destination.Breathe.route),
)

// MARK: - Navigation motion (README §Motion)
//
// The global easing is the calm, decelerating cubic-bezier(0.22, 1, 0.36, 1) — nothing
// bounces or overshoots. Top-level destination switches crossfade over ~240ms (README
// "Tab crossfade"); the same spec drives back navigation so the bar never feels jerky.

/** The calm global easing curve from the handoff (cubic-bezier 0.22, 1, 0.36, 1). */
private val NavEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** ~240ms crossfade on the calm easing — the README "Tab crossfade" between roots. */
private val navFadeSpec = tween<Float>(durationMillis = 240, easing = NavEasing)

/**
 * BrandMark — the Choop logo glyph at a small in-app size: an OPEN recovery ring (≈80%
 * arc, round caps, starting at −90° / 12 o'clock, clockwise) in the gold gradient with a
 * solid gold core dot at the centre. This is the same brand glyph the RecoveryRing hero
 * carries (the "O" of Choop), shrunk for the top bar / drawer header so the logo reads in
 * app. CLEAN/flat per the v3 restraint brief — no bloom, no halo, just the gradient ring.
 * Token-only (gold gradient + hairline track); decorative, so it carries no content label.
 */
@Composable
internal fun BrandMark(size: Dp = 22.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.13f          // ~2px-equivalent at 22dp
        val radius = (this.size.minDimension - stroke) / 2f
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)
        val capStroke = Stroke(width = stroke, cap = StrokeCap.Round)

        // Faint full-ring track (navy hairline) behind the open arc.
        drawCircle(
            color = Palette.hairline.copy(alpha = 0.5f),
            radius = radius,
            center = center,
            style = capStroke,
        )
        // Open recovery-ring arc: ~80% (288°), −90° start (12 o'clock), clockwise.
        drawArc(
            color = Palette.chargeColor,
            startAngle = -90f,
            sweepAngle = 288f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = capStroke,
        )
        // Solid WHITE "on-device core" dot at the centre (green ring + white core — iOS parity, no gold).
        drawCircle(color = Color.White, radius = stroke * 0.62f, center = center)
    }
}

/** Navigate to a top-level destination with single-top + state save/restore. */
private fun NavHostController.navigateTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/**
 * Loader for the v5 "Your Data, Fused" screen: assembles today's [FusedRecord] off the repository via
 * [AppViewModel.fusedRecordForToday] (the pure FusionResolver per metric) and hands the pure
 * [FusedRecordScreen] its read-model. Keeps the screen itself I/O-free + previewable. Re-loads on entry.
 */
@Composable
private fun FusedRecordRoute(viewModel: AppViewModel) {
    var record by remember {
        mutableStateOf(FusedRecord(rows = emptyList(), dayOwner = null as FusionSource?, contributingSourceCount = 0))
    }
    LaunchedEffect(Unit) {
        record = runCatching { viewModel.fusedRecordForToday() }.getOrDefault(record)
    }
    FusedRecordScreen(record = record)
}

/**
 * Placeholder screen for routes later waves will build. Uses [ScreenScaffold] so the
 * dark, instrument-grade chrome is already correct when a real screen replaces it.
 */
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
                Text(text, style = NoopType.title2, color = Palette.textPrimary, textAlign = TextAlign.Center)
                Overline("Coming soon", color = Palette.textSecondary)
                Text(
                    "This section is on the way.",
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
