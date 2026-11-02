package dev.personalterminal.ui.habits

import dev.personalterminal.domain.AppClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.db.HabitType
import dev.personalterminal.domain.Schedule
import dev.personalterminal.ui.components.Comment
import dev.personalterminal.ui.components.PromptLine
import dev.personalterminal.ui.components.Tag
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.navigation.Routes
import dev.personalterminal.ui.theme.Term
import kotlinx.coroutines.launch
import java.time.LocalDate

/** `ls habits` – every habit grouped by routine with reorder / archive controls. */
@Composable
fun HabitsScreen(
    app: PersonalTerminalApp,
    nav: NavHostController,
    onSelectHabit: ((Long) -> Unit)? = null,
    selectedHabitId: Long? = null,
) {
    val p = Term.palette
    val scope = rememberCoroutineScope()
    val summary by remember { app.habits.observeDay(AppClock.today()) }.collectAsStateWithLifecycle(initialValue = null)
    val allHabits by remember { app.habits.observeHabits() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var showArchived by remember { mutableStateOf(false) }
    val archived = allHabits.filter { it.archived }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { PromptLine("ls habits", trailing = "${allHabits.count { !it.archived }} active") }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermButton("+ add", onClick = { nav.navigate(Routes.habitEdit()) }, modifier = Modifier.weight(1f))
                TermButton("routines", onClick = { nav.navigate(Routes.ROUTINES) }, color = p.purple, modifier = Modifier.weight(1f))
                TermButton("insurance", onClick = { nav.navigate(Routes.SKIP_RULES) }, color = p.cyan, modifier = Modifier.weight(1f))
            }
        }
        val s = summary ?: return@LazyColumn
        s.groups.forEach { group ->
            item(key = "r-${group.routine?.id ?: -1}") {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${group.routine?.icon ?: ">"} ${group.name}/", color = p.purple, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("+ add", color = p.fgDim, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clickable { nav.navigate(Routes.habitEdit(routineId = group.routine?.id)) }.padding(4.dp))
                }
            }
            items(group.habits, key = { "h-${it.habit.id}" }) { hs ->
                val h = hs.habit
                val color = p.named(h.color)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (h.id == selectedHabitId) p.bgHighlight else p.bgAlt, RoundedCornerShape(6.dp))
                        .then(if (h.id == selectedHabitId) Modifier.border(1.5.dp, p.green, RoundedCornerShape(6.dp)) else Modifier)
                        .clickable { onSelectHabit?.invoke(h.id) ?: nav.navigate(Routes.habitDetail(h.id)) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        when (h.type) { HabitType.CHECKBOX -> "[ ]"; HabitType.COUNTER -> "[#]"; HabitType.TIMER -> "[▶]"; HabitType.CHECKLIST -> "[≡]" },
                        color = color, style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.name, color = p.fg, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Tag(Schedule.describe(h))
                            Schedule.pauseLabel(h, AppClock.today())?.let { Tag(it, p.yellow) }
                            if (h.type != HabitType.CHECKBOX) Tag("${h.target} ${h.unit}".trim(), color)
                            if (hs.streak.current > 0) Tag("⚡${hs.streak.current}", p.orange)
                            if (hs.streak.best > 0) Tag("best ${hs.streak.best}", p.fgDim)
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("▲", color = p.fgDim, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clickable { scope.launch { app.habits.moveHabit(h, -1) } }.padding(horizontal = 6.dp, vertical = 2.dp))
                        Text("▼", color = p.fgDim, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clickable { scope.launch { app.habits.moveHabit(h, +1) } }.padding(horizontal = 6.dp, vertical = 2.dp))
                    }
                }
            }
        }
        if (archived.isNotEmpty()) {
            item(key = "archived-header") {
                Spacer(Modifier.height(8.dp))
                Text(
                    (if (showArchived) "▾" else "▸") + " archived/ (${archived.size})",
                    color = p.fgDim, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable { showArchived = !showArchived }.padding(4.dp),
                )
            }
            if (showArchived) items(archived, key = { "a-${it.id}" }) { h ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(h.name, color = p.fgDim, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TermButton("restore", onClick = { scope.launch { app.habits.setArchived(h, false) } }, color = p.cyan)
                    Spacer(Modifier.width(6.dp))
                    TermButton("rm", onClick = { scope.launch { app.habits.deleteHabit(h) } }, color = p.red)
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)); Comment("tap a habit for details · ▲▼ reorder"); Spacer(Modifier.height(24.dp)) }
    }
}
