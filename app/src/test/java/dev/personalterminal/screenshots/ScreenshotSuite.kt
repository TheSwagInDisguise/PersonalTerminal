package dev.personalterminal.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.prefs.Settings
import dev.personalterminal.data.prefs.ThemeMode
import dev.personalterminal.ui.habits.HabitDetailScreen
import dev.personalterminal.ui.habits.HabitsScreen
import dev.personalterminal.ui.navigation.StatusLine
import dev.personalterminal.ui.navigation.Tab
import dev.personalterminal.ui.profile.ProfileScreen
import dev.personalterminal.ui.settings.SettingsScreen
import dev.personalterminal.timer.Phase
import dev.personalterminal.timer.PomodoroService
import dev.personalterminal.timer.TimerState
import dev.personalterminal.ui.theme.Term
import dev.personalterminal.ui.theme.TerminalTheme
import dev.personalterminal.ui.timeline.TimelineScreen
import dev.personalterminal.ui.timer.TimerScreen
import dev.personalterminal.ui.today.TodayScreen
import dev.personalterminal.ui.insights.ReviewScreen
import dev.personalterminal.ui.insights.AchievementsScreen
import dev.personalterminal.ui.insights.InsightsScreen
import dev.personalterminal.ui.habits.TemplatesScreen
import dev.personalterminal.ui.habits.HabitEditScreen
import dev.personalterminal.ui.habits.JournalScreen
import dev.personalterminal.ui.timer.SessionsScreen
import dev.personalterminal.ui.watch.WatchStatsScreen
import dev.personalterminal.ui.watch.StrapsScreen
import dev.personalterminal.ui.watch.WatchDetailScreen
import dev.personalterminal.ui.watch.WatchesScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.LocalDate

/**
 * Renders the real screens against seeded demo data and writes PNGs for the README.
 *
 *   ./gradlew screenshots          → PNG files in screenshots/ (repository root)
 *
 * Runs on the JVM via Robolectric with native (Skia) graphics – no emulator needed – so the
 * images are reproducible and stay in sync with the code. Skipped entirely during a normal
 * `testDebugUnitTest` run unless `-Pscreenshots.dir` / the `screenshots` task is used.
 */
/** The real Application minus the first-launch starter habits – the suite seeds its own profile. */
class ScreenshotApp : PersonalTerminalApp() {
    override val seedStarterHabits: Boolean get() = false
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ScreenshotApp::class, qualifiers = "w412dp-h915dp-420dpi")
class ScreenshotSuite {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val app: PersonalTerminalApp get() = ApplicationProvider.getApplicationContext()
    private val outDir: File? = System.getProperty("screenshots.dir")?.let(::File)
    /** Pinned so the goldens do not drift with the calendar (a Saturday, mid-month, no DST edge). */
    private val today: LocalDate = LocalDate.of(2026, 9, 12)

    @Before
    fun seed() {
        org.junit.Assume.assumeTrue("screenshots.dir not set – screenshot suite skipped", outDir != null)
        java.util.Locale.setDefault(java.util.Locale.US) // month / weekday names in the goldens
        dev.personalterminal.domain.AppClock.clock = java.time.Clock.fixed(
            today.atTime(10, 30).atZone(java.time.ZoneId.systemDefault()).toInstant(), java.time.ZoneId.systemDefault(),
        )
        // Grant what a real user grants on first run, so the timer screen shows the normal state.
        Shadows.shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        runBlocking { DemoData.seed(app, today) }
    }

