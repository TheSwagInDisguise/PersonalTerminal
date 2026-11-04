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
import dev.personalterminal.ui.planner.PlannerScreen
import dev.personalterminal.ui.planner.QuickAddPlannerFab
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

    // Expanded layouts use section → list → detail rather than pinning Today beside every route.
    // On a book fold the list ends exactly at the hinge; the selected item's detail lives on the
    // far side. On a wide tablet the same master/detail relationship uses a 42/58 split.
    val sizeClass = currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass
    val expanded = sizeClass != WindowWidthSizeClass.COMPACT
    val veryWide = sizeClass == WindowWidthSizeClass.EXPANDED
    val foldX = rememberWindowFold().foldXDp
    val split = veryWide || (expanded && foldX != null)
    val splitState = rememberUpdatedState(split)
    val selectedHabitIdState = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    val selectedWatchIdState = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<Long?>(null) }
    val selectedHabitId by selectedHabitIdState
    val selectedWatchId by selectedWatchIdState
    fun selectHabit(id: Long) {
        selectedHabitIdState.value = id
        selectedWatchIdState.value = null
    }
    fun selectWatch(id: Long) {
        selectedWatchIdState.value = id
        selectedHabitIdState.value = null
    }

    Scaffold(
        containerColor = p.bg,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = { if (!expanded) StatusLine(nav, currentTab, settings) },
        floatingActionButton = {
            QuickAddPlannerFab(
                app = app,
                nav = nav,
                visible = currentRoute?.substringBefore("?") != Routes.PLANNER.substringBefore("?"),
            )
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (expanded) SideRail(nav, currentTab, settings, compact = foldX != null)
            Box(Modifier.weight(1f).fillMaxHeight()) {
            // Keep one NavHost through fold/unfold so in-progress forms and back stack survive.
            // Only the list roots adopt master/detail; utility routes use the whole content area.
            NavHost(navController = nav, startDestination = Routes.TODAY) {
                composable(Routes.TODAY) {
                    if (splitState.value) FoldMasterDetailLayout(
                        foldX = foldX,
                        railWidth = if (foldX != null) CompactRailWidth else RailWidth,
                        list = { TodayScreen(app, nav, onSelectHabit = ::selectHabit, selectedHabitId = selectedHabitId) },
                        detail = {
                            selectedHabitId?.let { HabitDetailScreen(app, nav, it) }
                                ?: DetailPaneHint("today", "expand a habit and choose open to see its details here")
                        },
                    ) else TodayScreen(app, nav)
                }
                composable(Routes.HABITS) {
                    if (splitState.value) FoldMasterDetailLayout(
                        foldX = foldX,
                        railWidth = if (foldX != null) CompactRailWidth else RailWidth,
                        list = { HabitsScreen(app, nav, onSelectHabit = ::selectHabit, selectedHabitId = selectedHabitId) },
                        detail = {
                            selectedHabitId?.let { HabitDetailScreen(app, nav, it) }
                                ?: DetailPaneHint("habit detail", "select a habit from the list")
                        },
                    ) else HabitsScreen(app, nav)
                }
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
                composable(Routes.COLLECTION_REPORT) { dev.personalterminal.ui.watch.CollectionReportScreen(app, nav) }
                composable(Routes.CONTACT_SHEET) { dev.personalterminal.ui.watch.ContactSheetScreen(app, nav) }
                composable(Routes.WATCH_BOX) { dev.personalterminal.ui.watch.WatchBoxScreen(app, nav) }
                composable(Routes.WISHLIST_TAB, arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "wishlist" })) {
                    dev.personalterminal.ui.watch.WishlistScreen(app, nav, it.arguments?.getString("tab") ?: "wishlist")
                }
                composable(Routes.SKIP_RULES) { dev.personalterminal.ui.habits.SkipRulesScreen(app, nav) }
                composable(Routes.YEAR_REVIEW) { dev.personalterminal.ui.insights.YearReviewScreen(app, nav) }
                composable(Routes.IMPORT) { ImportScreen(app, nav) }
                composable(Routes.JOURNAL) { JournalScreen(app, nav) }
                composable(Routes.GARDEN) { dev.personalterminal.ui.garden.GardenScreen(app, nav) }
                composable(Routes.PLANNER) { PlannerScreen(app, nav) }
                composable(
                    Routes.PLANNER_TAB,
                    arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "tasks" }),
                ) {
                    PlannerScreen(app, nav, it.arguments?.getString("tab") ?: "tasks")
                }
                composable(Routes.HABIT_DETAIL, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    HabitDetailScreen(app, nav, it.arguments?.getLong("id") ?: 0L)
                }
                composable(Routes.ROUTINES) { RoutinesScreen(app, nav) }
                composable(Routes.TIMER, arguments = listOf(navArgument("habitId") { type = NavType.LongType; defaultValue = 0L })) {
                    TimerScreen(app, nav, it.arguments?.getLong("habitId") ?: 0L)
                }
                composable(Routes.TIMELINE) { TimelineScreen(app, nav) }
                composable(Routes.WATCHES) {
                    if (splitState.value) FoldMasterDetailLayout(
                        foldX = foldX,
                        railWidth = if (foldX != null) CompactRailWidth else RailWidth,
                        list = { WatchesScreen(app, nav, onSelectWatch = ::selectWatch, selectedWatchId = selectedWatchId) },
                        detail = {
                            selectedWatchId?.let { WatchDetailScreen(app, nav, it) }
                                ?: DetailPaneHint("watch detail", "select a watch from the collection")
                        },
                    ) else WatchesScreen(app, nav)
                }
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

/** A section list stays in the middle; its selected item's full screen detail occupies the far pane. */
@Composable
private fun FoldMasterDetailLayout(
    foldX: androidx.compose.ui.unit.Dp?,
    railWidth: androidx.compose.ui.unit.Dp,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    val p = Term.palette
    Row(Modifier.fillMaxSize()) {
        val listPane = if (foldX != null) {
            Modifier.width((foldX - railWidth - 2.dp).coerceAtLeast(0.dp))
        } else Modifier.weight(0.42f)
        Box(listPane.fillMaxHeight()) { list() }
        Box(Modifier.width(1.dp).fillMaxHeight().background(p.border))
        Box(Modifier.weight(if (foldX != null) 1f else 0.58f).fillMaxHeight()) { detail() }
    }
}

@Composable
private fun DetailPaneHint(title: String, hint: String) {
    val p = Term.palette
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("— $title —", color = p.green, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("# $hint", color = p.fgDim, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Width of the tmux-style side rail – also used to place the divider exactly on the fold. */
private val RailWidth = 112.dp
private val CompactRailWidth = 88.dp

/** Vertical tab strip for wide layouts (`[0:today]` … stacked on the left, like a tmux pane list). */
@Composable
private fun SideRail(nav: NavHostController, current: Tab?, settings: Settings, compact: Boolean = false) {
    val p = Term.palette
    Column(
        Modifier
            .fillMaxHeight()
            .width(if (compact) CompactRailWidth else RailWidth)
            .background(p.bgAlt)
            .windowInsetsPadding(WindowInsets.statusBars)
            // One UI shows a taskbar along the bottom of the inner screen; without this the
            // "⚙ settings" link at the end of the rail can hide underneath it.
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(vertical = 12.dp, horizontal = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(if (compact) settings.prompt.take(7) + "…" else settings.prompt, color = p.green, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(horizontal = 4.dp))
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
