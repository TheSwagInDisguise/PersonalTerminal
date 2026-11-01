package dev.personalterminal.ui.settings

import android.app.Activity
import android.app.PendingIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.personalterminal.BuildConfig
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.backup.BackupManager
import dev.personalterminal.data.prefs.Settings
import dev.personalterminal.data.prefs.ThemeMode
import dev.personalterminal.sync.DriveClient
import dev.personalterminal.sync.DriveSync
import dev.personalterminal.sync.GoogleAuth
import dev.personalterminal.ui.components.Comment
import dev.personalterminal.ui.components.KeyValue
import dev.personalterminal.ui.components.PromptLine
import dev.personalterminal.ui.components.RadioRow
import dev.personalterminal.ui.components.Stepper
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.components.TermTextField
import dev.personalterminal.ui.components.TerminalPanel
import dev.personalterminal.ui.theme.Palettes
import dev.personalterminal.ui.theme.Term
import dev.personalterminal.ui.theme.ThemeFamily
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SettingsScreen(
    app: PersonalTerminalApp,
    nav: NavHostController,
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    /** Test hook: scroll so the end of the appearance panel (font + icon pickers) is on screen. */
    scrollToAppearanceEnd: Boolean = false,
) {
    val p = Term.palette
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by app.prefs.settings.collectAsStateWithLifecycle(initialValue = Settings())
    var username by remember(settings.username) { mutableStateOf(settings.username) }
    var hostname by remember(settings.hostname) { mutableStateOf(settings.hostname) }
    var log by remember { mutableStateOf(listOf<String>()) }
    fun say(msg: String) { log = (log + "${DateTimeFormatter.ofPattern("HH:mm:ss").format(java.time.LocalTime.now())} $msg").takeLast(8) }
    var busy by remember { mutableStateOf(false) }
    var remote by remember { mutableStateOf<List<DriveClient.RemoteBackup>?>(null) }
    var confirmRestoreId by remember { mutableStateOf<String?>(null) }

    // ---- local export/import (Storage Access Framework) ----
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupManager.MIME)) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { app.backups.writeArchive(it) } }
                .onSuccess { say("exported archive with $it media files") }.onFailure { say("export failed: ${it.message}") }
            busy = false
        }
    }
    var pendingRestoreUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var restorePass by remember { mutableStateOf("") }
    suspend fun restoreFrom(uri: android.net.Uri, pass: String?) {
        busy = true
        runCatching { ctx.contentResolver.openInputStream(uri)!!.use { app.backups.restoreArchive(it, pass) } }
            .onSuccess { say(it); pendingRestoreUri = null }
            .onFailure { e -> if (e is BackupManager.PassphraseRequired) { pendingRestoreUri = uri; say(e.message ?: "passphrase required") } else say("restore failed: ${e.message}") }
        busy = false
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { restoreFrom(uri, null) }
    }
    val handedOver by app.pendingImport.collectAsStateWithLifecycle()
    val paletteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { ctx.contentResolver.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) } }
                .onSuccess { json ->
                    if (dev.personalterminal.ui.theme.CustomPalette.parse(json) == null) say("could not parse colour scheme (expected Windows Terminal / base16 / personal-terminal JSON)")
                    else { app.prefs.setCustomPalette(json); app.prefs.setTheme(Palettes.CUSTOM_ID); say("colour scheme imported") }
                }.onFailure { say("import failed: ${it.message}") }
        }
    }
    val paletteExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(dev.personalterminal.ui.theme.CustomPalette.export(p).toByteArray()) } }
                .onSuccess { say("colour scheme exported") }.onFailure { say("export failed: ${it.message}") }
        }
    }
    val healthPermissionLauncher = rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        scope.launch {
            val on = granted.isNotEmpty()
            app.prefs.setHealthConnect(on)
            dev.personalterminal.health.HealthSync.schedule(ctx, on)
            if (on) { val r = dev.personalterminal.health.HealthSync.syncNow(ctx); say(if (r.isEmpty()) "health connect linked" else r.joinToString("; ")) } else say("no health permissions granted")
        }
    }
    // ---- Drive consent resolution ----
    var pendingAfterConsent by remember { mutableStateOf<(suspend (String) -> Unit)?>(null) }
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val token = if (result.resultCode == Activity.RESULT_OK) app.googleAuth.tokenFromConsentResult(result.data) else null
        val cont = pendingAfterConsent; pendingAfterConsent = null
        if (token != null && cont != null) scope.launch { busy = true; cont(token); busy = false } else say("drive consent not granted")
    }
    fun handleConsent(intent: android.content.Intent, then: suspend (String) -> Unit) {
        @Suppress("DEPRECATION")
        val pi = intent.getParcelableExtra<PendingIntent>(GoogleAuth.EXTRA_PENDING_INTENT)
        if (pi == null) { say("no consent intent"); return }
        pendingAfterConsent = then
        consentLauncher.launch(IntentSenderRequest.Builder(pi.intentSender).build())
    }
    suspend fun doBackup(token: String? = null) {
        when (val r = app.driveSync.backupNow(token)) {
            is DriveSync.Outcome.Success -> say("backup ok: ${r.message}")
            is DriveSync.Outcome.Failure -> say("backup failed: ${r.message}")
            is DriveSync.Outcome.NeedsConsent -> handleConsent(r.intent) { t -> doBackup(t) }
        }
    }
    suspend fun doVerify(token: String? = null) {
        when (val r = app.driveSync.verifyLatest(token)) {
            is DriveSync.Outcome.Success -> say("verify ${r.message}")
            is DriveSync.Outcome.Failure -> say("verify failed: ${r.message}")
            is DriveSync.Outcome.NeedsConsent -> handleConsent(r.intent) { t -> doVerify(t) }
        }
    }
    suspend fun doList(token: String? = null) {
        app.driveSync.listRemote(token).onSuccess { remote = it; say("found ${it.size} backups in Drive") }
            .onFailure { e -> if (e is DriveSync.ConsentRequired) handleConsent(e.intent) { t -> doList(t) } else say("list failed: ${e.message}") }
    }
    suspend fun doRestore(id: String, token: String? = null) {
        when (val r = app.driveSync.restoreRemote(id, token)) {
            is DriveSync.Outcome.Success -> say(r.message)
            is DriveSync.Outcome.Failure -> say("restore failed: ${r.message}")
            is DriveSync.Outcome.NeedsConsent -> handleConsent(r.intent) { t -> doRestore(id, t) }
        }
    }

    var appearanceEnd by remember { mutableStateOf(0) }
    LaunchedEffect(scrollToAppearanceEnd, appearanceEnd) { if (scrollToAppearanceEnd && appearanceEnd > 0) scrollState.scrollTo((appearanceEnd - 1500).coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(scrollState).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PromptLine("settings", trailing = "v${BuildConfig.VERSION_NAME.removeSuffix("-local")}")

        // ---------------- appearance ----------------
        TerminalPanel(title = "appearance") {
            Text("theme:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                ThemeFamily.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { f -> ThemeCard(f, settings.themeName == f.id, p.dark, Modifier.weight(1f)) { scope.launch { app.prefs.setTheme(f.id) } } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val customOk = settings.customPaletteJson.isNotBlank()
                Text((if (settings.themeName == Palettes.CUSTOM_ID) "(•) " else "( ) ") + "custom" + (if (customOk) "" else " (none imported)"), color = if (settings.themeName == Palettes.CUSTOM_ID) p.green else p.fgDim, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(enabled = customOk) { scope.launch { app.prefs.setTheme(Palettes.CUSTOM_ID) } }.padding(2.dp))
                Spacer(Modifier.weight(1f))
                TermButton("import .json", color = p.cyan, onClick = { paletteLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) })
                TermButton("export", color = p.fgDim, onClick = { paletteExportLauncher.launch("${settings.themeName}-scheme.json") })
            }
            Comment("accepts Windows Terminal, iTerm-style and base16 JSON schemes")
            Spacer(Modifier.height(8.dp))
            Text("mode:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
            RadioRow(options = ThemeMode.entries, selected = settings.themeMode, onSelect = { scope.launch { app.prefs.setThemeMode(it) } }, label = { it.name.lowercase() })
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("font:", color = p.fgDim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(
                    if (settings.fontPinned) "[ follow theme ]" else "follows theme",
                    color = if (settings.fontPinned) p.cyan else p.fgDim, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clickable(enabled = settings.fontPinned) { scope.launch { app.prefs.unpinFont(settings.themeName) } }.padding(2.dp),
                )
            }
            Column {
                dev.personalterminal.ui.theme.Fonts.options.forEach { f ->
                    val sel = settings.fontName == f.id
                    Column(Modifier.fillMaxWidth().clickable { scope.launch { app.prefs.setFont(f.id) } }.padding(vertical = 3.dp)) {
                        Text((if (sel) "(•) " else "( ) ") + f.label + "  quick fox 0O1lI", color = if (sel) p.green else p.fg,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = f.family, fontSize = MaterialTheme.typography.bodyMedium.fontSize * dev.personalterminal.ui.theme.Fonts.sizeFactor(f.id)),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("    " + f.blurb, color = p.fgDim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Comment(if (settings.fontPinned) "pinned – themes keep this font until you tap `follow theme`" else "each theme brings its own typeface; pick one here to pin it")
            Spacer(Modifier.height(8.dp))
            Text("icon:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
            IconPicker(app, settings.launcherIcon, say = { say(it) })
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("font scale: ", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                listOf(0.85f, 1f, 1.15f, 1.3f).forEach { sc ->
                    val sel = kotlin.math.abs(settings.fontScale - sc) < 0.01f
                    Text((if (sel) "[${(sc * 100).toInt()}%]" else " ${(sc * 100).toInt()}% "), color = if (sel) p.green else p.fgDim, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { scope.launch { app.prefs.setFontScale(sc) } }.padding(2.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            ToggleLine("today: group by time of day (morning / afternoon / evening) instead of routine", settings.todaySections) { scope.launch { app.prefs.setTodaySections(it) } }
            ToggleLine("today: weakest habits first (by strength) inside each block", settings.todaySort == "strength") { scope.launch { app.prefs.setTodaySort(if (it) "strength" else "") } }
            ToggleLine("crt scanlines & glow", settings.crtEffect) { scope.launch { app.prefs.setCrt(it) } }
            ToggleLine("accessibility: bigger targets, higher contrast, no animations", settings.accessibilityMode) { scope.launch { app.prefs.setAccessibilityMode(it) } }
            Spacer(Modifier.height(1.dp).onGloballyPositioned { appearanceEnd = it.positionInRoot().y.toInt() + scrollState.value })
        }

        // ---------------- notifications ----------------
        NotificationsPanel(app, settings, say = { say(it) })

        // ---------------- identity ----------------
        TerminalPanel(title = "prompt") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermTextField(value = username, onValueChange = { username = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }.take(16) }, label = "user", modifier = Modifier.weight(1f))
                TermTextField(value = hostname, onValueChange = { hostname = it.filter { c -> c.isLetterOrDigit() || c == '-' }.take(16) }, label = "host", modifier = Modifier.weight(1f), imeAction = ImeAction.Done)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${username.ifBlank { "user" }}@${hostname.ifBlank { "android" }} $ ", color = p.green, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                TermButton("apply", enabled = username != settings.username || hostname != settings.hostname, onClick = { scope.launch { app.prefs.setUsername(username); app.prefs.setHostname(hostname) } })
            }
        }

        // ---------------- pomodoro ----------------
        TerminalPanel(title = "pomodoro") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("focus", color = p.fgDim); Stepper(settings.pomodoroFocusMin, { scope.launch { app.prefs.setPomodoro(it, settings.pomodoroBreakMin, settings.pomodoroLongBreakMin) } }, min = 1, max = 180, suffix = "m", color = p.orange)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("break", color = p.fgDim); Stepper(settings.pomodoroBreakMin, { scope.launch { app.prefs.setPomodoro(settings.pomodoroFocusMin, it, settings.pomodoroLongBreakMin) } }, min = 1, max = 60, suffix = "m")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("long break", color = p.fgDim); Stepper(settings.pomodoroLongBreakMin, { scope.launch { app.prefs.setPomodoro(settings.pomodoroFocusMin, settings.pomodoroBreakMin, it) } }, min = 1, max = 90, suffix = "m")
            }
            Spacer(Modifier.height(4.dp))
            val dndAccess = dev.personalterminal.timer.FocusDnd.hasAccess(ctx)
            ToggleLine("do-not-disturb during focus" + if (!dndAccess) " (tap to grant access)" else "", settings.dndDuringFocus && dndAccess) { on ->
                if (!dndAccess) runCatching { ctx.startActivity(dev.personalterminal.timer.FocusDnd.settingsIntent()) } else scope.launch { app.prefs.setDndDuringFocus(on) }
            }
            ToggleLine("compact live update: icon + time left only (status chip / island)", settings.compactLiveUpdate) { scope.launch { app.prefs.setCompactLiveUpdate(it) } }
            Comment("per-habit focus/break lengths live in `habit edit` · quick settings tile + widget can start the timer")
            if (android.os.Build.MANUFACTURER.lowercase() in setOf("oppo", "oneplus", "realme")) {
                Comment("colorOS / oxygenOS: the island shows the countdown only after settings › notifications & quick settings › live alerts › app alert is turned on for this app")
            }
        }

        // ---------------- focus ambience (0.3.8) ----------------
        TerminalPanel(title = "focus ambience") {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("sound", color = p.fgDim)
                Spacer(Modifier.width(10.dp))
                dev.personalterminal.timer.AmbientAudio.PATTERNS.forEach { m ->
                    val sel = settings.ambientSound == m
                    Text(
                        (if (sel) "[✓ " else "[  ") + m + "]",
                        color = if (sel) p.green else p.fgDim,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .clickable { scope.launch { app.prefs.setAmbient(m); dev.personalterminal.timer.AmbientAudio.setPattern(m) } }
                            .padding(horizontal = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("volume", color = p.fgDim)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    TermButton("−", onClick = {
                        val v = (settings.ambientVolume - 10).coerceAtLeast(0)
                        scope.launch { app.prefs.setAmbientVolume(v) }; dev.personalterminal.timer.AmbientAudio.setVolume(v)
                    })
                    Text("  ${settings.ambientVolume}%  ", color = p.fg, style = MaterialTheme.typography.bodyMedium)
                    TermButton("+", onClick = {
                        val v = (settings.ambientVolume + 10).coerceAtMost(100)
                        scope.launch { app.prefs.setAmbientVolume(v) }; dev.personalterminal.timer.AmbientAudio.setVolume(v)
                    })
                }
            }
            Comment("procedurally generated — no audio assets · plays during focus, stops for breaks")
        }

        // ---------------- screensaver (0.3.8) ----------------
        TerminalPanel(title = "screensaver") {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("pattern", color = p.fgDim)
                Spacer(Modifier.width(10.dp))
                listOf("off", "matrix", "life", "stars").forEach { m ->
                    val sel = settings.saverPattern == m
                    Text(
                        (if (sel) "[✓ " else "[  ") + m + "]",
                        color = if (sel) p.green else p.fgDim,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { scope.launch { app.prefs.setSaver(m) } }.padding(horizontal = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("after", color = p.fgDim)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    listOf(2, 5, 10, 15).forEach { m ->
                        val sel = settings.saverAfterMin == m
                        Text(
                            " $m" + if (sel) "✓" else " ",
                            color = if (sel) p.green else p.fgDim,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clickable { scope.launch { app.prefs.setSaverAfter(m) } }.padding(horizontal = 6.dp),
                        )
                    }
                    Text(" min idle", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                }
            }
            Comment("tap or any key wakes it · matrix rain, game of life, starfield")
        }

        // ---------------- privacy (0.3.8) ----------------
        TerminalPanel(title = "privacy", titleColor = p.yellow) {
            ToggleLine("app lock: biometric / PIN on open and after backgrounding", settings.appLock) { on ->
                scope.launch { app.prefs.setAppLock(on) }
            }
            if (settings.appLock) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("lock after", color = p.fgDim)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        listOf(0, 1, 5, 15).forEach { m ->
                            val sel = settings.lockAfterMin == m
                            Text(
                                (if (m == 0) " [now]" else " ${m}m") + if (sel) "✓" else "",
                                color = if (sel) p.green else p.fgDim,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.clickable { scope.launch { app.prefs.setLockAfter(m) } }.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }
            ToggleLine("secure window: hide the app in screenshots & recents", settings.secureWindow) { on ->
                scope.launch { app.prefs.setSecureWindow(on) }
            }
            Comment("the lock needs a screen lock (PIN / pattern / biometric) on the device · secure window keeps valuations out of the recents preview")
        }

        // ---------------- health connect ----------------
        TerminalPanel(title = "health connect", titleColor = p.green) {
            val avail = dev.personalterminal.health.HealthSync.isAvailable(ctx)
            if (!avail) Comment("health connect is not available on this device (needs android 14+ or the health connect app)")
            ToggleLine("auto-complete linked habits", settings.healthConnect && avail) { on ->
                if (on) runCatching { healthPermissionLauncher.launch(dev.personalterminal.health.HealthMetric.allPermissions) }.onFailure { say("cannot open health connect: ${it.message}") }
                else scope.launch { app.prefs.setHealthConnect(false); dev.personalterminal.health.HealthSync.schedule(ctx, false) }
            }
            if (settings.healthConnect) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TermButton("sync now", color = p.green, onClick = { scope.launch { val r = dev.personalterminal.health.HealthSync.syncNow(ctx); say(if (r.isEmpty()) "nothing to sync" else r.joinToString("; ")) } })
                }
            }
            Comment("link a counter/timer habit to steps, exercise minutes, sleep hours or hydration in `habit edit`")
        }

        // ---------------- google drive ----------------
        TerminalPanel(title = "google drive sync", titleColor = p.yellow) {
            if (!app.googleAuth.isConfigured) {
                Text("not configured", color = p.red, fontWeight = FontWeight.Bold)
                Comment("add GOOGLE_WEB_CLIENT_ID to local.properties and rebuild — see README")
            }
            val email = settings.driveAccountEmail
            if (email.isBlank()) {
                TermButton("sign in with google", color = p.yellow, enabled = app.googleAuth.isConfigured && !busy, onClick = {
                    val activity = ctx as? Activity ?: return@TermButton
                    scope.launch {
                        busy = true
                        when (val r = app.googleAuth.signIn(activity)) {
                            is GoogleAuth.SignInResult.Success -> { app.prefs.setDriveAccount(r.email, ""); say("signed in as ${r.email}"); doBackup() }
                            is GoogleAuth.SignInResult.Error -> say("sign-in failed: ${r.message}")
                            GoogleAuth.SignInResult.NotConfigured -> say("missing GOOGLE_WEB_CLIENT_ID")
                        }
                        busy = false
                    }
                })
            } else {
                KeyValue("account", email, valueColor = p.fg)
                KeyValue("folder", DriveClient.FOLDER_NAME + "/")
                KeyValue("last backup", if (settings.lastBackupAt == 0L) "never" else fmt(settings.lastBackupAt))
                if (settings.lastBackupStatus.isNotBlank()) Text(settings.lastBackupStatus, color = if (settings.lastBackupStatus.startsWith("ok")) p.green else p.red, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                KeyValue("last verified", if (settings.lastVerifiedAt == 0L) "never" else fmt(settings.lastVerifiedAt))
                if (settings.lastVerifiedStatus.isNotBlank()) Text(settings.lastVerifiedStatus, color = if (settings.lastVerifiedStatus.startsWith("ok")) p.green else if (settings.lastVerifiedStatus.startsWith("warn")) p.yellow else p.red, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable {
                    scope.launch { val en = !settings.autoBackup; app.prefs.setAutoBackup(en); DriveSync.schedule(ctx, en, settings.backupIntervalHours) }
                }) {
                    Text(if (settings.autoBackup) "[✓]" else "[ ]", color = p.yellow, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.width(8.dp)); Text("automatic backup", color = p.fg)
                }
                if (settings.autoBackup) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("every ", color = p.fgDim, style = MaterialTheme.typography.bodySmall)
                        listOf(6, 12, 24, 72).forEach { h ->
                            val sel = settings.backupIntervalHours == h
                            Text(if (sel) "[${h}h]" else " ${h}h ", color = if (sel) p.yellow else p.fgDim, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.clickable { scope.launch { app.prefs.setBackupInterval(h); DriveSync.schedule(ctx, true, h) } }.padding(2.dp))
                        }
                    }
                    Comment("also backs up ~10 min after any change (wifi/data required)")
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TermButton("backup now", color = p.yellow, filled = true, enabled = !busy, onClick = { scope.launch { busy = true; doBackup(); busy = false } })
                    TermButton("list", color = p.yellow, enabled = !busy, onClick = { scope.launch { busy = true; doList(); busy = false } })
                    TermButton("verify", color = p.cyan, enabled = !busy, onClick = { scope.launch { busy = true; doVerify(); busy = false } })
                    TermButton("sign out", color = p.fgDim, enabled = !busy, onClick = { scope.launch { app.googleAuth.signOut(); app.prefs.setDriveAccount("", ""); app.prefs.setAutoBackup(false); DriveSync.schedule(ctx, false, 24); remote = null; say("signed out") } })
                }
                remote?.let { list ->
                    Spacer(Modifier.height(8.dp))
                    Text("── remote backups ──", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                    if (list.isEmpty()) Comment("none yet")
                    list.forEach { b ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.name, color = p.fg, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${fmt(b.modifiedAt)} · ${b.size / 1024} KB", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
                            }
                            if (confirmRestoreId == b.id) TermButton("confirm", color = p.red, filled = true, onClick = { scope.launch { busy = true; doRestore(b.id); confirmRestoreId = null; busy = false } })
                            else TermButton("restore", color = p.red, enabled = !busy, onClick = { confirmRestoreId = b.id })
                        }
                    }
                    Comment("restore replaces ALL local data")
                }
            }
        }

        // ---------------- local backup ----------------
        handedOver?.let { uri ->
            TerminalPanel(title = "archive received", titleColor = p.red, borderColor = p.red.copy(alpha = 0.5f)) {
                Text(uri.lastPathSegment ?: uri.toString(), color = p.fg, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Comment("restoring replaces ALL local data with this archive")
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TermButton("restore it", color = p.red, filled = true, enabled = !busy, onClick = { scope.launch { restoreFrom(uri, null); app.pendingImport.value = null } })
                    TermButton("ignore", color = p.fgDim, onClick = { app.pendingImport.value = null })
                }
            }
        }
        TerminalPanel(title = "local backup") {
            Comment("a .${BackupManager.EXT} archive = data.json + watch photos · .${BackupManager.EXT_ENCRYPTED} = the same, AES-256-GCM encrypted")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermButton("export file", enabled = !busy, onClick = { exportLauncher.launch(app.backups.fileName(encrypted = settings.backupPassphrase.isNotBlank())) })
                TermButton("import file", color = p.red, enabled = !busy, onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) })
            }
            pendingRestoreUri?.let {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TermTextField(value = restorePass, onValueChange = { restorePass = it }, placeholder = "archive passphrase", visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), imeAction = ImeAction.Done, modifier = Modifier.weight(1f), prompt = "🔒 ")
                    Spacer(Modifier.width(6.dp))
                    TermButton("unlock & restore", color = p.red, enabled = restorePass.isNotBlank() && !busy, onClick = { scope.launch { restoreFrom(it, restorePass) } })
                }
            }
            Spacer(Modifier.height(8.dp))
            var pass by remember(settings.backupPassphrase) { mutableStateOf(settings.backupPassphrase) }
            Text("encryption:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TermTextField(value = pass, onValueChange = { pass = it }, placeholder = "passphrase (empty = plain zip)", visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), imeAction = ImeAction.Done, modifier = Modifier.weight(1f), prompt = "🔒 ")
                Spacer(Modifier.width(6.dp))
                TermButton("apply", enabled = pass != settings.backupPassphrase, onClick = { scope.launch { app.prefs.setBackupPassphrase(pass.trim()); say(if (pass.isBlank()) "backups are plain zips again" else "new backups (drive + export) are encrypted") } })
            }
            Comment(if (settings.backupPassphrase.isBlank()) "drive and exported archives are readable by anyone with the file" else "PBKDF2 + AES-256-GCM · no passphrase, no restore – keep it somewhere safe", color = if (settings.backupPassphrase.isBlank()) p.fgDim else p.yellow)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermButton("import from loop / habitica", color = p.cyan, onClick = { nav.navigate(dev.personalterminal.ui.navigation.Routes.IMPORT) })
            }
        }

        TerminalPanel(title = "automation") {
            Comment("tasker / macrodroid / adb can drive the app through broadcasts:")
            Text(
                listOf(
                    "am broadcast -a dev.personalterminal.action.COMPLETE --es habit \"stretch\"",
                    "am broadcast -a dev.personalterminal.action.TIMER_START --ei minutes 25",
                    "am broadcast -a dev.personalterminal.action.WEAR --es watch \"speedy\"",
                    "…INCREMENT --ei amount 1 · …SET --ei value 20 · …SKIP --es reason \"sick\"",
                    "…TIMER_STOP · …BACKUP · any action + --es command \"done stretch\"",
                ).joinToString("\n"),
                color = p.fg, style = MaterialTheme.typography.labelSmall,
            )
            Comment("the same shell runs on the today screen (`help` lists every command)")
        }

        if (log.isNotEmpty()) TerminalPanel(title = "log") { log.forEach { Text(it, color = if ("fail" in it || "error" in it) p.red else p.fg, style = MaterialTheme.typography.bodySmall) } }
        if (busy) Text("working…", color = p.yellow, style = MaterialTheme.typography.bodySmall)

        TerminalPanel(title = "about") {
            KeyValue("app", "Personal Terminal")
            KeyValue("version", "${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}")
            KeyValue("fonts", "JetBrains Mono · Fira Code · Roboto Mono (OFL)")
            Comment("inspired by Init Habits · built with Jetpack Compose")
            Spacer(Modifier.height(6.dp))
            TermButton("replay boot screen", color = p.fgDim, onClick = { scope.launch { app.prefs.setOnboarded(false) } })
        }
        TermButton("back", color = p.fgDim, onClick = { nav.popBackStack() })
        Spacer(Modifier.height(24.dp))
    }
}

