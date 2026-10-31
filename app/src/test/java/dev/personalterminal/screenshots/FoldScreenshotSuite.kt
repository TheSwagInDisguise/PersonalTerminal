package dev.personalterminal.screenshots

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.prefs.Settings
import dev.personalterminal.data.prefs.ThemeMode
import dev.personalterminal.timer.Phase
import dev.personalterminal.timer.PomodoroService
import dev.personalterminal.timer.TimerState
import dev.personalterminal.ui.habits.HabitEditScreen
import dev.personalterminal.ui.navigation.AppScaffold
import dev.personalterminal.ui.navigation.StatusLine
import dev.personalterminal.ui.navigation.Tab
import dev.personalterminal.ui.theme.Term
import dev.personalterminal.ui.theme.TerminalTheme
import dev.personalterminal.ui.timer.TimerScreen
import dev.personalterminal.ui.today.TodayScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.LocalDate
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowInfoTrackerDecorator
import androidx.window.layout.WindowLayoutInfo

/**
 * Fold 5 layout goldens (the foldable plan, step 8): the same screens as [ScreenshotSuite], but
 * at the two window sizes the phone actually reports, so CI catches layout regressions on
 * **both** screens without a device:
 *
 *   cover   `w344dp-h882dp`   – compact layout, five-tab status line (indices/brackets dropped)
 *   inner   `w690dp-h829dp`   – side rail + split panes, divider placed on the fake hinge
 *   flex    `w829dp-h690dp`   – landscape tabletop: timer countdown top, controls bottom
 *
 * The hinge is injected with [WindowInfoTracker.overrideDecorator]: Robolectric's window backend
 * reports no display features, so without the decorator the inner screen would never split.
 */
private val foldToday: LocalDate = LocalDate.of(2026, 9, 12)

/** Shared @Before body: skip when not rendering screenshots, pin clock/locale, seed demo data. */
private fun seedFoldSuite(app: PersonalTerminalApp) {
    val outDir = System.getProperty("screenshots.dir")
    org.junit.Assume.assumeTrue("screenshots.dir not set – fold suite skipped", outDir != null)
    java.util.Locale.setDefault(java.util.Locale.US)
    dev.personalterminal.domain.AppClock.clock = java.time.Clock.fixed(
        foldToday.atTime(10, 30).atZone(java.time.ZoneId.systemDefault()).toInstant(), java.time.ZoneId.systemDefault(),
    )
    shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    runBlocking { DemoData.seed(app, foldToday) }
}

private fun baseSettings(onboarded: Boolean = false) = Settings(
    themeName = "signal",
    themeMode = ThemeMode.DARK,
    username = DemoData.USERNAME,
    fontName = dev.personalterminal.ui.theme.ThemeFamily.fromId("dracula").font,
    onboarded = onboarded,
)

/** A screen plus the bottom status line – what a single-pane (cover / phone) layout shows. */
private fun shootScreen(
    rule: ComposeContentTestRule,
    activity: Activity,
    app: PersonalTerminalApp,
    name: String,
    tab: Tab,
    content: @Composable (androidx.navigation.NavHostController) -> Unit,
) {
    val settings = baseSettings()
    rule.setContent {
        TerminalTheme(settings) {
            val nav = rememberNavController()
            Column(Modifier.fillMaxSize().background(Term.palette.bg)) {
                Box(Modifier.weight(1f).fillMaxWidth()) { content(nav) }
                StatusLine(nav, tab, settings)
            }
        }
    }
    settleAndCapture(rule, activity, name)
}

/** The full scaffold (rail + panes + nav host) – the inner-screen layout including the split. */
private fun shootApp(
    rule: ComposeContentTestRule,
    activity: Activity,
    app: PersonalTerminalApp,
    name: String,
) {
    val settings = baseSettings(onboarded = true)
    rule.setContent {
        TerminalTheme(settings) {
            AppScaffold(app = app, settings = settings, startRoute = null, onRouteConsumed = { })
        }
    }
    settleAndCapture(rule, activity, name)
}

private fun settleAndCapture(rule: ComposeContentTestRule, activity: Activity, name: String) {
    // Same settle loop as ScreenshotSuite: Room flows, Coil and animations need wall-clock time.
    repeat(16) {
        ShadowLooper.idleMainLooper()
        rule.mainClock.advanceTimeBy(250)
        rule.waitForIdle()
        Thread.sleep(60)
        ShadowLooper.idleMainLooper()
    }
    val view = activity.window.decorView
    val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bmp))
    val out = File(System.getProperty("screenshots.dir")!!, "$name.png")
    out.parentFile.mkdirs()
    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    println("screenshot: ${out.absolutePath} (${bmp.width}x${bmp.height})")
}

