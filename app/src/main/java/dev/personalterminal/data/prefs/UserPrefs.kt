package dev.personalterminal.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Settings(
    val themeName: String = "signal",
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val username: String = "user",
    val hostname: String = "android",
    val fontScale: Float = 1.0f,
    val pomodoroFocusMin: Int = 25,
    val pomodoroBreakMin: Int = 5,
    val pomodoroLongBreakMin: Int = 15,
    val autoBackup: Boolean = false,
    val backupIntervalHours: Int = 24,
    val lastBackupAt: Long = 0L,
    val lastBackupStatus: String = "",
    /** Size / photo count of the last uploaded archive and when it was last verified (0 = never). */
    val lastBackupBytes: Long = 0L,
    val lastBackupMedia: Int = 0,
    val lastVerifiedAt: Long = 0L,
    val lastVerifiedStatus: String = "",
    val driveAccountEmail: String = "",
    val driveFolderId: String = "",
    val onboarded: Boolean = false,
    val showBanner: Boolean = true,
    val crtEffect: Boolean = false,
    /** Reminders are muted between these two times (minutes after midnight). Equal values = no quiet hours. */
    val quietStartMin: Int = 22 * 60,
    val quietEndMin: Int = 7 * 60,
    val remindersEnabled: Boolean = true,
    /** Enable Do-Not-Disturb while a focus phase runs (needs notification-policy access). */
    val dndDuringFocus: Boolean = false,
    /** Monospace font: "jetbrains" | "fira" | "roboto" | "system". */
    val fontName: String = "jetbrains",
    /** True once the user picked a font by hand; themes then stop changing it. */
    val fontPinned: Boolean = false,
    /** Launcher icon variant id (see `LauncherIcons`). */
    val launcherIcon: String = "classic",
    /** JSON of a user-imported palette (empty = none). Selected when [themeName] == "custom". */
    val customPaletteJson: String = "",
    /** Passphrase used to encrypt archives ("" = plain zip). Never leaves the device. */
    val backupPassphrase: String = "",
    /** Larger touch targets / reduced motion / high contrast. */
    val accessibilityMode: Boolean = false,
    /** Health Connect auto-completion switch. */
    val healthConnect: Boolean = false,
    /** Epoch day of the last weekly review the user looked at (for the “new review” hint). */
    val lastReviewDay: Long = 0L,
    /** Whether first-launch restore-from-Drive was offered already. */
    val restoreOffered: Boolean = false,
    /** Which notifications the app may send (see [NotificationPrefs]). */
    val notifications: NotificationPrefs = NotificationPrefs(),
    /**
     * Compact Live Update: title = remaining time only, no progress bar / habit name / actions
     * text in the promoted surface (status-bar chip, ColorOS "Live Alerts" island, One UI Now Bar).
     * The full controls stay in the expanded notification.
     */
    val compactLiveUpdate: Boolean = true,
    /** Today grouped by time of day (morning / afternoon / evening / anytime) instead of by routine. */
    val todaySections: Boolean = false,
    /** "" = routine order · "strength" = weakest habits first inside each block. */
    val todaySort: String = "",
    /** Ambient focus sound: "off" | "rain" | "brown" | "keys" (procedurally generated, no assets). */
    val ambientSound: String = "off",
    /** Ambient mix level 0–100 (applied on top of the media volume). */
    val ambientVolume: Int = 60,
    /** Idle screensaver: "off" | "matrix" | "life" | "stars". */
    val saverPattern: String = "off",
    /** Minutes without input before the screensaver appears. */
    val saverAfterMin: Int = 5,
    /** Biometric / device-credential gate on open and after backgrounding. */
    val appLock: Boolean = false,
    /** Minutes in background before the app re-locks (0 = immediately on return). */
    val lockAfterMin: Int = 1,
    /** FLAG_SECURE: keep the app out of screenshots and the recents preview. */
    val secureWindow: Boolean = false,
) {
    val prompt: String get() = "$username@$hostname"
    val hasQuietHours: Boolean get() = quietStartMin != quietEndMin

    /** True when [minuteOfDay] falls inside the quiet-hours window (which may wrap midnight). */
    fun isQuiet(minuteOfDay: Int): Boolean {
        if (!hasQuietHours) return false
        return if (quietStartMin < quietEndMin) minuteOfDay in quietStartMin until quietEndMin
        else minuteOfDay >= quietStartMin || minuteOfDay < quietEndMin
    }
}

