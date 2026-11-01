package dev.personalterminal.timer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.personalterminal.MainActivity
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Phase { IDLE, FOCUS, BREAK, LONG_BREAK, STOPWATCH }

data class TimerState(
    val phase: Phase = Phase.IDLE,
    val running: Boolean = false,
    val totalSeconds: Int = 0,
    val remainingSeconds: Int = 0,
    /** Pomodoros completed in this sitting (drives long-break cadence). */
    val cycle: Int = 0,
    /** Habit that receives the focus minutes when a session completes (0 = none). */
    val habitId: Long = 0,
    val habitName: String = "",
    /** Seconds of *focus* time accrued in the current focus phase (for partial credit on stop). */
    val focusedSeconds: Int = 0,
    /** Wall-clock time (epoch ms) at which the current phase ends. Only meaningful while [running]. */
    val endsAtMs: Long = 0L,
    /** Stopwatch: seconds elapsed (counts up, no end). */
    val elapsedSeconds: Int = 0,
    /** Epoch ms when the current phase started (for the session log). */
    val startedAtMs: Long = 0L,
) {
    val isStopwatch: Boolean get() = phase == Phase.STOPWATCH
    val fraction: Float get() = if (isStopwatch) (elapsedSeconds % 3600) / 3600f else if (totalSeconds == 0) 0f else 1f - remainingSeconds.toFloat() / totalSeconds
    val clock: String get() = formatClock(if (isStopwatch) elapsedSeconds else remainingSeconds)

    companion object {
        fun formatClock(seconds: Int): String {
            val h = seconds / 3600; val m = seconds / 60 % 60; val s = seconds % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
        }
    }
}

/**
 * Foreground service that owns the Pomodoro countdown so it survives the activity being backgrounded.
 * State is exposed as a process-wide [StateFlow] so the UI just collects it.
 */
