package dev.personalterminal.tiles

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.domain.AppClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * A12 — quick-settings "done": one tap ticks the top due, undone habit; the tile shows its
 * name so the 90 % case never needs a trip into the app.
 */
class DoneTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job?.cancel()
        job = scope.launch { render() }
    }

    override fun onStopListening() {
        job?.cancel(); job = null
        super.onStopListening()
    }

    override fun onClick() {
        scope.launch(Dispatchers.IO) {
            val app = application as? PersonalTerminalApp ?: return@launch
            val today = AppClock.today()
            runCatching {
                val next = app.habits.daySummary(today).active.firstOrNull { !it.completed } ?: return@runCatching
                app.habits.toggle(next.habit.id, today)
            }
            launch(Dispatchers.Main) { render() }
        }
    }

    private suspend fun render() {
        val app = application as? PersonalTerminalApp ?: return
        val tile = qsTile ?: return
        val today = AppClock.today()
        val s = runCatching { app.habits.daySummary(today) }.getOrNull()
        val next = s?.active?.firstOrNull { !it.completed }
        val done = s?.done ?: 0
        val total = s?.active?.size ?: 0
        runOnUiThread {
            tile.state = when {
                s == null -> Tile.STATE_UNAVAILABLE
                next == null -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            tile.label = next?.habit?.name ?: "all done"
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                tile.subtitle = when {
                    s == null -> ""
                    next == null -> "perfect day ✓"
                    else -> "$done/$total · tap to tick"
                }
            }
            tile.updateTile()
        }
    }
}
