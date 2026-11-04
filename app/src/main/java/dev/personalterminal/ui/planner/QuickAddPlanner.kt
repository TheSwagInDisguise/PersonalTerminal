package dev.personalterminal.ui.planner

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.navigation.NavHostController
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.db.PlannerNote
import dev.personalterminal.data.db.PlannerPriority
import dev.personalterminal.data.db.PlannerTask
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.components.TermTextField
import dev.personalterminal.ui.navigation.Routes
import dev.personalterminal.ui.theme.Term
import kotlinx.coroutines.launch
import java.time.LocalDate

private fun parseDay(value: String): Long? = runCatching { LocalDate.parse(value.trim()).toEpochDay() }.getOrNull()

/**
 * Always-visible planner entry point. The planner screen itself lives behind the Profile tab, so
 * without this the one-off tasks and Markdown notes are easy to miss. Rendered inside AppScaffold,
 * which the screenshot suite does not capture — adding it does not change any golden images.
 */
@Composable
fun QuickAddPlannerFab(app: PersonalTerminalApp, nav: NavHostController, visible: Boolean = true) {
    if (!visible) return
    val p = Term.palette
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<String?>(null) } // null | menu | task | note

    FloatingActionButton(
        onClick = { mode = "menu" },
        containerColor = p.green,
        contentColor = p.bg,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text("+ task / note", style = MaterialTheme.typography.labelMedium)
    }

    if (mode == null) return
    Dialog(onDismissRequest = { mode = null }) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(p.bg)
                .border(1.dp, p.green, RoundedCornerShape(4.dp))
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                when (mode) {
                    "task" -> "quick add · one-off task"
                    "note" -> "quick add · markdown note"
                    else -> "planner · quick add"
                },
                color = p.green,
                style = MaterialTheme.typography.titleSmall,
            )
            when (mode) {
                "menu" -> Menu(app, nav, onMode = { mode = it }, onClose = { mode = null })
                "task" -> QuickTask(app, onDone = {
                    mode = null
                    nav.navigate(Routes.planner("tasks")) { launchSingleTop = true }
                }, onClose = { mode = null })
                "note" -> QuickNote(app, onDone = {
                    mode = null
                    nav.navigate(Routes.planner("notes")) { launchSingleTop = true }
                }, onClose = { mode = null })
            }
        }
    }
}

@Composable
private fun Menu(
    app: PersonalTerminalApp,
    nav: NavHostController,
    onMode: (String) -> Unit,
    onClose: () -> Unit,
) {
    val p = Term.palette
    Text("add planner items without hunting through the profile tab", color = p.fgDim, style = MaterialTheme.typography.bodySmall)
    TermButton("new one-off task", filled = true, onClick = { onMode("task") }, modifier = Modifier.fillMaxWidth())
    TermButton("new markdown note", color = p.purple, onClick = { onMode("note") }, modifier = Modifier.fillMaxWidth())
    TermButton("open full planner", color = p.cyan, onClick = {
        onClose()
        nav.navigate(Routes.PLANNER) { launchSingleTop = true }
    }, modifier = Modifier.fillMaxWidth())
    TermButton("cancel", color = p.fgDim, onClick = onClose, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun QuickTask(app: PersonalTerminalApp, onDone: () -> Unit, onClose: () -> Unit) {
    val p = Term.palette
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    var reminder by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(PlannerPriority.NORMAL) }
    TermTextField(value = title, onValueChange = { title = it }, label = "title", placeholder = "book the dentist")
    TermTextField(value = due, onValueChange = { due = it }, label = "due date (yyyy-mm-dd)", placeholder = "2026-11-20")
    Text("priority", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        PlannerPriority.entries.forEach { pr ->
            TermButton(pr.label, color = if (priority == pr) p.yellow else p.fgDim, enabled = priority != pr,
                onClick = { priority = pr }, modifier = Modifier.weight(1f))
        }
    }
    Spacer(Modifier.height(4.dp))
    TermButton("save task", filled = true, enabled = title.isNotBlank(), onClick = {
        scope.launch {
            app.planner.saveTask(
                PlannerTask(
                    title = title.trim(),
                    priority = priority.name,
                    dueDay = parseDay(due),
                ),
            )
            onDone()
        }
    }, modifier = Modifier.fillMaxWidth())
    TermButton("cancel", color = p.fgDim, onClick = onClose, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun QuickNote(app: PersonalTerminalApp, onDone: () -> Unit, onClose: () -> Unit) {
    val p = Term.palette
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("# note\n\n- [ ] first step\n\n**bold** and `code` and [link](https://example.com)") }
    TermTextField(value = title, onValueChange = { title = it }, label = "title", placeholder = "trip packing")
    TermTextField(value = body, onValueChange = { body = it }, label = "body (markdown)", singleLine = false, placeholder = "# heading")
    MarkdownToolbar(onInsert = { body += it })
    Text("preview", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
    MarkdownPreview(body, onOpen = { })
    Spacer(Modifier.height(4.dp))
    TermButton("save note", filled = true, enabled = title.isNotBlank(), onClick = {
        scope.launch {
            app.planner.saveNote(
                PlannerNote(title = title.trim(), body = body, lastSyncedBody = body),
            )
            onDone()
        }
    }, modifier = Modifier.fillMaxWidth())
    TermButton("cancel", color = p.fgDim, onClick = onClose, modifier = Modifier.fillMaxWidth())
}
