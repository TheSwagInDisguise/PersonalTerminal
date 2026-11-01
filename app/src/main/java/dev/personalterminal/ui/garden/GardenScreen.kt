package dev.personalterminal.ui.garden

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.domain.AppClock
import dev.personalterminal.domain.Garden
import dev.personalterminal.ui.components.Comment
import dev.personalterminal.ui.components.PromptLine
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.components.TerminalPanel
import dev.personalterminal.ui.theme.Term
import java.time.LocalDate

/**
 * The garden screen: the plant, the 52-week field, and the numbers behind them. Everything is
 * derived from logged focus sessions (`focus_sessions`), so the plant is the timer's afterlife.
 */
@Composable
fun GardenScreen(app: PersonalTerminalApp, nav: NavHostController) {
    val p = Term.palette
    val today = AppClock.today()
    val sessions by remember { app.habits.observeFocusSessions(4000) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val days: List<Garden.Day> = sessions
        .groupBy { it.day }
        .map { (epoch, rows) ->
            Garden.Day(LocalDate.ofEpochDay(epoch), rows.sumOf { it.minutes }, rows.size)
        }
    val st = Garden.stats(days, today)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PromptLine("garden")
        TerminalPanel(title = "plant · ${Garden.STAGE_NAMES[st.stage]}" + if (st.wilted) " (wilted)" else "", titleColor = if (st.wilted) p.yellow else p.green) {
            Garden.art(st.stage, st.wilted).forEach { line ->
                Text(line, color = if (st.wilted) p.yellow else p.green, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            when {
                st.wilted -> Comment("no focus session since ${st.lastDay ?: "—"} · one session waters it back")
                st.nextAt != null -> Comment("next stage: ${st.nextAt - st.totalMinutes} more focus minutes (at ${st.nextAt})")
                else -> Comment("fully grown · ${st.totalMinutes} focus minutes and counting")
            }
        }
        TerminalPanel(title = "field · focus minutes") {
            Garden.field(days, today).forEach { row ->
                Text(
                    row,
                    color = p.fgDim,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Spacer(Modifier.height(6.dp))
            Comment(Garden.legend())
        }
        TerminalPanel(title = "stats") {
            Text(
                "%d sessions · %dh %02dm total · %d-day watering streak · %dm this week".format(
                    st.totalSessions, st.totalMinutes / 60, st.totalMinutes % 60, st.dayStreak, st.weekMinutes
                ),
                color = p.fg, style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            Comment("every completed focus phase feeds the garden — `timer` to water it")
        }
        TermButton("← back", color = p.fgDim, onClick = { nav.popBackStack() })
        Spacer(Modifier.height(8.dp))
    }
}