    @Test fun today() = shoot("01-today", Tab.TODAY) { nav -> TodayScreen(app, nav) }
    @Test fun habits() = shoot("02-habits", Tab.HABITS) { nav -> HabitsScreen(app, nav) }
    @Test fun habitDetail() = shoot("03-habit-detail", Tab.HABITS) { nav -> HabitDetailScreen(app, nav, habitId(name = "stretch")) }
    @Test fun timer() {
        // Show a session in flight rather than an idle 25:00. The screen only observes
        // PomodoroService.state, so we set that directly instead of running the foreground service.
        val focusHabit = habitId(name = "focus session")
        val total = 25 * 60
        val remaining = 17 * 60 + 23
        setTimerState(
            TimerState(
                phase = Phase.FOCUS, running = true, totalSeconds = total, remainingSeconds = remaining, cycle = 2,
                habitId = focusHabit, habitName = "focus session", focusedSeconds = total - remaining,
                endsAtMs = System.currentTimeMillis() + remaining * 1000L,
            ),
        )
        try {
            shoot("04-timer", Tab.TIMER) { nav -> TimerScreen(app, nav, focusHabit) }
        } finally {
            setTimerState(TimerState()) // static state survives across tests – leave it idle
        }
    }
    @Test fun watches() = shoot("05-watches", Tab.WATCH) { nav -> WatchesScreen(app, nav) }
    @Test fun watchDetail() = shoot("06-watch-detail", Tab.WATCH) { nav -> WatchDetailScreen(app, nav, watchId(model = "Speedmaster Professional")) }
    @Test fun timeline() = shoot("07-timeline", Tab.WATCH) { nav -> TimelineScreen(app, nav) }
    @Test fun profile() = shoot("08-profile", Tab.PROFILE) { nav -> ProfileScreen(app, nav) }
    @Test fun settings() = shoot("09-settings", Tab.PROFILE) { nav -> SettingsScreen(app, nav) }
    @Test fun themeNordLight() = shoot("10-theme-nord-light", Tab.TODAY, theme = "nord", dark = false) { nav -> TodayScreen(app, nav) }
    @Test fun themeGruvbox() = shoot("11-theme-gruvbox", Tab.PROFILE, theme = "gruvbox") { nav -> ProfileScreen(app, nav) }
    @Test fun themeSolarizedLight() = shoot("12-theme-solarized-light", Tab.HABITS, theme = "solarized", dark = false) { nav -> HabitsScreen(app, nav) }
    @Test fun review() = shoot("13-review", Tab.PROFILE) { nav -> ReviewScreen(app, nav) }
    @Test fun achievements() = shoot("14-achievements", Tab.PROFILE) { nav -> AchievementsScreen(app, nav) }
    @Test fun insights() = shoot("15-insights", Tab.PROFILE) { nav -> InsightsScreen(app, nav) }
    @Test fun templates() = shoot("16-templates", Tab.HABITS) { nav -> TemplatesScreen(app, nav) }
    @Test fun sessions() = shoot("17-sessions", Tab.TIMER) { nav -> SessionsScreen(app, nav) }
    @Test fun watchStats() = shoot("18-watch-stats", Tab.WATCH) { nav -> WatchStatsScreen(app, nav) }
    @Test fun straps() = shoot("19-straps", Tab.WATCH) { nav -> StrapsScreen(app, nav) }
    @Test fun habitEdit() = shoot("20-habit-edit", Tab.HABITS) { nav -> HabitEditScreen(app, nav, habitId(name = "no sugar"), null) }
    @Test fun journal() = shoot("21-journal", Tab.HABITS) { nav -> JournalScreen(app, nav) }
    @Test fun crt() = shoot("22-crt-matrix", Tab.TODAY, theme = "matrix", crt = true) { nav -> TodayScreen(app, nav) }
    @Test fun todaySections() {
        // Today grouped by time of day (setting persisted, read by the screen itself).
        runBlocking { app.prefs.setTodaySections(true) }
        try { shoot("23-today-sections", Tab.TODAY, theme = "nord") { nav -> TodayScreen(app, nav) } } finally { runBlocking { app.prefs.setTodaySections(false) } }
    }
    @Test fun checklistEdit() = shoot("24-checklist-edit", Tab.HABITS) { nav -> HabitEditScreen(app, nav, habitId(name = "pack gym bag"), null) }
    @Test fun watchBox() = shoot("25-watch-box", Tab.WATCH) { nav -> dev.personalterminal.ui.watch.WatchBoxScreen(app, nav) }
    @Test fun yearReview() = shoot("26-year-review", Tab.PROFILE, theme = "gruvbox") { nav -> dev.personalterminal.ui.insights.YearReviewScreen(app, nav) }
    @Test fun insurance() = shoot("27-streak-insurance", Tab.HABITS) { nav -> dev.personalterminal.ui.habits.SkipRulesScreen(app, nav) }
    @Test fun themeTokyoNight() = shoot("28-theme-tokyo-night", Tab.TODAY, theme = "tokyonight") { nav -> TodayScreen(app, nav) }
    @Test fun themeAmberCrt() = shoot("29-theme-amber-crt", Tab.WATCH, theme = "amber", crt = true) { nav -> WatchesScreen(app, nav) }
    @Test fun themeRosePineDawn() = shoot("30-theme-rose-pine-dawn", Tab.PROFILE, theme = "rosepine", dark = false) { nav -> ProfileScreen(app, nav) }
    @Test fun themeSynthwave() = shoot("31-theme-synthwave", Tab.TIMER, theme = "synthwave") { nav -> SessionsScreen(app, nav) }
    @Test fun wishlist() = shoot("33-wishlist", Tab.WATCH) { nav -> dev.personalterminal.ui.watch.WishlistScreen(app, nav, "wishlist") }
    @Test fun uptime() = shoot("34-uptime", Tab.WATCH, theme = "everforest") { nav -> dev.personalterminal.ui.watch.WishlistScreen(app, nav, "uptime") }
    @Test fun fontsAndIcons() = shoot("32-fonts-icons", Tab.PROFILE, theme = "onedark") { nav ->
        // settings scrolled to the font / icon pickers
        SettingsScreen(app, nav, scrollToAppearanceEnd = true)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setTimerState(state: TimerState) {
        val field = PomodoroService::class.java.getDeclaredField("_state").apply { isAccessible = true }
        (field.get(null) as MutableStateFlow<TimerState>).value = state
    }

    private fun habitId(name: String): Long = runBlocking { app.habits.allHabits().first { it.name == name }.id }
    private fun watchId(model: String): Long = runBlocking { app.db.watchDao().getAll().first { it.model == model }.id }

    private fun shoot(
        name: String,
        tab: Tab,
        theme: String = "signal",
        dark: Boolean = true,
        crt: Boolean = false,
        content: @Composable (androidx.navigation.NavHostController) -> Unit,
    ) {
        val settings = Settings(
            themeName = theme,
            themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT,
            username = DemoData.USERNAME,
            crtEffect = crt,
            fontName = dev.personalterminal.ui.theme.ThemeFamily.fromId(theme).font,
        )
        rule.setContent {
            TerminalTheme(settings) {
                val nav = rememberNavController()
                Column(Modifier.fillMaxSize().background(Term.palette.bg)) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { content(nav) }
                    StatusLine(nav, tab, settings)
                }
            }
        }
        // Let Room flows, Coil image loads and the boot/typing animations settle. Room's suspend
        // queries and Coil's decoding run on real background threads, so give them wall-clock time
        // as well as virtual time – otherwise a heavy screen (year review) or the last thumbnail in a
        // grid can still be in flight when the frame is captured.
        repeat(16) {
            ShadowLooper.idleMainLooper()
            rule.mainClock.advanceTimeBy(250)
            rule.waitForIdle()
            Thread.sleep(60)
            ShadowLooper.idleMainLooper()
        }
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val out = File(outDir!!, "$name.png")
        out.parentFile.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("screenshot: ${out.absolutePath} (${bmp.width}x${bmp.height})")
    }
}