class PomodoroService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    private var prefsJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        prefsJob = scope.launch {
            PersonalTerminalApp.get(this@PomodoroService).prefs.settings.collect {
                alertsEnabled = it.notifications.timerAlerts
                if (compact != it.compactLiveUpdate) { compact = it.compactLiveUpdate; if (_state.value.phase != Phase.IDLE) updateNotification() }
                AmbientAudio.setPattern(it.ambientSound)
                AmbientAudio.setVolume(it.ambientVolume)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_TICK) {
            // Heartbeat alarm, or START_STICKY re-delivery after the process was killed mid-session:
            // re-derive the phase from the persisted end time and repaint the notification.
            if (_state.value.phase == Phase.IDLE) restoreSession()
            resync()
            syncAmbient()
            return START_STICKY
        }
        when (intent.action) {
            ACTION_START -> {
                val focus = intent.getIntExtra(EXTRA_FOCUS_MIN, 25)
                val brk = intent.getIntExtra(EXTRA_BREAK_MIN, 5)
                val longBrk = intent.getIntExtra(EXTRA_LONG_BREAK_MIN, 15)
                val habitId = intent.getLongExtra(EXTRA_HABIT_ID, 0)
                val habitName = intent.getStringExtra(EXTRA_HABIT_NAME) ?: ""
                config = Config(focus, brk, longBrk)
                startPhase(Phase.FOCUS, habitId, habitName)
            }
            ACTION_STOPWATCH -> {
                val habitId = intent.getLongExtra(EXTRA_HABIT_ID, 0)
                val habitName = intent.getStringExtra(EXTRA_HABIT_NAME) ?: ""
                startStopwatch(habitId, habitName)
            }
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_SKIP -> skip()
            ACTION_STOP -> stopSession(creditPartial = true)
        }
        syncAmbient()
        return START_STICKY
    }

    private fun startStopwatch(habitId: Long, habitName: String) {
        _state.value = TimerState(
            phase = Phase.STOPWATCH, running = true, habitId = habitId, habitName = habitName,
            startedAtMs = System.currentTimeMillis(), endsAtMs = System.currentTimeMillis(),
        )
        goForeground()
        startTicker()
        persistSession()
        FocusDnd.enter(this)
        syncAmbient()
    }

    private data class Config(val focus: Int, val brk: Int, val longBrk: Int)
    private var config = Config(25, 5, 15)
    /** Mirrors `settings.notifications.timerAlerts` (kept fresh while the service lives). */
    @Volatile private var alertsEnabled = true
    /** Mirrors `settings.compactLiveUpdate`. */
    @Volatile private var compact = true

    private fun startPhase(phase: Phase, habitId: Long = _state.value.habitId, habitName: String = _state.value.habitName) {
        val minutes = when (phase) {
            Phase.FOCUS -> config.focus
            Phase.BREAK -> config.brk
            Phase.LONG_BREAK -> config.longBrk
            Phase.IDLE, Phase.STOPWATCH -> 0
        }
        val secs = minutes * 60
        _state.value = _state.value.copy(
            phase = phase, running = true, totalSeconds = secs, remainingSeconds = secs,
            habitId = habitId, habitName = habitName, focusedSeconds = 0, elapsedSeconds = 0,
            endsAtMs = System.currentTimeMillis() + secs * 1000L, startedAtMs = System.currentTimeMillis(),
        )
        goForeground()
        startTicker()
        persistSession()
        if (phase == Phase.FOCUS) FocusDnd.enter(this) else FocusDnd.exit(this)
        syncAmbient()
    }

    // ------------------------------------------------------------------ resilience

    /**
     * Brings the in-memory state in line with the wall clock. Called from the heartbeat alarm and
     * on process restart: if the device dozed or ColorOS froze the process, the ticker missed
     * seconds and the compact title (a snapshot of the clock) went stale on the island while the
     * system chronometer kept counting – this repaints it and finishes an overdue phase.
     */
    private fun resync() {
        val s = _state.value
        if (s.phase == Phase.IDLE) return
        if (!s.running) { updateNotification(); return }
        if (s.isStopwatch) {
            val elapsed = ((System.currentTimeMillis() - s.endsAtMs) / 1000).toInt().coerceAtLeast(0)
            _state.value = s.copy(elapsedSeconds = elapsed, focusedSeconds = elapsed)
            updateNotification()
        } else {
            val remaining = ((s.endsAtMs - System.currentTimeMillis() + 999) / 1000).toInt().coerceIn(0, s.totalSeconds)
            val focused = if (s.phase == Phase.FOCUS) s.totalSeconds - remaining else s.focusedSeconds
            _state.value = s.copy(remainingSeconds = remaining, focusedSeconds = focused)
            if (remaining == 0) { onPhaseFinished(); return }
            updateNotification()
        }
        if (ticker?.isActive != true) startTicker()
        scheduleHeartbeat()
    }

    /**
     * Exact alarm every 30 s (and precisely at the phase end) while running. An alarm delivery
     * unfreezes the process, so even under aggressive OEM battery management the notification is
     * repainted and the phase flips on time. Falls back to an inexact alarm where exact ones are
     * not permitted.
     */
    private fun scheduleHeartbeat() {
        val s = _state.value
        if (!s.running || s.phase == Phase.IDLE) { cancelHeartbeat(); return }
        val now = System.currentTimeMillis()
        var at = now + HEARTBEAT_MS
        if (!s.isStopwatch && s.endsAtMs in (now + 1000)..at) at = s.endsAtMs + 300
        val am = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val pi = heartbeatIntent()
        runCatching {
            val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exact) am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
        }.onFailure { runCatching { am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi) } }
    }

    private fun cancelHeartbeat() {
        runCatching { (getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager).cancel(heartbeatIntent()) }
    }

    private fun heartbeatIntent(): PendingIntent = PendingIntent.getService(
        this, HEARTBEAT_RC, Intent(this, PomodoroService::class.java).setAction(ACTION_TICK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Persists what is needed to rebuild the session after a process kill (see [restoreSession]). */
    private fun persistSession() {
        val s = _state.value
        val prefs = getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE).edit()
        if (s.phase == Phase.IDLE) { prefs.clear().apply(); return }
        prefs.putString("phase", s.phase.name).putBoolean("running", s.running)
            .putInt("total", s.totalSeconds).putInt("remaining", s.remainingSeconds).putInt("cycle", s.cycle)
            .putLong("habitId", s.habitId).putString("habitName", s.habitName)
            .putLong("endsAt", s.endsAtMs).putLong("startedAt", s.startedAtMs).putInt("elapsed", s.elapsedSeconds)
            .putInt("cfgFocus", config.focus).putInt("cfgBreak", config.brk).putInt("cfgLong", config.longBrk)
            .apply()
    }

    /** Rebuilds a running session from [persistSession] data – only if it can still be meaningful. */
    private fun restoreSession() {
        val p = getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        val phase = runCatching { Phase.valueOf(p.getString("phase", null) ?: return) }.getOrNull() ?: return
        if (phase == Phase.IDLE) return
        val endsAt = p.getLong("endsAt", 0L)
        val running = p.getBoolean("running", false)
        // A countdown that ended more than 10 minutes ago is history, not a session to resume.
        if (phase != Phase.STOPWATCH && running && endsAt < System.currentTimeMillis() - 10 * 60_000L) { p.edit().clear().apply(); return }
        config = Config(p.getInt("cfgFocus", 25), p.getInt("cfgBreak", 5), p.getInt("cfgLong", 15))
        _state.value = TimerState(
            phase = phase, running = running, totalSeconds = p.getInt("total", 0), remainingSeconds = p.getInt("remaining", 0),
            cycle = p.getInt("cycle", 0), habitId = p.getLong("habitId", 0L), habitName = p.getString("habitName", "") ?: "",
            endsAtMs = endsAt, startedAtMs = p.getLong("startedAt", 0L), elapsedSeconds = p.getInt("elapsed", 0),
            focusedSeconds = if (phase == Phase.STOPWATCH) p.getInt("elapsed", 0) else 0,
        )
        goForeground()
        if (running) { startTicker(); scheduleHeartbeat() }
        syncAmbient()
    }

    /**
     * Ticks once per wall-clock second. Remaining time is always derived from [TimerState.endsAtMs]
     * rather than accumulated, so it cannot drift while the device dozes and it stays in sync with
     * the chronometer / status-chip countdown the system renders from the same end time.
     */
    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                delay(1000 - now % 1000) // align to the next second boundary
                try {
                    val s = _state.value
                    if (!s.running) continue
                    if (s.isStopwatch) {
                        val elapsed = ((System.currentTimeMillis() - s.endsAtMs) / 1000).toInt().coerceAtLeast(0)
                        if (elapsed != s.elapsedSeconds) {
                            _state.value = s.copy(elapsedSeconds = elapsed, focusedSeconds = elapsed)
                            if (elapsed % 5 == 0) updateNotification()
                        }
                        continue
                    }
                    val remaining = ((s.endsAtMs - System.currentTimeMillis() + 999) / 1000).toInt().coerceIn(0, s.totalSeconds)
                    if (remaining == s.remainingSeconds) continue
                    val focused = if (s.phase == Phase.FOCUS) s.totalSeconds - remaining else s.focusedSeconds
                    _state.value = s.copy(remainingSeconds = remaining, focusedSeconds = focused)
                    if (remaining == 0) onPhaseFinished() else updateNotification()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("PomodoroService", "tick failed: ${e.message}") // never let one bad tick kill the countdown
                }
            }
        }
        holdWakeLock(true)
        scheduleHeartbeat()
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /** Partial wake lock while a phase is running: keeps the one-second ticker honest under doze. */
    private fun holdWakeLock(hold: Boolean) {
        runCatching {
            if (hold) {
                if (wakeLock?.isHeld == true) return
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "personalterminal:timer").also {
                    it.setReferenceCounted(false)
                    it.acquire(4 * 60 * 60 * 1000L) // hard cap: 4 h, in case stop() is never reached
                }
            } else {
                wakeLock?.takeIf { it.isHeld }?.release()
                wakeLock = null
            }
        }
    }

    private fun onPhaseFinished() {
        val s = _state.value
        if (alertsEnabled) { buzz(); postPhaseDoneAlert(s) }
        if (s.phase == Phase.FOCUS) {
            recordSession(s, config.focus, completed = true)
            val cycle = s.cycle + 1
            _state.value = s.copy(cycle = cycle)
            startPhase(if (cycle % 4 == 0) Phase.LONG_BREAK else Phase.BREAK)
        } else {
            startPhase(Phase.FOCUS)
        }
    }

    /** Persists the session (heatmap / history) and credits whole minutes to the bound habit. */
    private fun recordSession(s: TimerState, minutes: Int, completed: Boolean) {
        if (minutes <= 0) return
        val app = PersonalTerminalApp.get(this)
        val kind = if (s.isStopwatch) dev.personalterminal.data.db.FocusSession.KIND_STOPWATCH else dev.personalterminal.data.db.FocusSession.KIND_FOCUS
        val started = if (s.startedAtMs > 0) s.startedAtMs else System.currentTimeMillis() - minutes * 60_000L
        scope.launch { app.habits.recordSession(s.habitId, started, System.currentTimeMillis(), minutes, kind, completed) }
    }

    private fun pause() {
        val s = _state.value
        if (!s.running) return
        holdWakeLock(false)
        cancelHeartbeat()
        if (s.isStopwatch) { _state.value = s.copy(running = false); updateNotification(); persistSession(); syncAmbient(); return }
        val remaining = ((s.endsAtMs - System.currentTimeMillis() + 999) / 1000).toInt().coerceIn(0, s.totalSeconds)
        _state.value = s.copy(running = false, remainingSeconds = remaining)
        updateNotification()
        persistSession()
        syncAmbient()
    }

    private fun resume() {
        val s = _state.value
        if (s.running || s.phase == Phase.IDLE) return
        if (s.isStopwatch) {
            // endsAtMs doubles as the "virtual start" for the stopwatch: shift it so elapsed continues.
            _state.value = s.copy(running = true, endsAtMs = System.currentTimeMillis() - s.elapsedSeconds * 1000L)
        } else {
            _state.value = s.copy(running = true, endsAtMs = System.currentTimeMillis() + s.remainingSeconds * 1000L)
        }
        holdWakeLock(true)
        scheduleHeartbeat()
        updateNotification()
        persistSession()
        syncAmbient()
    }

    private fun skip() {
        val s = _state.value
        when (s.phase) {
            Phase.STOPWATCH -> stopSession(creditPartial = true)
            Phase.FOCUS -> {
                // partial credit for whole minutes focused
                recordSession(s, s.focusedSeconds / 60, completed = false)
                startPhase(Phase.BREAK)
            }
            else -> startPhase(Phase.FOCUS)
        }
    }

    /** Ambient sound follows the timer: play while focused and running, pause with it, else silence. */
    private fun syncAmbient() {
        val s = _state.value
        val focusPhase = s.phase == Phase.FOCUS || s.isStopwatch
        when {
            !focusPhase -> AmbientAudio.focusOff()
            s.running -> AmbientAudio.focusOn()
            else -> AmbientAudio.pause()
        }
    }

    private fun stopSession(creditPartial: Boolean) {
        val s = _state.value
        if (creditPartial && (s.phase == Phase.FOCUS || s.isStopwatch)) recordSession(s, s.focusedSeconds / 60, completed = s.isStopwatch)
        ticker?.cancel()
        holdWakeLock(false)
        cancelHeartbeat()
        _state.value = TimerState()
        persistSession()
        FocusDnd.exit(this)
        syncAmbient()
        NotificationManagerCompat.from(this).cancel(ALERT_NOTIF_ID)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ notification

    private fun goForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification() {
        // The FGS notification is exempt from the runtime permission, but a plain notify() is not.
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification()) }
    }

    /**
     * Ongoing countdown notification, built to qualify as an Android 16 **Live Update** (promoted
     * ongoing notification): ProgressStyle, ongoing, titled, not colorized, promotion requested.
     * On Android 16+ that puts the timer in the status-bar chip / lock screen (and OEM surfaces
     * such as ColorOS 16 "Live Alerts" or One UI's Now Bar); older versions get a regular ongoing
     * notification whose header chronometer counts down on its own between updates.
     */
    private fun buildNotification(): Notification {
        val s = _state.value
        val phaseLabel = when (s.phase) {
            Phase.FOCUS -> "focus"
            Phase.BREAK -> "break"
            Phase.LONG_BREAK -> "long break"
            Phase.STOPWATCH -> "stopwatch"
            Phase.IDLE -> "idle"
        }
        val glyph = when {
            !s.running -> "‖"
            s.phase == Phase.FOCUS || s.isStopwatch -> "▶"
            else -> "☕"
        }
        // Compact mode keeps the promoted surface (island / chip) to icon + time: the title *is* the
        // countdown, everything else moves to the second line which those surfaces don't show.
        // (OEM capsules render the title; the AOSP chip renders the chronometer set below – both
        // are fed, so whichever the device shows keeps counting.)
        val title = if (compact) (if (s.running) s.clock else "${s.clock} ‖") else "$phaseLabel $glyph ${s.clock}" + if (!s.running) " (paused)" else ""
        val bar = if (s.isStopwatch) "elapsed" else asciiBar(s.fraction, 16)
        val text = buildString {
            if (compact) { append(phaseLabel); if (!s.running) append(" · paused") } else append(bar)
            if (s.habitName.isNotBlank()) append("  ").append(s.habitName)
            if (s.cycle > 0) append("  🍅×").append(s.cycle)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).apply { putExtra(MainActivity.EXTRA_ROUTE, "timer") },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(action: String, label: String) = NotificationCompat.Action.Builder(
            0, label,
            PendingIntent.getService(this, action.hashCode(), Intent(this, PomodoroService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        ).build()
        val accent = ContextCompat.getColor(this, if (s.phase == Phase.FOCUS) R.color.timer_focus else R.color.timer_break)
        val elapsed = (s.totalSeconds - s.remainingSeconds).coerceIn(0, s.totalSeconds)
        val style = NotificationCompat.ProgressStyle()
            .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(s.totalSeconds.coerceAtLeast(1)).setColor(accent)))
            .setProgress(elapsed)
            .setStyledByProgress(!compact) // compact: no coloured fill, just the thin track

        val b = NotificationCompat.Builder(this, PersonalTerminalApp.CHANNEL_TIMER)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accent)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Android 16 Live Update opt-in (ignored on older versions).
            .setRequestPromotedOngoing(true)
            .addAction(if (s.running) action(ACTION_PAUSE, "pause") else action(ACTION_RESUME, "resume"))
        if (!s.isStopwatch) b.addAction(action(ACTION_SKIP, "skip"))
        b.addAction(action(ACTION_STOP, "stop"))
        if (s.isStopwatch) {
            b.setStyle(null)
            if (s.running) b.setWhen(s.endsAtMs).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(false)
            else b.setShowWhen(false).setUsesChronometer(false)
        } else if (s.running) {
            // Header chronometer counts down to the phase end without further updates from us, and
            // the Live Update status chip shows the same ticking countdown (chip text is only used
            // when no chronometer is running, so leave shortCriticalText unset here).
            b.setWhen(s.endsAtMs).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            b.setShowWhen(false).setUsesChronometer(false).setShortCriticalText("paused")
        }
        return b.build()
    }

    /** One-shot, audible "phase finished" alert on the high-importance channel (heads-up + lock screen). */
    private fun postPhaseDoneAlert(finished: TimerState) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val next = when {
            finished.phase != Phase.FOCUS -> "focus"
            (finished.cycle + 1) % 4 == 0 -> "long break"
            else -> "break"
        }
        val title = if (finished.phase == Phase.FOCUS) "focus complete ✓" else "break over"
        val text = if (finished.phase == Phase.FOCUS) {
            "+${config.focus} min" + (if (finished.habitName.isNotBlank()) " → ${finished.habitName}" else "") + " · $next starts now"
        } else "back to work · $next starts now"
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java).apply { putExtra(MainActivity.EXTRA_ROUTE, "timer") },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, PersonalTerminalApp.CHANNEL_TIMER_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(2 * 60_000L)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(ALERT_NOTIF_ID, n) }
    }

    private fun buzz() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 100, 200, 100, 400), -1)) }
    }

    override fun onDestroy() {
        ticker?.cancel()
        prefsJob?.cancel()
        holdWakeLock(false)
        // Keep the heartbeat armed when a *running* session is being torn down by the system: the
        // next alarm restarts the service and restoreSession() picks the countdown back up.
        if (!_state.value.running) cancelHeartbeat()
        super.onDestroy()
    }

    companion object {
        private const val NOTIF_ID = 1001
        private const val ALERT_NOTIF_ID = 1002
        const val ACTION_START = "dev.personalterminal.timer.START"
        const val ACTION_PAUSE = "dev.personalterminal.timer.PAUSE"
        const val ACTION_RESUME = "dev.personalterminal.timer.RESUME"
        const val ACTION_SKIP = "dev.personalterminal.timer.SKIP"
        const val ACTION_STOP = "dev.personalterminal.timer.STOP"
        const val ACTION_STOPWATCH = "dev.personalterminal.timer.STOPWATCH"
        const val ACTION_TICK = "dev.personalterminal.timer.TICK"
        private const val HEARTBEAT_MS = 30_000L
        private const val HEARTBEAT_RC = 7001
        private const val SESSION_PREFS = "timer_session"
        const val EXTRA_FOCUS_MIN = "focus"
        const val EXTRA_BREAK_MIN = "break"
        const val EXTRA_LONG_BREAK_MIN = "long_break"
        const val EXTRA_HABIT_ID = "habit_id"
        const val EXTRA_HABIT_NAME = "habit_name"

        private val _state = MutableStateFlow(TimerState())
        val state: StateFlow<TimerState> = _state.asStateFlow()

        fun start(context: Context, focusMin: Int, breakMin: Int, longBreakMin: Int, habitId: Long, habitName: String) {
            val i = Intent(context, PomodoroService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_FOCUS_MIN, focusMin).putExtra(EXTRA_BREAK_MIN, breakMin).putExtra(EXTRA_LONG_BREAK_MIN, longBreakMin)
                .putExtra(EXTRA_HABIT_ID, habitId).putExtra(EXTRA_HABIT_NAME, habitName)
            context.startForegroundService(i)
        }

        fun startStopwatch(context: Context, habitId: Long, habitName: String) {
            val i = Intent(context, PomodoroService::class.java).setAction(ACTION_STOPWATCH)
                .putExtra(EXTRA_HABIT_ID, habitId).putExtra(EXTRA_HABIT_NAME, habitName)
            context.startForegroundService(i)
        }

        /**
         * Starts a focus session for [habit] (or a free one) using the habit's own focus/break
         * lengths when set, otherwise the global pomodoro settings.
         */
        suspend fun startFor(context: Context, habitId: Long) {
            val app = PersonalTerminalApp.get(context)
            val s = app.prefs.current()
            val h = if (habitId != 0L) app.habits.habit(habitId) else null
            val focus = h?.focusMinutes?.takeIf { it > 0 } ?: s.pomodoroFocusMin
            val brk = h?.breakMinutes?.takeIf { it > 0 } ?: s.pomodoroBreakMin
            start(context, focus, brk, s.pomodoroLongBreakMin, h?.id ?: 0L, h?.name ?: "")
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, PomodoroService::class.java).setAction(action))
        }

        fun asciiBar(fraction: Float, width: Int): String {
            val filled = (fraction.coerceIn(0f, 1f) * width).toInt()
            return "█".repeat(filled) + "░".repeat(width - filled)
        }

        /**
         * Whether the OS will *promote* our timer notification (Android 16 Live Updates). `true` on
         * older versions, where the question does not arise. False means the user switched Live
         * Updates off for this app – see [liveUpdateSettingsIntent].
         */
        fun canPostLiveUpdates(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 36) return true
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            return runCatching { nm.canPostPromotedNotifications() }.getOrDefault(true)
        }

        /** Deep link to the per-app "Live Updates" toggle, or null when this OS build has no such screen. */
        fun liveUpdateSettingsIntent(context: Context): Intent? {
            if (Build.VERSION.SDK_INT < 36) return null
            val i = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            return i.takeIf { it.resolveActivity(context.packageManager) != null }
        }
    }
}
