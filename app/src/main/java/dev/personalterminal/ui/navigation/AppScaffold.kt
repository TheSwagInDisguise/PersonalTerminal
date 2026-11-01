package dev.personalterminal.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.window.core.layout.WindowWidthSizeClass
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBars
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.prefs.Settings
import dev.personalterminal.ui.boot.BootScreen
import dev.personalterminal.ui.components.rememberWindowFold
import dev.personalterminal.ui.habits.HabitDetailScreen
import dev.personalterminal.ui.habits.HabitEditScreen
import dev.personalterminal.ui.habits.HabitsScreen
import dev.personalterminal.ui.habits.TemplatesScreen
import dev.personalterminal.ui.habits.JournalScreen
import dev.personalterminal.ui.insights.ReviewScreen
import dev.personalterminal.ui.insights.AchievementsScreen
import dev.personalterminal.ui.insights.InsightsScreen
import dev.personalterminal.ui.timer.SessionsScreen
import dev.personalterminal.ui.watch.StrapsScreen
import dev.personalterminal.ui.watch.WatchStatsScreen
import dev.personalterminal.ui.settings.ImportScreen
import dev.personalterminal.ui.profile.ProfileScreen
import dev.personalterminal.ui.routines.RoutinesScreen
import dev.personalterminal.ui.settings.SettingsScreen
import dev.personalterminal.ui.theme.Term
import dev.personalterminal.ui.timeline.TimelineScreen
import dev.personalterminal.ui.timer.TimerScreen
import dev.personalterminal.ui.today.TodayScreen
import dev.personalterminal.ui.watch.WatchDetailScreen
import dev.personalterminal.ui.watch.WatchEditScreen
import dev.personalterminal.ui.watch.WatchesScreen
import dev.personalterminal.ui.watch.WearLogScreen

