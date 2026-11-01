package dev.personalterminal.ui.timer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as SysSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.db.HabitType
import dev.personalterminal.data.prefs.Settings
import dev.personalterminal.timer.Phase
import dev.personalterminal.timer.PomodoroService
import dev.personalterminal.ui.components.AsciiProgress
import dev.personalterminal.ui.components.Comment
import dev.personalterminal.ui.components.PromptLine
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.components.TerminalPanel
import dev.personalterminal.ui.components.rememberWindowFold
import dev.personalterminal.ui.navigation.Routes
import dev.personalterminal.ui.theme.Term

@Composable
fun TimerScreen(app: PersonalTerminalApp, nav: NavHostController, initialHabitId: Long) {
    val p = Term.palette
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by PomodoroService.state.collectAsStateWithLifecycle()
    val settings by app.prefs.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val habits by remember { app.habits.observeHabits() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val timerHabits = habits.filter { it.type == HabitType.TIMER && !it.archived }
    var selectedHabit by remember { mutableLongStateOf(initialHabitId) }
    if (state.phase != Phase.IDLE) selectedHabit = state.habitId

    // Notification permission gates the countdown notification (and the Live Update chip on
    // Android 16). Ask before the first start; if denied, still start the timer – it just won't
    // show outside the app – and explain how to fix it.
    fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var notifGranted by remember { mutableStateOf(notificationsAllowed()) }
    var liveUpdatesOn by remember { mutableStateOf(PomodoroService.canPostLiveUpdates(ctx)) }
    var dndAccess by remember { mutableStateOf(dev.personalterminal.timer.FocusDnd.hasAccess(ctx)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        // Re-check when coming back from system settings.
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { notifGranted = notificationsAllowed(); liveUpdatesOn = PomodoroService.canPostLiveUpdates(ctx); dndAccess = dev.personalterminal.timer.FocusDnd.hasAccess(ctx) } }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    var stopwatchMode by remember { mutableStateOf(false) }
    val selected = timerHabits.firstOrNull { it.id == selectedHabit }
    val focusLen = selected?.focusMinutes?.takeIf { it > 0 } ?: settings.pomodoroFocusMin
    val breakLen = selected?.breakMinutes?.takeIf { it > 0 } ?: settings.pomodoroBreakMin
    fun startSession() {
        val h = timerHabits.firstOrNull { it.id == selectedHabit }
        if (stopwatchMode) PomodoroService.startStopwatch(ctx, h?.id ?: 0L, h?.name ?: "")
        else PomodoroService.start(ctx, focusLen, breakLen, settings.pomodoroLongBreakMin, h?.id ?: 0L, h?.name ?: "")
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifGranted = granted
        startSession()
    }
    fun startWithPermission() {
        if (notificationsAllowed()) startSession()
        else permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun openNotificationSettings() {
        val i = Intent(SysSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SysSettings.EXTRA_APP_PACKAGE, ctx.packageName)
        runCatching { ctx.startActivity(i) }
    }

    val phaseColor = when (state.phase) { Phase.FOCUS, Phase.STOPWATCH -> p.orange; Phase.BREAK, Phase.LONG_BREAK -> p.cyan; Phase.IDLE -> p.green }

    val fold = rememberWindowFold()
    val tabletopY = if (fold.isTabletop) fold.foldYDp else null
    val topContent: @Composable ColumnScope.() -> Unit = {
        PromptLine(if (state.isStopwatch || (state.phase == Phase.IDLE && stopwatchMode)) "stopwatch" else "pomodoro", trailing = if (state.cycle > 0) "🍅 ×${state.cycle}" else null)
        if (state.phase == Phase.IDLE) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (!stopwatchMode) "[•] countdown" else "[ ] countdown", color = if (!stopwatchMode) p.green else p.fgDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { stopwatchMode = false }.padding(4.dp))
                Text(if (stopwatchMode) "[•] stopwatch" else "[ ] stopwatch", color = if (stopwatchMode) p.green else p.fgDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { stopwatchMode = true }.padding(4.dp))
                Spacer(Modifier.weight(1f))
                Text("garden →", color = p.green, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { nav.navigate(Routes.GARDEN) }.padding(4.dp))
                Text("sessions →", color = p.cyan, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { nav.navigate(Routes.SESSIONS) }.padding(4.dp))
            }
        }

        // ---- big clock ----
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(220.dp)) {
                val stroke = 10.dp.toPx()
                drawArc(color = p.bgHighlight, startAngle = -90f, sweepAngle = 360f, useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round), topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke))
                drawArc(color = phaseColor, startAngle = -90f, sweepAngle = 360f * state.fraction, useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round), topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (state.phase == Phase.IDLE) (if (stopwatchMode) "00:00" else "%02d:00".format(focusLen)) else state.clock,
                    color = p.fg, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold,
                )
                Text(
                    when (state.phase) { Phase.IDLE -> "idle"; Phase.FOCUS -> "focus"; Phase.BREAK -> "break"; Phase.LONG_BREAK -> "long break"; Phase.STOPWATCH -> "stopwatch" } +
                        (if (state.phase != Phase.IDLE && !state.running) " (paused)" else ""),
                    color = phaseColor, style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        AsciiProgress(fraction = state.fraction, width = 32, color = phaseColor, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
    val bottomContent: @Composable ColumnScope.() -> Unit = {
        // ---- controls ----
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.phase == Phase.IDLE -> TermButton(if (stopwatchMode) "▶ start stopwatch" else "▶ start focus", filled = true, modifier = Modifier.weight(1f), onClick = { startWithPermission() })
                state.running -> TermButton("‖ pause", modifier = Modifier.weight(1f), color = p.yellow, onClick = { PomodoroService.send(ctx, PomodoroService.ACTION_PAUSE) })
                else -> TermButton("▶ resume", modifier = Modifier.weight(1f), filled = true, onClick = { PomodoroService.send(ctx, PomodoroService.ACTION_RESUME) })
            }
            if (state.phase != Phase.IDLE) {
                if (!state.isStopwatch) TermButton("skip »", color = p.cyan, onClick = { PomodoroService.send(ctx, PomodoroService.ACTION_SKIP) })
                TermButton(if (state.isStopwatch) "■ stop & log" else "■ stop", color = p.red, onClick = { PomodoroService.send(ctx, PomodoroService.ACTION_STOP) })
            }
        }

        // ---- notification / live update status ----
        if (!notifGranted) {
            TerminalPanel(title = "notifications off", titleColor = p.red, borderColor = p.red.copy(alpha = 0.5f)) {
                Comment("the countdown only shows inside the app until notifications are allowed")
                Spacer(Modifier.height(6.dp))
                TermButton("open notification settings", color = p.red, onClick = { openNotificationSettings() })
            }
        } else if (!liveUpdatesOn) {
            TerminalPanel(title = "live updates off", titleColor = p.yellow, borderColor = p.yellow.copy(alpha = 0.5f)) {
                Comment("android 16 can pin the countdown to the status bar / lock screen (live update). it is disabled for this app")
                Spacer(Modifier.height(6.dp))
                TermButton("enable live updates", color = p.yellow, onClick = {
                    val i = PomodoroService.liveUpdateSettingsIntent(ctx)
                    if (i != null) runCatching { ctx.startActivity(i) } else openNotificationSettings()
                })
            }
        } else if (state.phase != Phase.IDLE) {
            Comment(
                if (Build.VERSION.SDK_INT >= 36) "countdown is live in the status bar & lock screen — this screen can be closed"
                else "countdown continues in the notification shade — this screen can be closed",
            )
        }

        // ---- habit binding ----
        TerminalPanel(title = "log minutes to") {
            if (timerHabits.isEmpty()) {
                Comment("no timer habits yet — create one with type=timer")
                Spacer(Modifier.height(6.dp))
                TermButton("habit add", onClick = { nav.navigate(Routes.habitEdit()) })
            } else {
                val enabled = state.phase == Phase.IDLE
                Text((if (selectedHabit == 0L) "(•) " else "( ) ") + "nothing (free session)", color = if (selectedHabit == 0L) p.green else p.fgDim,
                    modifier = Modifier.clickable(enabled = enabled) { selectedHabit = 0L }.padding(vertical = 3.dp), style = MaterialTheme.typography.bodyMedium)
                timerHabits.forEach { h ->
                    val sel = h.id == selectedHabit
                    val interval = if (h.focusMinutes > 0) "  ${h.focusMinutes}/${h.breakMinutes}" else ""
                    Text((if (sel) "(•) " else "( ) ") + h.name + "  ${h.target}m/day$interval", color = if (sel) p.named(h.color) else p.fgDim,
                        modifier = Modifier.clickable(enabled = enabled) { selectedHabit = h.id }.padding(vertical = 3.dp), style = MaterialTheme.typography.bodyMedium)
                }
                if (!enabled) Comment("stop the session to change the target habit")
            }
        }

        TerminalPanel(title = "ambience") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("sound", color = p.fgDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(10.dp))
                dev.personalterminal.timer.AmbientAudio.PATTERNS.forEach { m ->
                    val sel = settings.ambientSound == m
                    Text(
                        (if (sel) "[✓ " else "[  ") + m + "]",
                        color = if (sel) p.green else p.fgDim,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clickable {
                                scope.launch { app.prefs.setAmbient(m) }
                                dev.personalterminal.timer.AmbientAudio.setPattern(m)
                            }
                            .padding(horizontal = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("volume", color = p.fgDim, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.width(10.dp))
                Text("−", color = p.green, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable(enabled = settings.ambientVolume > 0) {
                        val v = (settings.ambientVolume - 10).coerceAtLeast(0)
                        scope.launch { app.prefs.setAmbientVolume(v) }; dev.personalterminal.timer.AmbientAudio.setVolume(v)
                    }.padding(horizontal = 8.dp))
                Text("${settings.ambientVolume}%", color = p.fg, style = MaterialTheme.typography.bodyMedium)
                Text("+", color = p.green, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable(enabled = settings.ambientVolume < 100) {
                        val v = (settings.ambientVolume + 10).coerceAtMost(100)
                        scope.launch { app.prefs.setAmbientVolume(v) }; dev.personalterminal.timer.AmbientAudio.setVolume(v)
                    }.padding(horizontal = 8.dp))
            }
            Comment("procedural rain / brown noise / key clicks play during focus, stop for breaks · volume rides on media")
        }

        TerminalPanel(title = "config") {
            Text("focus ${focusLen}m · break ${breakLen}m · long break ${settings.pomodoroLongBreakMin}m every 4" + (if (selected?.focusMinutes ?: 0 > 0) "  (per-habit)" else ""), color = p.fg, style = MaterialTheme.typography.bodyMedium)
            Comment("completed focus phases add minutes to the selected habit · stopping early credits whole minutes · every session lands on the heatmap")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                if (!dndAccess) runCatching { ctx.startActivity(dev.personalterminal.timer.FocusDnd.settingsIntent()) }
                else scope.launch { app.prefs.setDndDuringFocus(!settings.dndDuringFocus) }
            }.padding(vertical = 2.dp)) {
                Text(if (settings.dndDuringFocus && dndAccess) "[✓]" else "[ ]", color = if (settings.dndDuringFocus && dndAccess) p.green else p.fgDim, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("do-not-disturb during focus" + if (!dndAccess) "  (grant access)" else "", color = p.fg, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            TermButton("edit in settings", color = p.fgDim, onClick = { nav.navigate(Routes.SETTINGS) })
        }
        Spacer(Modifier.height(24.dp))
    }
    if (tabletopY != null) {
        // Flex Mode: the countdown stays on the top half (anchored at the hinge), controls and
        // panels below it – each half scrolls on its own so both are reachable without crossing
        // the fold.
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxWidth().height(tabletopY).statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = topContent,
            )
            Column(
                Modifier.weight(1f).fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = bottomContent,
            )
        }
    } else {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            topContent()
            bottomContent()
        }
    }
}
