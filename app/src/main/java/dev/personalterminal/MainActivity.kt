package dev.personalterminal

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.collectAsStateWithLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.fragment.app.FragmentActivity
import dev.personalterminal.data.prefs.Settings
import dev.personalterminal.ui.lock.LockGate
import dev.personalterminal.ui.navigation.AppScaffold
import dev.personalterminal.ui.saver.ScreensaverHost
import dev.personalterminal.ui.theme.TerminalTheme

class MainActivity : FragmentActivity() {

    /** Route requested via intent extra (widget / notification deep-links). */
    private var pendingRoute by mutableStateOf<String?>(null)

    /** Last time the app went to background (0 = never) — the app-lock timer reads it on return. */
    private var lastLeftMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = PersonalTerminalApp.get(this)
        pendingRoute = routeFor(intent, app)
        setContent {
            val settings by app.prefs.settings.collectAsStateWithLifecycle(initialValue = Settings())
            // Latest snapshot, readable from lifecycle callbacks without blocking on DataStore.
            var latest by remember { mutableStateOf(settings) }
            latest = settings
            var locked by remember { mutableStateOf(false) }

            // Enabled → gate on open (and whenever the toggle flips mid-session).
            androidx.compose.runtime.LaunchedEffect(settings.appLock) {
                locked = settings.appLock
            }
            // Returned from background after the grace period → gate again.
            DisposableEffect(lifecycle) {
                val obs = LifecycleEventObserver { _, e ->
                    when (e) {
                        Lifecycle.Event.ON_STOP -> lastLeftMs = SystemClock.elapsedRealtime()
                        Lifecycle.Event.ON_START -> {
                            val s = latest
                            if (s.appLock && lastLeftMs != 0L &&
                                SystemClock.elapsedRealtime() - lastLeftMs >= s.lockAfterMin * 60_000L
                            ) locked = true
                        }
                        else -> Unit
                    }
                }
                lifecycle.addObserver(obs)
                onDispose { lifecycle.removeObserver(obs) }
            }

            // Secure window: out of screenshots and the recents preview while enabled.
            SideEffect {
                if (settings.secureWindow) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }

            TerminalTheme(settings) {
                ScreensaverHost(settings.saverPattern, settings.saverAfterMin) {
                    AppScaffold(
                        app = app,
                        settings = settings,
                        startRoute = pendingRoute,
                        onRouteConsumed = { pendingRoute = null },
                    )
                }
                if (locked) LockGate(onUnlock = { locked = false })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = routeFor(intent, PersonalTerminalApp.get(this))
    }

    /** Explicit route extra, or – for a `.ptbak` file opened from another app – the settings screen with the file staged for import. */
    private fun routeFor(intent: Intent?, app: PersonalTerminalApp): String? {
        intent ?: return null
        intent.getStringExtra(EXTRA_ROUTE)?.let { return it }
        if (intent.action == Intent.ACTION_VIEW && intent.data != null) {
            app.pendingImport.value = intent.data
            return dev.personalterminal.ui.navigation.Routes.SETTINGS
        }
        return null
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