@Suppress("UNCHECKED_CAST")
private fun setTimerState(state: TimerState) {
    val field = PomodoroService::class.java.getDeclaredField("_state").apply { isAccessible = true }
    (field.get(null) as MutableStateFlow<TimerState>).value = state
}

private fun habitId(app: PersonalTerminalApp, name: String): Long =
    runBlocking { app.habits.allHabits().first { it.name == name }.id }

/**
 * Injects a fake hinge into [WindowInfoTracker]: a zero-width fold down the middle (vertical,
 * flat – how the inner screen reports when lying open) or across the middle (horizontal,
 * half-open – Flex Mode / tabletop).
 */
@Suppress("RestrictedApi")
private class FakeFold(private val vertical: Boolean, private val separating: Boolean) : WindowInfoTrackerDecorator {
    override fun decorate(tracker: WindowInfoTracker): WindowInfoTracker = object : WindowInfoTracker {
        override fun windowLayoutInfo(activity: Activity): Flow<WindowLayoutInfo> {
            val dm = activity.resources.displayMetrics
            val rect = if (vertical) Rect(dm.widthPixels / 2, 0, dm.widthPixels / 2, dm.heightPixels)
            else Rect(0, dm.heightPixels / 2, dm.widthPixels, dm.heightPixels / 2)
            val feature = object : FoldingFeature {
                override val bounds: Rect get() = rect
                override val isSeparating: Boolean = separating
                override val occlusionType: FoldingFeature.OcclusionType = FoldingFeature.OcclusionType.NONE
                override val orientation: FoldingFeature.Orientation =
                    if (vertical) FoldingFeature.Orientation.VERTICAL else FoldingFeature.Orientation.HORIZONTAL
                override val state: FoldingFeature.State =
                    if (separating) FoldingFeature.State.HALF_OPENED else FoldingFeature.State.FLAT
            }
            return flowOf(WindowLayoutInfo(listOf(feature)))
        }
    }
}

// ------------------------------------------------------------------ cover screen (~344dp)

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ScreenshotApp::class, qualifiers = "w344dp-h882dp-420dpi")
class CoverFoldSuite {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app: PersonalTerminalApp get() = ApplicationProvider.getApplicationContext()

    @Before fun seed() = seedFoldSuite(app)

    @Test fun today() = shootScreen(rule, rule.activity, app, "35-cover-today", Tab.TODAY) { nav -> TodayScreen(app, nav) }
    @Test fun habitEdit() = shootScreen(rule, rule.activity, app, "36-cover-habit-edit", Tab.HABITS) { nav ->
        HabitEditScreen(app, nav, habitId(app, "no sugar"), null)
    }
}

// ------------------------------------------------------------------ inner screen, portrait (690dp + hinge)

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ScreenshotApp::class, qualifiers = "w690dp-h829dp-420dpi")
class InnerSplitSuite {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app: PersonalTerminalApp get() = ApplicationProvider.getApplicationContext()

    @Before fun seed() {
        seedFoldSuite(app)
        WindowInfoTracker.overrideDecorator(FakeFold(vertical = true, separating = false))
    }
    @After fun unstub() { WindowInfoTracker.reset() }

    @Test fun splitAtHinge() = shootApp(rule, rule.activity, app, "37-inner-split")
}

// ------------------------------------------------------------------ Flex Mode / tabletop (landscape + hinge)

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = ScreenshotApp::class, qualifiers = "w829dp-h690dp-420dpi")
class FlexTimerSuite {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app: PersonalTerminalApp get() = ApplicationProvider.getApplicationContext()

    @Before fun seed() {
        seedFoldSuite(app)
        WindowInfoTracker.overrideDecorator(FakeFold(vertical = false, separating = true))
    }
    @After fun unstub() {
        WindowInfoTracker.reset()
        setTimerState(TimerState()) // static state survives across tests – leave it idle
    }

    @Test fun timer() {
        val focusHabit = habitId(app, "focus session")
        val total = 25 * 60
        val remaining = 17 * 60 + 23
        setTimerState(
            TimerState(
                phase = Phase.FOCUS, running = true, totalSeconds = total, remainingSeconds = remaining, cycle = 2,
                habitId = focusHabit, habitName = "focus session", focusedSeconds = total - remaining,
                endsAtMs = System.currentTimeMillis() + remaining * 1000L,
            ),
        )
        shootScreen(rule, rule.activity, app, "38-timer-flex", Tab.TIMER) { nav -> TimerScreen(app, nav, focusHabit) }
    }
}