private fun fmt(ms: Long): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@Composable
internal fun ToggleLine(label: String, on: Boolean, color: androidx.compose.ui.graphics.Color = Term.palette.green, onToggle: (Boolean) -> Unit) {
    val p = Term.palette
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onToggle(!on) }.padding(vertical = 4.dp)) {
        Text(if (on) "[✓]" else "[ ]", color = if (on) color else p.fgDim, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(8.dp))
        Text(label, color = p.fg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ThemeCard(family: ThemeFamily, selected: Boolean, dark: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = Term.palette
    val pal = Palettes.get(family, dark)
    Row(
        modifier
            .background(pal.bg, RoundedCornerShape(6.dp))
            .border(if (selected) 2.dp else 1.dp, if (selected) p.green else p.border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val face = dev.personalterminal.ui.theme.Fonts.options.firstOrNull { it.id == family.font }
            Text(
                (if (selected) "(•) " else "( ) ") + family.label, color = pal.fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = face?.family ?: MaterialTheme.typography.labelMedium.fontFamily,
                    fontSize = MaterialTheme.typography.labelMedium.fontSize * dev.personalterminal.ui.theme.Fonts.sizeFactor(family.font)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.padding(top = 4.dp)) {
                listOf(pal.red, pal.green, pal.yellow, pal.blue, pal.purple, pal.cyan).forEach { Box(Modifier.size(10.dp).background(it, RoundedCornerShape(2.dp))) }
            }
            Text(face?.label?.lowercase() ?: "", color = pal.fgDim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

/** Launcher icon variants – tap to swap the home-screen icon (one activity-alias enabled at a time). */
@Composable
private fun IconPicker(app: PersonalTerminalApp, selectedId: String, say: (String) -> Unit) {
    val p = Term.palette
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // the package manager is the source of truth (a restored backup may carry a different pref)
    val actual = remember { dev.personalterminal.LauncherIcons.current(ctx) }
    var current by remember { mutableStateOf(actual) }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        dev.personalterminal.LauncherIcons.options.forEach { o ->
            val sel = current == o.id
            Column(
                Modifier
                    .clickable {
                        current = o.id
                        scope.launch {
                            app.prefs.setLauncherIcon(o.id)
                            runCatching { dev.personalterminal.LauncherIcons.apply(ctx, o.id) }
                                .onSuccess { say("icon → ${o.label} · the launcher may take a moment to redraw") }
                                .onFailure { say("could not switch icon: ${it.message}") }
                        }
                    }
                    .padding(2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color(o.background))
                        .border(if (sel) 2.dp else 1.dp, if (sel) p.green else p.border, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(o.foreground), contentDescription = o.label,
                        modifier = Modifier.size(84.dp), // adaptive foregrounds are drawn inside a 108dp canvas with a safe zone
                    )
                }
                Text((if (sel) "[" else " ") + o.label + (if (sel) "]" else " "), color = if (sel) p.green else p.fgDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
    Comment(dev.personalterminal.LauncherIcons.option(current).blurb)
}


/**
 * `notifications` – one line per thing the app can send, each with its own switch (and time where it
 * applies). The first switch is the master; quiet hours apply to all scheduled notifications.
 */
@Composable
private fun NotificationsPanel(app: PersonalTerminalApp, settings: dev.personalterminal.data.prefs.Settings, say: (String) -> Unit) {
    val p = Term.palette
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val n = settings.notifications
    val master = settings.remindersEnabled
    val access = dev.personalterminal.reminders.ReminderScheduler.hasNotificationAccess(ctx)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        say(if (granted) "notifications allowed" else "notifications still blocked – allow them in system settings")
    }
    var nextSlot by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings) { nextSlot = runCatching { dev.personalterminal.reminders.ReminderScheduler.describeNext(ctx) }.getOrNull() }
    fun update(block: (dev.personalterminal.data.prefs.NotificationPrefs) -> dev.personalterminal.data.prefs.NotificationPrefs) {
        scope.launch { app.prefs.setNotifications(block(n)); dev.personalterminal.reminders.ReminderScheduler.reschedule(ctx) }
    }

    TerminalPanel(title = "notifications", titleColor = p.yellow) {
        ToggleLine("send notifications (master switch)", master, color = p.yellow) { on ->
            scope.launch { app.prefs.setRemindersEnabled(on); dev.personalterminal.reminders.ReminderScheduler.reschedule(ctx) }
        }
        if (!access) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Text("notifications are blocked for this app", color = p.red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TermButton("allow", color = p.red, onClick = {
                    if (android.os.Build.VERSION.SDK_INT >= 33) permissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    else runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)) }
                })
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("# habits", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
        NotifLine("habit reminders", "\"time to do it\" at each habit's own time (habit edit)", n.habitReminders, master) { update { it.copy(habitReminders = !it.habitReminders) } }
        NotifLine("evening check-in", if (n.eveningSummary) "replaced by the evening summary while that is on" else "\"did you do X today?\" for habits with check-in on · done / skip buttons", n.habitCheckIn && !n.eveningSummary, master && !n.eveningSummary,
            minutes = n.checkInMinutes, onMinutes = { m -> update { it.copy(checkInMinutes = m) } }) { update { it.copy(habitCheckIn = !it.habitCheckIn) } }
        NotifLine("evening summary", "one line instead of N check-ins: `4/6 done · open: read, journal · streak at risk: journal`", n.eveningSummary, master,
            minutes = n.eveningSummaryMinutes, onMinutes = { m -> update { it.copy(eveningSummaryMinutes = m) } }) { update { it.copy(eveningSummary = !it.eveningSummary) } }
        NotifLine("comeback nudge", "after ${dev.personalterminal.domain.Comeback.QUIET_DAYS} quiet days: what's waiting, shields ready, longest streak still alive · backs off ×2 each time", n.comebackNudge, master) { update { it.copy(comebackNudge = !it.comebackNudge) } }
        NotifLine("stack nudge", "habit stacking: the follower's reminder fires the moment its anchor is ticked (`after` habits)", n.stackNudge, master) { update { it.copy(stackNudge = !it.stackNudge) } }
        NotifLine("streak at risk", "one notice when a streak ≥ ${n.streakRiskMinStreak} days is still open in the evening", n.streakRisk, master,
            minutes = n.streakRiskMinutes, onMinutes = { m -> update { it.copy(streakRiskMinutes = m) } }) { update { it.copy(streakRisk = !it.streakRisk) } }
        if (n.streakRisk && master) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 28.dp)) {
            Text("min streak ", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
            Stepper(n.streakRiskMinStreak, { v -> update { it.copy(streakRiskMinStreak = v) } }, min = 1, max = 60, suffix = "d", color = p.yellow)
        }
        NotifLine("weekly review", "sunday evening: review --week is ready", n.weeklyReview, master,
            minutes = n.weeklyReviewMinutes, onMinutes = { m -> update { it.copy(weeklyReviewMinutes = m) } }) { update { it.copy(weeklyReview = !it.weeklyReview) } }
        NotifLine("morning briefing", "one line a day: habits due, sleep, watch suggestion, streaks at risk", n.morningBriefing, master,
            minutes = n.morningBriefingMinutes, onMinutes = { m -> update { it.copy(morningBriefingMinutes = m) } }) { update { it.copy(morningBriefing = !it.morningBriefing) } }
        Spacer(Modifier.height(6.dp))
        Text("# watches", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
        NotifLine("log today's watch", "if nothing is on the wrist yet · suggests `watch next` with a one-tap \"wear it\"", n.wearLog, master,
            minutes = n.wearLogMinutes, onMinutes = { m -> update { it.copy(wearLogMinutes = m) } }) { update { it.copy(wearLog = !it.wearLog) } }
        NotifLine("service due", "when a watch's service interval is within 30 days (at most weekly per watch)", n.watchService, master) { update { it.copy(watchService = !it.watchService) } }
        Spacer(Modifier.height(6.dp))
        Text("# timer", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
        NotifLine("focus / break finished", "sound + heads-up when a pomodoro phase ends (the live countdown itself always stays)", n.timerAlerts, true) { update { it.copy(timerAlerts = !it.timerAlerts) } }
        Spacer(Modifier.height(8.dp))
        Text("quiet hours:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("from ", color = p.fgDim, style = MaterialTheme.typography.bodySmall)
            Stepper(settings.quietStartMin / 60, { scope.launch { app.prefs.setQuietHours(it * 60, settings.quietEndMin); dev.personalterminal.reminders.ReminderScheduler.reschedule(ctx) } }, min = 0, max = 23, suffix = ":00", color = p.yellow)
            Spacer(Modifier.width(10.dp))
            Text("to ", color = p.fgDim, style = MaterialTheme.typography.bodySmall)
            Stepper(settings.quietEndMin / 60, { scope.launch { app.prefs.setQuietHours(settings.quietStartMin, it * 60); dev.personalterminal.reminders.ReminderScheduler.reschedule(ctx) } }, min = 0, max = 23, suffix = ":00", color = p.yellow)
        }
        Comment(if (settings.hasQuietHours) "anything scheduled inside this window is dropped, not delayed" else "same start and end = no quiet hours")
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(nextSlot?.let { "next: $it" } ?: if (master) "next: nothing scheduled" else "next: —", color = p.fgDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            TermButton("test", color = p.fgDim, enabled = master && access, onClick = { scope.launch { say(dev.personalterminal.reminders.ReminderScheduler.sendTest(ctx)) } })
        }
        Comment("per-habit times live in `habit edit` · `remind stretch 07:30` and `remind stretch checkin on` work from the prompt")
    }
}

/** `[✓] label            18:00` with a tap-to-toggle checkbox and an optional time stepper (15-minute steps). */
@Composable
private fun NotifLine(label: String, hint: String, on: Boolean, enabled: Boolean, minutes: Int? = null, onMinutes: ((Int) -> Unit)? = null, onToggle: () -> Unit) {
    val p = Term.palette
    val active = on && enabled
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onToggle() }) {
            Text(if (on) "[✓]" else "[ ]", color = if (active) p.green else p.fgDim, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(label, color = if (enabled) p.fg else p.fgDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (minutes != null && onMinutes != null) {
                Text("[-]", color = if (active) p.yellow else p.fgDim, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable(enabled = active) { onMinutes((minutes - 15 + 1440) % 1440) }.padding(horizontal = 4.dp))
                Text("%02d:%02d".format(minutes / 60, minutes % 60), color = if (active) p.fg else p.fgDim, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text("[+]", color = if (active) p.yellow else p.fgDim, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable(enabled = active) { onMinutes((minutes + 15) % 1440) }.padding(horizontal = 4.dp))
            }
        }
        Text(hint, color = p.fgDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 28.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