@Composable
fun AppScaffold(app: PersonalTerminalApp, settings: Settings, startRoute: String?, onRouteConsumed: () -> Unit) {
    val nav = rememberNavController()
    val p = Term.palette

    if (!settings.onboarded) {
        BootScreen(app = app, onDone = { })
        return
    }

    LaunchedEffect(startRoute) {
        if (startRoute != null) {
            nav.navigate(startRoute) { launchSingleTop = true }
            onRouteConsumed()
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val currentTab = Tab.entries.firstOrNull { t -> currentRoute?.substringBefore("?") == t.route.substringBefore("?") }

    // Tablets / landscape phones / unfolded foldables: tmux-style split. The left pane keeps
    // today's list permanently in view; the right pane hosts everything else.
    //
    // Split when the window is wide (≥840dp, tablets) *or* when it is ≥600dp wide and the device
    // reports a vertical fold (Fold-class inner screen in portrait: ~690dp wide, which never
    // reaches 840 on its own). The fold case divides the panes exactly at the hinge – about
    // 345dp per side, almost exactly the cover screen's width – so each pane gets a layout the
    // app already fits. A flat-open inner screen reports the fold with zero width and book
    // posture (half-folded) reports a real divider; both carry the same x, so both align.
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass
    val expanded = sizeClass != WindowWidthSizeClass.COMPACT    // ≥600dp → side rail
    val veryWide = sizeClass == WindowWidthSizeClass.EXPANDED    // ≥840dp → split without a fold
    val fold = rememberWindowFold()                              // physical hinge + posture
    val foldX = fold.foldXDp
    val split = veryWide || (expanded && foldX != null)
    // Destination lambdas may be composed from a graph that was built before a fold, so anything
    // layout-sensitive read *inside* composable() goes through this State (see composable(TODAY)).
    val splitState = rememberUpdatedState(split)

    Scaffold(
        containerColor = p.bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { if (!expanded) StatusLine(nav, currentTab, settings) },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (expanded) SideRail(nav, currentTab, settings)
            if (split) {
                if (foldX != null) {
                    // Divide exactly at the fold: rail + today on the left half, everything else right.
                    Box(Modifier.width((foldX - RailWidth - 1.dp).coerceAtLeast(0.dp)).fillMaxHeight()) { TodayScreen(app, nav) }
                } else {
                    Box(Modifier.weight(0.42f).fillMaxHeight()) { TodayScreen(app, nav) }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(p.border))
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
            // One fixed start destination, both modes. Switching it between Habits and Today when
            // the layout changes (fold/unfold, rotation) rebuilds the graph and wipes the back
            // stack – on a Fold that is exactly what killed a half-typed edit when the phone was
            // folded mid-edit. In split mode the right pane idles on habits via composable(TODAY).
            NavHost(navController = nav, startDestination = Routes.TODAY) {
                composable(Routes.TODAY) {
                    // Split: the left pane owns Today, so the right pane idles on the habit list
                    // instead of a second copy of Today (read splitState – see note above).
                    if (splitState.value) HabitsScreen(app, nav) else TodayScreen(app, nav)
                }
                composable(Routes.HABITS) { HabitsScreen(app, nav) }
                composable(
                    Routes.HABIT_EDIT,
                    arguments = listOf(
                        navArgument("id") { type = NavType.LongType; defaultValue = 0L },
                        navArgument("routineId") { type = NavType.LongType; defaultValue = -1L },
                        navArgument("name") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) {
                    val id = it.arguments?.getLong("id") ?: 0L
                    val rid = it.arguments?.getLong("routineId") ?: -1L
                    HabitEditScreen(app, nav, habitId = id, routineId = rid.takeIf { r -> r >= 0 }, initialName = it.arguments?.getString("name"))
                }
                composable(Routes.TEMPLATES) { TemplatesScreen(app, nav) }
                composable(Routes.REVIEW) { ReviewScreen(app, nav) }
                composable(Routes.ACHIEVEMENTS) { AchievementsScreen(app, nav) }
                composable(Routes.INSIGHTS) { InsightsScreen(app, nav) }
                composable(Routes.SESSIONS) { SessionsScreen(app, nav) }
                composable(Routes.STRAPS) { StrapsScreen(app, nav) }
                composable(Routes.WATCH_STATS) { WatchStatsScreen(app, nav) }
                composable(Routes.WATCH_BOX) { dev.personalterminal.ui.watch.WatchBoxScreen(app, nav) }
                composable(Routes.WISHLIST_TAB, arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "wishlist" })) {
                    dev.personalterminal.ui.watch.WishlistScreen(app, nav, it.arguments?.getString("tab") ?: "wishlist")
                }
                composable(Routes.SKIP_RULES) { dev.personalterminal.ui.habits.SkipRulesScreen(app, nav) }
                composable(Routes.YEAR_REVIEW) { dev.personalterminal.ui.insights.YearReviewScreen(app, nav) }
                composable(Routes.IMPORT) { ImportScreen(app, nav) }
                composable(Routes.JOURNAL) { JournalScreen(app, nav) }
                composable(Routes.GARDEN) { dev.personalterminal.ui.garden.GardenScreen(app, nav) }
                composable(Routes.HABIT_DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    HabitDetailScreen(app, nav, it.arguments?.getLong("id") ?: 0L)
                }
                composable(Routes.ROUTINES) { RoutinesScreen(app, nav) }
                composable(Routes.TIMER, arguments = listOf(navArgument("habitId") { type = NavType.LongType; defaultValue = 0L })) {
                    TimerScreen(app, nav, it.arguments?.getLong("habitId") ?: 0L)
                }
                composable(Routes.TIMELINE) { TimelineScreen(app, nav) }
                composable(Routes.WATCHES) { WatchesScreen(app, nav) }
                composable(Routes.WATCH_EDIT, arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = 0L }, navArgument("wish") { type = NavType.IntType; defaultValue = 0 })) {
                    WatchEditScreen(app, nav, it.arguments?.getLong("id") ?: 0L, startOnWishlist = (it.arguments?.getInt("wish") ?: 0) == 1)
                }
                composable(Routes.WATCH_DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    WatchDetailScreen(app, nav, it.arguments?.getLong("id") ?: 0L)
                }
                composable(Routes.WEAR_LOG, arguments = listOf(navArgument("day") { type = NavType.LongType; defaultValue = -1L })) {
                    WearLogScreen(app, nav, it.arguments?.getLong("day")?.takeIf { d -> d >= 0 })
                }
                composable(Routes.PROFILE) { ProfileScreen(app, nav) }
                composable(Routes.SETTINGS) { SettingsScreen(app, nav) }
            }
            }
        }
    }
}

