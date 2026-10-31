package dev.personalterminal.ui.boot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.ui.components.Cursor
import dev.personalterminal.ui.components.RadioRow
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.components.TermTextField
import dev.personalterminal.ui.theme.Term
import dev.personalterminal.ui.theme.ThemeFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val bootLines = listOf(
    "[  OK  ] mounting /habits",
    "[  OK  ] loading streak engine",
    "[  OK  ] starting pomodoro daemon",
    "[  OK  ] initialising watch registry",
    "[  OK  ] checking shields … 0 available",
    "[  OK  ] personal-terminal.service started",
)

/** First-run screen: fake boot log + username/theme prompt. */
@Composable
fun BootScreen(app: PersonalTerminalApp, onDone: () -> Unit) {
    val p = Term.palette
    val scope = rememberCoroutineScope()
    var shown by remember { mutableIntStateOf(0) }
    var username by remember { mutableStateOf("") }
    var theme by remember { mutableStateOf(ThemeFamily.SIGNAL) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // ---- restore-from-Drive on first launch ----
    var restoreMode by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var restoreLog by remember { mutableStateOf<String?>(null) }
    var remote by remember { mutableStateOf<List<dev.personalterminal.sync.DriveClient.RemoteBackup>?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var pendingRestoreId by remember { mutableStateOf<String?>(null) }
    var pendingAfterConsent by remember { mutableStateOf<(suspend (String) -> Unit)?>(null) }
    val consentLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val token = if (result.resultCode == android.app.Activity.RESULT_OK) app.googleAuth.tokenFromConsentResult(result.data) else null
        val cont = pendingAfterConsent; pendingAfterConsent = null
        if (token != null && cont != null) scope.launch { busy = true; cont(token); busy = false } else restoreLog = "drive consent not granted"
    }
    fun handleConsent(intent: android.content.Intent, then: suspend (String) -> Unit) {
        @Suppress("DEPRECATION")
        val pi = intent.getParcelableExtra<android.app.PendingIntent>(dev.personalterminal.sync.GoogleAuth.EXTRA_PENDING_INTENT) ?: run { restoreLog = "no consent intent"; return }
        pendingAfterConsent = then
        consentLauncher.launch(androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender).build())
    }
    suspend fun listRemote(token: String? = null) {
        app.driveSync.listRemote(token).onSuccess { remote = it; restoreLog = if (it.isEmpty()) "no backups in drive for this account" else "found ${it.size} backups" }
            .onFailure { e -> if (e is dev.personalterminal.sync.DriveSync.ConsentRequired) handleConsent(e.intent) { t -> listRemote(t) } else restoreLog = "list failed: ${e.message}" }
    }
    suspend fun restore(id: String, token: String? = null) {
        when (val r = app.driveSync.restoreRemote(id, token, passphrase.takeIf { it.isNotBlank() })) {
            is dev.personalterminal.sync.DriveSync.Outcome.Success -> {
                restoreLog = r.message
                app.prefs.setRestoreOffered(true)
                app.prefs.setOnboarded(true)
                onDone()
            }
            is dev.personalterminal.sync.DriveSync.Outcome.Failure -> { restoreLog = r.message; if ("passphrase" in r.message) pendingRestoreId = id }
            is dev.personalterminal.sync.DriveSync.Outcome.NeedsConsent -> handleConsent(r.intent) { t -> restore(id, t) }
        }
    }

    LaunchedEffect(Unit) {
        while (shown < bootLines.size) { delay(220); shown++ }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(p.bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Personal Terminal v${dev.personalterminal.BuildConfig.VERSION_NAME}", color = p.green, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("booting…", color = p.fgDim, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        bootLines.take(shown).forEach { line ->
            Row {
                Text(line.substring(0, 8), color = p.green, style = MaterialTheme.typography.bodyMedium)
                Text(line.substring(8), color = p.fg, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (shown >= bootLines.size && restoreMode) {
            Spacer(Modifier.height(24.dp))
            Text("restore from google drive", color = p.yellow, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            if (!app.googleAuth.isConfigured) Text("# drive sync is not configured in this build", color = p.red, style = MaterialTheme.typography.bodySmall)
            val list = remote
            if (list == null) {
                TermButton(if (busy) "…" else "sign in & list backups", filled = true, enabled = !busy && app.googleAuth.isConfigured, modifier = Modifier.fillMaxWidth(), onClick = {
                    val activity = ctx as? android.app.Activity ?: return@TermButton
                    scope.launch {
                        busy = true
                        when (val r = app.googleAuth.signIn(activity)) {
                            is dev.personalterminal.sync.GoogleAuth.SignInResult.Success -> { app.prefs.setDriveAccount(r.email, ""); listRemote() }
                            is dev.personalterminal.sync.GoogleAuth.SignInResult.Error -> restoreLog = "sign-in failed: ${r.message}"
                            dev.personalterminal.sync.GoogleAuth.SignInResult.NotConfigured -> restoreLog = "missing GOOGLE_WEB_CLIENT_ID"
                        }
                        busy = false
                    }
                })
            } else {
                list.take(10).forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.name, color = p.fg, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            Text("${java.time.Instant.ofEpochMilli(b.modifiedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()} · ${b.size / 1024} KB", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
                        }
                        TermButton("restore", color = p.green, enabled = !busy, onClick = { scope.launch { busy = true; restore(b.id); busy = false } })
                    }
                }
                if (pendingRestoreId != null) {
                    Spacer(Modifier.height(6.dp))
                    TermTextField(value = passphrase, onValueChange = { passphrase = it }, label = "archive passphrase", visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), imeAction = ImeAction.Done)
                    Spacer(Modifier.height(4.dp))
                    TermButton("unlock & restore", color = p.green, enabled = passphrase.isNotBlank() && !busy, onClick = { scope.launch { busy = true; restore(pendingRestoreId!!); busy = false } })
                }
            }
            restoreLog?.let { Spacer(Modifier.height(6.dp)); Text("# $it", color = if ("fail" in it || "not" in it) p.red else p.green, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(12.dp))
            TermButton("← start fresh instead", color = p.fgDim, onClick = { restoreMode = false })
        } else if (shown >= bootLines.size) {
            Spacer(Modifier.height(24.dp))
            Text("login: new user detected", color = p.yellow, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            Text("→ already have a backup? restore from google drive", color = p.cyan, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.clickable { restoreMode = true }.padding(vertical = 4.dp))
            Spacer(Modifier.height(8.dp))
            TermTextField(value = username, onValueChange = { username = it.filter { c -> c.isLetterOrDigit() || c == '_' || c == '-' }.take(16) },
                label = "username", placeholder = "user", imeAction = ImeAction.Done)
            Spacer(Modifier.height(16.dp))
            Text("theme:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                ThemeFamily.entries.chunked(2).forEach { row ->
                    RadioRow(options = row, selected = theme, onSelect = { theme = it }, label = { it.label })
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${username.ifBlank { "user" }}@android $ ", color = p.green, style = MaterialTheme.typography.bodyLarge)
                Text("init", color = p.fg, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.padding(2.dp))
                Cursor()
            }
            Spacer(Modifier.height(16.dp))
            TermButton("run init", filled = true, modifier = Modifier.fillMaxWidth(), onClick = {
                scope.launch {
                    app.prefs.setUsername(username.ifBlank { "user" })
                    app.prefs.setTheme(theme.id)
                    app.habits.seedDefaults()
                    app.prefs.setRestoreOffered(true)
                    app.prefs.setOnboarded(true)
                    onDone()
                }
            })
            Spacer(Modifier.height(8.dp))
            Text("# a starter set of habits will be created – edit or delete them any time", color = p.fgDim, style = MaterialTheme.typography.bodySmall)
        }
    }
}