/**
 * Every notification the app can send, individually switchable from settings. [remindersEnabled]
 * in [Settings] stays the master switch for everything except the running-timer notification (which
 * is what keeps the foreground service alive) and Drive backup failures.
 *
 * Times are minutes after local midnight. Quiet hours apply to all of them.
 */
data class NotificationPrefs(
    /** "time to do it" nudge at each habit's own [dev.personalterminal.data.db.Habit.reminderMinutes]. */
    val habitReminders: Boolean = true,
    /** Evening "did you do X today?" for habits flagged with `checkIn`, sent once at [checkInMinutes]. */
    val habitCheckIn: Boolean = true,
    val checkInMinutes: Int = 20 * 60,
    /** Daily "which watch is on the wrist today?" if nothing was logged yet, at [wearLogMinutes]. */
    val wearLog: Boolean = false,
    val wearLogMinutes: Int = 9 * 60,
    /** Service / maintenance due notices for watches with a service interval. */
    val watchService: Boolean = true,
    /** Focus / break finished alert (sound + heads-up) from the pomodoro. */
    val timerAlerts: Boolean = true,
    /** One evening notice when a streak ≥ [streakRiskMinStreak] would break tonight, at [streakRiskMinutes]. */
    val streakRisk: Boolean = true,
    val streakRiskMinutes: Int = 21 * 60,
    val streakRiskMinStreak: Int = 3,
    /** Weekly review ready – Sunday evening at [weeklyReviewMinutes]. */
    val weeklyReview: Boolean = false,
    val weeklyReviewMinutes: Int = 18 * 60,
    /** One glanceable morning line (opt-in): habits today, watch suggestion, sleep, streaks at risk. */
    val morningBriefing: Boolean = false,
    val morningBriefingMinutes: Int = 7 * 60 + 30,
    /** Evening summary (opt-in): one `4/6 done · open: …` line at [eveningSummaryMinutes]; replaces per-habit check-ins when on. */
    val eveningSummary: Boolean = false,
    val eveningSummaryMinutes: Int = 21 * 60 + 30,
    /** Comeback nudge: one line after [dev.personalterminal.domain.Comeback.QUIET_DAYS] days without a log, with back-off. */
    val comebackNudge: Boolean = true,
    /** Stacked habits: nudge the follower the moment its anchor is ticked (`after` habits with anchorRemind). */
    val stackNudge: Boolean = true,
) {
    /** True when at least one *scheduled* (time-based) notification is on – used to arm the scheduler. */
    val anyScheduled: Boolean get() = habitReminders || habitCheckIn || wearLog || streakRisk || weeklyReview || morningBriefing || eveningSummary
}