/** Width of the tmux-style side rail – also used to place the divider exactly on the fold. */
private val RailWidth = 112.dp

/** Vertical tab strip for wide layouts (`[0:today]` … stacked on the left, like a tmux pane list). */
@Composable
private fun SideRail(nav: NavHostController, current: Tab?, settings: Settings) {
    val p = Term.palette
    Column(
        Modifier
            .fillMaxHeight()
            .width(RailWidth)
            .background(p.bgAlt)
            .windowInsetsPadding(WindowInsets.statusBars)
            // One UI shows a taskbar along the bottom of the inner screen; without this the
            // "⚙ settings" link at the end of the rail can hide underneath it.
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(settings.prompt, color = p.green, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
        Spacer(Modifier.height(8.dp))
        Tab.entries.forEach { tab ->
            val selected = tab == current
            Text(
                text = if (selected) "[${tab.index}:${tab.label}]" else " ${tab.index}:${tab.label} ",
                color = if (selected) p.green else p.fgDim,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.fillMaxWidth().then(if (selected) Modifier.background(p.bgHighlight) else Modifier).clickable { nav.switchTab(tab) }.padding(horizontal = 4.dp, vertical = 8.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        Text("⚙ settings", color = p.fgDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { nav.navigate(Routes.SETTINGS) }.padding(4.dp))
    }
    Box(Modifier.width(1.dp).fillMaxHeight().background(p.border))
}

/** tmux-like status bar: `[0:today] 1:habits 2:timer 3:watch 4:profile        lvl 3 ` */
@Composable
internal fun StatusLine(nav: NavHostController, current: Tab?, settings: Settings) {
    val p = Term.palette
    // Fold 5 cover screen (~344dp): five "[n:label]" tabs don't fit on one line in the wider
    // terminal fonts (VT323 / Space Mono) – below 360dp the indices and brackets go.
    val narrow = LocalConfiguration.current.screenWidthDp < 360
    Column(Modifier.fillMaxWidth().background(p.bgAlt)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.border))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 8.dp)
                .windowInsetsPadding(WindowInsets.navigationBars),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab ->
                val selected = tab == current
                val label = when {
                    narrow -> tab.label
                    selected -> "[${tab.index}:${tab.label}]"
                    else -> " ${tab.index}:${tab.label} "
                }
                Text(
                    text = label,
                    maxLines = 1,
                    color = if (selected) p.green else p.fgDim,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .then(if (selected) Modifier.background(p.bgHighlight) else Modifier)
                        .clickable { nav.switchTab(tab) }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * Bottom-bar tab switch.
 *
 * Deliberately *not* the textbook `popUpTo(start) { saveState = true } + restoreState = true`
 * recipe. In this app the start destination (today) is itself a tab, and with that recipe the
 * NavController maps "state saved while popping up to today" onto the today destination. As soon
 * as any screen had been pushed on top of today before a tab switch (habit detail, ⛨ → profile,
 * profile → settings …), tapping `0:today` *restores* that popped screen instead of showing
 * today – every single time. That is the "can't get back to today" bug.
 *
 * So: a tab tap always unwinds to the start destination (dropping detail/edit screens, which is
 * what a tab bar is expected to do) and, for any tab other than today, re-launches the tab root
 * single-top on top of it. Tapping the already-selected tab acts as "back to the tab root".
 */
fun NavHostController.switchTab(tab: Tab) {
    val startId = graph.findStartDestination().id
    if (tab == Tab.TODAY) {
        if (currentDestination?.id == startId) return
        if (!popBackStack(startId, inclusive = false)) navigate(Routes.TODAY) { launchSingleTop = true }
        return
    }
    navigate(tab.route) {
        popUpTo(startId) { inclusive = false; saveState = false }
        launchSingleTop = true
        restoreState = false
    }
}