class UserPrefs(private val context: Context) {
    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val MODE = stringPreferencesKey("mode")
        val USERNAME = stringPreferencesKey("username")
        val HOSTNAME = stringPreferencesKey("hostname")
        val FONT_SCALE = stringPreferencesKey("font_scale")
        val FOCUS = intPreferencesKey("pomo_focus")
        val BREAK = intPreferencesKey("pomo_break")
        val LONG_BREAK = intPreferencesKey("pomo_long_break")
        val AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        val BACKUP_INTERVAL = intPreferencesKey("backup_interval")
        val LAST_BACKUP = longPreferencesKey("last_backup")
        val LAST_BACKUP_STATUS = stringPreferencesKey("last_backup_status")
        val LAST_BACKUP_BYTES = longPreferencesKey("last_backup_bytes")
        val LAST_BACKUP_MEDIA = intPreferencesKey("last_backup_media")
        val LAST_VERIFIED = longPreferencesKey("last_verified")
        val LAST_VERIFIED_STATUS = stringPreferencesKey("last_verified_status")
        val DRIVE_EMAIL = stringPreferencesKey("drive_email")
        val DRIVE_FOLDER = stringPreferencesKey("drive_folder")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val BANNER = booleanPreferencesKey("banner")
        val CRT = booleanPreferencesKey("crt")
        val QUIET_START = intPreferencesKey("quiet_start")
        val QUIET_END = intPreferencesKey("quiet_end")
        val REMINDERS = booleanPreferencesKey("reminders")
        val DND_FOCUS = booleanPreferencesKey("dnd_focus")
        val FONT = stringPreferencesKey("font")
        val FONT_PINNED = booleanPreferencesKey("font_pinned")
        val LAUNCHER_ICON = stringPreferencesKey("launcher_icon")
        val CUSTOM_PALETTE = stringPreferencesKey("custom_palette")
        val BACKUP_PASSPHRASE = stringPreferencesKey("backup_passphrase")
        val A11Y = booleanPreferencesKey("a11y")
        val HEALTH = booleanPreferencesKey("health_connect")
        val LAST_REVIEW = longPreferencesKey("last_review_day")
        val RESTORE_OFFERED = booleanPreferencesKey("restore_offered")
        val N_HABIT = booleanPreferencesKey("n_habit_reminders")
        val N_CHECKIN = booleanPreferencesKey("n_check_in")
        val N_CHECKIN_MIN = intPreferencesKey("n_check_in_min")
        val N_WEAR = booleanPreferencesKey("n_wear_log")
        val N_WEAR_MIN = intPreferencesKey("n_wear_log_min")
        val N_SERVICE = booleanPreferencesKey("n_watch_service")
        val N_TIMER = booleanPreferencesKey("n_timer_alerts")
        val N_STREAK = booleanPreferencesKey("n_streak_risk")
        val N_STREAK_MIN = intPreferencesKey("n_streak_risk_min")
        val N_STREAK_LEN = intPreferencesKey("n_streak_risk_len")
        val N_REVIEW = booleanPreferencesKey("n_weekly_review")
        val N_REVIEW_MIN = intPreferencesKey("n_weekly_review_min")
        val N_BRIEFING = booleanPreferencesKey("n_morning_briefing")
        val N_BRIEFING_MIN = intPreferencesKey("n_morning_briefing_min")
        val N_SUMMARY = booleanPreferencesKey("n_evening_summary")
        val N_SUMMARY_MIN = intPreferencesKey("n_evening_summary_min")
        val N_COMEBACK = booleanPreferencesKey("n_comeback")
        val N_STACK = booleanPreferencesKey("n_stack")
        val TODAY_SORT = stringPreferencesKey("today_sort")
        val COMPACT_LIVE = booleanPreferencesKey("compact_live_update")
        val TODAY_SECTIONS = booleanPreferencesKey("today_sections")
        val AMBIENT = stringPreferencesKey("ambient")
        val AMBIENT_VOL = intPreferencesKey("ambient_vol")
        val SAVER = stringPreferencesKey("saver")
        val SAVER_AFTER = intPreferencesKey("saver_after_min")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val LOCK_AFTER = intPreferencesKey("lock_after_min")
        val SECURE_WINDOW = booleanPreferencesKey("secure_window")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            themeName = p[Keys.THEME] ?: "signal",
            themeMode = p[Keys.MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            username = p[Keys.USERNAME] ?: "user",
            hostname = p[Keys.HOSTNAME] ?: "android",
            fontScale = p[Keys.FONT_SCALE]?.toFloatOrNull() ?: 1.0f,
            pomodoroFocusMin = p[Keys.FOCUS] ?: 25,
            pomodoroBreakMin = p[Keys.BREAK] ?: 5,
            pomodoroLongBreakMin = p[Keys.LONG_BREAK] ?: 15,
            autoBackup = p[Keys.AUTO_BACKUP] ?: false,
            backupIntervalHours = p[Keys.BACKUP_INTERVAL] ?: 24,
            lastBackupAt = p[Keys.LAST_BACKUP] ?: 0L,
            lastBackupStatus = p[Keys.LAST_BACKUP_STATUS] ?: "",
            lastBackupBytes = p[Keys.LAST_BACKUP_BYTES] ?: 0L,
            lastBackupMedia = p[Keys.LAST_BACKUP_MEDIA] ?: 0,
            lastVerifiedAt = p[Keys.LAST_VERIFIED] ?: 0L,
            lastVerifiedStatus = p[Keys.LAST_VERIFIED_STATUS] ?: "",
            driveAccountEmail = p[Keys.DRIVE_EMAIL] ?: "",
            driveFolderId = p[Keys.DRIVE_FOLDER] ?: "",
            onboarded = p[Keys.ONBOARDED] ?: false,
            showBanner = p[Keys.BANNER] ?: true,
            crtEffect = p[Keys.CRT] ?: false,
            quietStartMin = p[Keys.QUIET_START] ?: (22 * 60),
            quietEndMin = p[Keys.QUIET_END] ?: (7 * 60),
            remindersEnabled = p[Keys.REMINDERS] ?: true,
            dndDuringFocus = p[Keys.DND_FOCUS] ?: false,
            fontName = p[Keys.FONT] ?: "jetbrains",
            fontPinned = p[Keys.FONT_PINNED] ?: false,
            launcherIcon = p[Keys.LAUNCHER_ICON] ?: "classic",
            customPaletteJson = p[Keys.CUSTOM_PALETTE] ?: "",
            backupPassphrase = p[Keys.BACKUP_PASSPHRASE] ?: "",
            accessibilityMode = p[Keys.A11Y] ?: false,
            healthConnect = p[Keys.HEALTH] ?: false,
            lastReviewDay = p[Keys.LAST_REVIEW] ?: 0L,
            restoreOffered = p[Keys.RESTORE_OFFERED] ?: false,
            notifications = NotificationPrefs(
                habitReminders = p[Keys.N_HABIT] ?: true,
                habitCheckIn = p[Keys.N_CHECKIN] ?: true,
                checkInMinutes = p[Keys.N_CHECKIN_MIN] ?: (20 * 60),
                wearLog = p[Keys.N_WEAR] ?: false,
                wearLogMinutes = p[Keys.N_WEAR_MIN] ?: (9 * 60),
                watchService = p[Keys.N_SERVICE] ?: true,
                timerAlerts = p[Keys.N_TIMER] ?: true,
                streakRisk = p[Keys.N_STREAK] ?: true,
                streakRiskMinutes = p[Keys.N_STREAK_MIN] ?: (21 * 60),
                streakRiskMinStreak = p[Keys.N_STREAK_LEN] ?: 3,
                weeklyReview = p[Keys.N_REVIEW] ?: false,
                weeklyReviewMinutes = p[Keys.N_REVIEW_MIN] ?: (18 * 60),
                morningBriefing = p[Keys.N_BRIEFING] ?: false,
                morningBriefingMinutes = p[Keys.N_BRIEFING_MIN] ?: (7 * 60 + 30),
                eveningSummary = p[Keys.N_SUMMARY] ?: false,
                eveningSummaryMinutes = p[Keys.N_SUMMARY_MIN] ?: (21 * 60 + 30),
                comebackNudge = p[Keys.N_COMEBACK] ?: true,
                stackNudge = p[Keys.N_STACK] ?: true,
            ),
            compactLiveUpdate = p[Keys.COMPACT_LIVE] ?: true,
            todaySections = p[Keys.TODAY_SECTIONS] ?: false,
            todaySort = p[Keys.TODAY_SORT] ?: "",
            ambientSound = p[Keys.AMBIENT] ?: "off",
            ambientVolume = p[Keys.AMBIENT_VOL] ?: 60,
            saverPattern = p[Keys.SAVER] ?: "off",
            saverAfterMin = p[Keys.SAVER_AFTER] ?: 5,
            appLock = p[Keys.APP_LOCK] ?: false,
            lockAfterMin = p[Keys.LOCK_AFTER] ?: 1,
            secureWindow = p[Keys.SECURE_WINDOW] ?: false,
        )
    }

    suspend fun current(): Settings = settings.first()

    /**
     * Selecting a theme also applies the typeface it was designed with – unless the user has picked a
     * font explicitly since (see [setFont]), in which case their choice sticks across themes.
     */
    suspend fun setTheme(name: String) = context.dataStore.edit {
        it[Keys.THEME] = name
        if (it[Keys.FONT_PINNED] != true) {
            dev.personalterminal.ui.theme.ThemeFamily.entries.firstOrNull { f -> f.id == name }?.let { f -> it[Keys.FONT] = f.font }
        }
    }
    suspend fun setThemeMode(mode: ThemeMode) = context.dataStore.edit { it[Keys.MODE] = mode.name }
    suspend fun setUsername(v: String) = context.dataStore.edit { it[Keys.USERNAME] = v.ifBlank { "user" } }
    suspend fun setHostname(v: String) = context.dataStore.edit { it[Keys.HOSTNAME] = v.ifBlank { "android" } }
    suspend fun setFontScale(v: Float) = context.dataStore.edit { it[Keys.FONT_SCALE] = v.toString() }
    suspend fun setPomodoro(focus: Int, brk: Int, longBrk: Int) = context.dataStore.edit {
        it[Keys.FOCUS] = focus; it[Keys.BREAK] = brk; it[Keys.LONG_BREAK] = longBrk
    }
    suspend fun setAutoBackup(enabled: Boolean) = context.dataStore.edit { it[Keys.AUTO_BACKUP] = enabled }
    suspend fun setBackupInterval(hours: Int) = context.dataStore.edit { it[Keys.BACKUP_INTERVAL] = hours }
    suspend fun setLastBackup(at: Long, status: String) = context.dataStore.edit {
        it[Keys.LAST_BACKUP] = at; it[Keys.LAST_BACKUP_STATUS] = status
    }
    suspend fun setBackupStatus(status: String) = context.dataStore.edit { it[Keys.LAST_BACKUP_STATUS] = status }
    suspend fun setBackupStats(bytes: Long, media: Int) = context.dataStore.edit { it[Keys.LAST_BACKUP_BYTES] = bytes; it[Keys.LAST_BACKUP_MEDIA] = media }
    suspend fun setBackupVerified(at: Long, status: String) = context.dataStore.edit { it[Keys.LAST_VERIFIED] = at; it[Keys.LAST_VERIFIED_STATUS] = status }
    suspend fun setDriveAccount(email: String, folderId: String) = context.dataStore.edit {
        it[Keys.DRIVE_EMAIL] = email; it[Keys.DRIVE_FOLDER] = folderId
    }
    suspend fun setOnboarded(v: Boolean) = context.dataStore.edit { it[Keys.ONBOARDED] = v }
    suspend fun setShowBanner(v: Boolean) = context.dataStore.edit { it[Keys.BANNER] = v }
    suspend fun setCrt(v: Boolean) = context.dataStore.edit { it[Keys.CRT] = v }
    suspend fun setQuietHours(startMin: Int, endMin: Int) = context.dataStore.edit { it[Keys.QUIET_START] = startMin; it[Keys.QUIET_END] = endMin }
    suspend fun setRemindersEnabled(v: Boolean) = context.dataStore.edit { it[Keys.REMINDERS] = v }
    suspend fun setDndDuringFocus(v: Boolean) = context.dataStore.edit { it[Keys.DND_FOCUS] = v }
    suspend fun setFont(name: String, pin: Boolean = true) = context.dataStore.edit { it[Keys.FONT] = name; it[Keys.FONT_PINNED] = pin }
    /** Back to "font follows theme". */
    suspend fun unpinFont(themeName: String) = context.dataStore.edit {
        it[Keys.FONT_PINNED] = false
        it[Keys.FONT] = dev.personalterminal.ui.theme.ThemeFamily.entries.firstOrNull { f -> f.id == themeName }?.font ?: "jetbrains"
    }
    suspend fun setLauncherIcon(id: String) = context.dataStore.edit { it[Keys.LAUNCHER_ICON] = id }
    suspend fun setCustomPalette(json: String) = context.dataStore.edit { it[Keys.CUSTOM_PALETTE] = json }
    suspend fun setBackupPassphrase(v: String) = context.dataStore.edit { it[Keys.BACKUP_PASSPHRASE] = v }
    suspend fun setAccessibilityMode(v: Boolean) = context.dataStore.edit { it[Keys.A11Y] = v }
    suspend fun setHealthConnect(v: Boolean) = context.dataStore.edit { it[Keys.HEALTH] = v }
    suspend fun setLastReviewDay(day: Long) = context.dataStore.edit { it[Keys.LAST_REVIEW] = day }
    suspend fun setRestoreOffered(v: Boolean) = context.dataStore.edit { it[Keys.RESTORE_OFFERED] = v }
    suspend fun setCompactLiveUpdate(v: Boolean) = context.dataStore.edit { it[Keys.COMPACT_LIVE] = v }
    suspend fun setTodaySections(v: Boolean) = context.dataStore.edit { it[Keys.TODAY_SECTIONS] = v }
    suspend fun setNotifications(n: NotificationPrefs) = context.dataStore.edit {
        it[Keys.N_HABIT] = n.habitReminders
        it[Keys.N_CHECKIN] = n.habitCheckIn; it[Keys.N_CHECKIN_MIN] = n.checkInMinutes
        it[Keys.N_WEAR] = n.wearLog; it[Keys.N_WEAR_MIN] = n.wearLogMinutes
        it[Keys.N_SERVICE] = n.watchService
        it[Keys.N_TIMER] = n.timerAlerts
        it[Keys.N_STREAK] = n.streakRisk; it[Keys.N_STREAK_MIN] = n.streakRiskMinutes; it[Keys.N_STREAK_LEN] = n.streakRiskMinStreak
        it[Keys.N_REVIEW] = n.weeklyReview; it[Keys.N_REVIEW_MIN] = n.weeklyReviewMinutes
        it[Keys.N_BRIEFING] = n.morningBriefing; it[Keys.N_BRIEFING_MIN] = n.morningBriefingMinutes
        it[Keys.N_SUMMARY] = n.eveningSummary; it[Keys.N_SUMMARY_MIN] = n.eveningSummaryMinutes
        it[Keys.N_COMEBACK] = n.comebackNudge; it[Keys.N_STACK] = n.stackNudge
    }

    /** Today ordering: "" (routine order) or "strength" (weakest first inside each block). */
    suspend fun setTodaySort(v: String) = context.dataStore.edit { it[Keys.TODAY_SORT] = v }

    suspend fun setAmbient(mode: String, volume: Int? = null) = context.dataStore.edit {
        it[Keys.AMBIENT] = mode
        if (volume != null) it[Keys.AMBIENT_VOL] = volume.coerceIn(0, 100)
    }
    suspend fun setAmbientVolume(v: Int) = context.dataStore.edit { it[Keys.AMBIENT_VOL] = v.coerceIn(0, 100) }
    suspend fun setSaver(pattern: String, afterMin: Int? = null) = context.dataStore.edit {
        it[Keys.SAVER] = pattern
        if (afterMin != null) it[Keys.SAVER_AFTER] = afterMin
    }
    suspend fun setSaverAfter(minutes: Int) = context.dataStore.edit { it[Keys.SAVER_AFTER] = minutes }
    suspend fun setAppLock(on: Boolean, afterMin: Int? = null) = context.dataStore.edit {
        it[Keys.APP_LOCK] = on
        if (afterMin != null) it[Keys.LOCK_AFTER] = afterMin
    }
    suspend fun setLockAfter(minutes: Int) = context.dataStore.edit { it[Keys.LOCK_AFTER] = minutes }
    suspend fun setSecureWindow(on: Boolean) = context.dataStore.edit { it[Keys.SECURE_WINDOW] = on }
}
