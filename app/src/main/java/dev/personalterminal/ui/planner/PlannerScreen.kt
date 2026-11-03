package dev.personalterminal.ui.planner

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.db.PlannerPriority
import dev.personalterminal.data.db.PlannerProject
import dev.personalterminal.data.db.PlannerTask
import dev.personalterminal.data.db.PlannerNote
import dev.personalterminal.data.db.hasPendingConflict
import dev.personalterminal.domain.planner.NoteConflictChoice
import dev.personalterminal.domain.planner.PlannerRef
import dev.personalterminal.domain.planner.checklistItems
import dev.personalterminal.domain.planner.dueDate
import dev.personalterminal.domain.planner.encodeRefs
import dev.personalterminal.domain.planner.parseRefs
import dev.personalterminal.domain.planner.priorityValue
import dev.personalterminal.sync.DriveSync
import dev.personalterminal.ui.components.Comment
import dev.personalterminal.ui.components.KeyValue
import dev.personalterminal.ui.components.PromptLine
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.components.TermTextField
import dev.personalterminal.ui.components.TerminalPanel
import dev.personalterminal.ui.theme.Term
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private fun parseDay(value: String): Long? = runCatching { LocalDate.parse(value.trim()).toEpochDay() }.getOrNull()
private fun parseReminder(value: String): Long? = runCatching {
    LocalDateTime.parse(value.trim().replace(' ', 'T')).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()

/**
 * Planner = one-off work, kept deliberately separate from habits (no streaks, schedules or XP).
 * Projects, priorities, due dates, exact reminders, checklists, Markdown notes with live preview,
 * contextual links/attachments and two-way Drive Markdown with an explicit conflict pause.
 */
@Composable
fun PlannerScreen(app: PersonalTerminalApp, nav: NavHostController) {
    val p = Term.palette
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by remember { mutableStateOf("tasks") }
    var message by remember { mutableStateOf<String?>(null) }

    val projects by remember { app.planner.observeProjects() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val tasks by remember { app.planner.observeAllTasks() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val notes by remember { app.planner.observeNotes() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val conflicts by remember { app.planner.observeConflicts() }.collectAsStateWithLifecycle(initialValue = emptyList())

    fun open(ref: PlannerRef) {
        val route = ref.internalRoute
        if (route != null) {
            nav.navigate(route) { launchSingleTop = true }
        } else {
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(ref.uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { message = "cannot open ${ref.uri}: ${it.message}" }
        }
    }

    fun say(text: String) { message = text }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PromptLine("planner", trailing = "${tasks.count { !it.completed }} open · ${projects.size} projects · ${notes.size} notes")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("tasks", "projects", "notes").forEach { t ->
                TermButton(if (tab == t) "$t ●" else t, color = if (tab == t) p.green else p.fgDim,
                    onClick = { tab = t }, modifier = Modifier.weight(1f))
            }
        }
        message?.let {
            TerminalPanel(title = "status") { Text(it, color = if (it.startsWith("conflict") || it.startsWith("failed") || it.startsWith("cannot")) p.red else p.green, style = MaterialTheme.typography.bodySmall) }
        }
        if (conflicts.isNotEmpty()) {
            TerminalPanel(title = "note sync paused · ${conflicts.size} conflict(s)", titleColor = p.red) {
                Comment("both versions are retained. choose one below; sync resumes after your choice.")
            }
        }

        when (tab) {
            "projects" -> ProjectsTab(app, projects, scope, ::say)
            "tasks" -> TasksTab(app, projects, tasks, scope, ::say, ::open)
            else -> NotesTab(app, notes, conflicts, scope, ::say, ::open)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ProjectsTab(
    app: PersonalTerminalApp,
    projects: List<PlannerProject>,
    scope: kotlinx.coroutines.CoroutineScope,
    say: (String) -> Unit,
) {
    val p = Term.palette
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    TerminalPanel(title = "new project") {
        TermTextField(value = name, onValueChange = { name = it }, label = "name", placeholder = "renovate the study")
        Spacer(Modifier.height(6.dp))
        TermTextField(value = notes, onValueChange = { notes = it }, label = "notes (markdown)", singleLine = false, placeholder = "# goals\n- [ ] paint")
        Spacer(Modifier.height(6.dp))
        TermButton("create", filled = true, enabled = name.isNotBlank(), onClick = {
            scope.launch {
                app.planner.saveProject(PlannerProject(name = name.trim(), notes = notes))
                say("project '${name.trim()}' created")
                name = ""; notes = ""
            }
        })
    }
    projects.forEach { project ->
        TerminalPanel(title = project.name, titleColor = p.named(project.color)) {
            MarkdownPreview(project.notes, onOpen = { }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermButton("archive", color = p.fgDim, onClick = { scope.launch { app.planner.saveProject(project.copy(archived = true)); say("archived ${project.name}") } })
                TermButton("delete", color = p.red, onClick = { scope.launch { app.planner.deleteProject(project); say("deleted ${project.name}") } })
            }
        }
    }
    if (projects.isEmpty()) Comment("# no projects yet")
}

@Composable
private fun TasksTab(
    app: PersonalTerminalApp,
    projects: List<PlannerProject>,
    tasks: List<PlannerTask>,
    scope: kotlinx.coroutines.CoroutineScope,
    say: (String) -> Unit,
    open: (PlannerRef) -> Unit,
) {
    val p = Term.palette
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(PlannerPriority.NORMAL) }
    var due by remember { mutableStateOf("") }
    var reminder by remember { mutableStateOf("") }
    var checklist by remember { mutableStateOf("") }
    var projectId by remember { mutableStateOf<Long?>(null) }
    var links by remember { mutableStateOf("") }

    TerminalPanel(title = "new one-off task") {
        TermTextField(value = title, onValueChange = { title = it }, label = "title", placeholder = "book the dentist")
        Spacer(Modifier.height(6.dp))
        Text("priority", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlannerPriority.entries.forEach { pr ->
                TermButton(pr.label, color = if (priority == pr) p.yellow else p.fgDim, enabled = priority != pr,
                    onClick = { priority = pr })
            }
        }
        Spacer(Modifier.height(6.dp))
        TermTextField(value = due, onValueChange = { due = it }, label = "due date (yyyy-mm-dd)", placeholder = "2026-11-20")
        Spacer(Modifier.height(6.dp))
        TermTextField(value = reminder, onValueChange = { reminder = it }, label = "reminder (yyyy-mm-dd HH:mm)", placeholder = "2026-11-20 09:00")
        Spacer(Modifier.height(6.dp))
        TermTextField(value = checklist, onValueChange = { checklist = it }, label = "checklist (one per line)", singleLine = false, placeholder = "call\nconfirm")
        Spacer(Modifier.height(6.dp))
        TermTextField(value = links, onValueChange = { links = it }, label = "context links (label<TAB>uri)", singleLine = false,
            placeholder = "habit\tpt://habit/1")
        if (projects.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text("project", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TermButton("none", color = if (projectId == null) p.green else p.fgDim, onClick = { projectId = null })
                projects.take(4).forEach { pr ->
                    TermButton(pr.name.take(10), color = if (projectId == pr.id) p.green else p.fgDim, onClick = { projectId = pr.id })
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        TermButton("add task", filled = true, enabled = title.isNotBlank(), onClick = {
            scope.launch {
                val items = checklist.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val refs = links.lines().map { it.trim() }.filter { it.isNotEmpty() }
                val task = PlannerTask(
                    title = title.trim(), notes = notes, projectId = projectId,
                    priority = priority.name, dueDay = parseDay(due), reminderAt = parseReminder(reminder),
                    checklist = items.joinToString("\n") { "0|$it" },
                    links = encodeRefs(refs.map { PlannerRef(it.substringBefore('\t').trim().ifBlank { it }, it.substringAfter('\t', it).trim()) }),
                )
                app.planner.saveTask(task)
                say("task '${task.title}' added")
                title = ""; notes = ""; due = ""; reminder = ""; checklist = ""; links = ""
            }
        })
    }

    tasks.forEach { task ->
        val projectName = projects.firstOrNull { it.id == task.projectId }?.name
        TerminalPanel(title = if (task.completed) "✓ ${task.title}" else task.title,
            titleColor = if (task.completed) p.fgDim else when (task.priorityValue) {
                PlannerPriority.URGENT -> p.red
                PlannerPriority.HIGH -> p.orange
                PlannerPriority.LOW -> p.fgDim
                else -> p.green
            }) {
            KeyValue("priority", task.priorityValue.label)
            task.dueDate?.let { KeyValue("due", it.toString()) }
            task.reminderAt?.let { KeyValue("reminder", java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime().toString()) }
            projectName?.let { KeyValue("project", it) }
            if (task.checklistItems.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("checklist", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
                task.checklistItems.forEachIndexed { i, item ->
                    TermButton("${if (item.done) "x" else " "} ${item.label}", color = if (item.done) p.fgDim else p.cyan,
                        onClick = { scope.launch { app.planner.toggleChecklistItem(task, i, !item.done) } })
                }
            }
            if (task.notes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                MarkdownPreview(task.notes, onOpen = open, links = parseRefs(task.links), attachments = parseRefs(task.attachments))
            } else if (parseRefs(task.links).isNotEmpty() || parseRefs(task.attachments).isNotEmpty()) {
                MarkdownPreview("", onOpen = open, links = parseRefs(task.links), attachments = parseRefs(task.attachments))
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermButton(if (task.completed) "reopen" else "done", color = if (task.completed) p.fgDim else p.green,
                    onClick = { scope.launch { app.planner.setTaskCompleted(task, !task.completed); say(if (task.completed) "reopened" else "done") } })
                TermButton("delete", color = p.red, onClick = { scope.launch { app.planner.deleteTask(task); say("deleted task") } })
            }
        }
    }
    if (tasks.isEmpty()) Comment("# no one-off tasks yet — habits are on the habits tab")
}

@Composable
private fun NotesTab(
    app: PersonalTerminalApp,
    notes: List<PlannerNote>,
    conflicts: List<PlannerNote>,
    scope: kotlinx.coroutines.CoroutineScope,
    say: (String) -> Unit,
    open: (PlannerRef) -> Unit,
) {
    val p = Term.palette
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("# note\n\n- [ ] first step\n\n**bold** and `code` and [link](https://example.com)") }
    var links by remember { mutableStateOf("") }
    var attachments by remember { mutableStateOf("") }

    TerminalPanel(title = "new markdown note") {
        TermTextField(value = title, onValueChange = { title = it }, label = "title", placeholder = "trip packing")
        Spacer(Modifier.height(6.dp))
        TermTextField(value = body, onValueChange = { body = it }, label = "body (markdown)", singleLine = false, placeholder = "# heading")
        Spacer(Modifier.height(6.dp))
        TermTextField(value = links, onValueChange = { links = it }, label = "context links (label<TAB>uri)", singleLine = false,
            placeholder = "watch\tpt://watch/1")
        Spacer(Modifier.height(6.dp))
        TermTextField(value = attachments, onValueChange = { attachments = it }, label = "attachments (label<TAB>uri)", singleLine = false,
            placeholder = "receipt\tcontent://…")
        Spacer(Modifier.height(6.dp))
        Text("preview", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
        MarkdownPreview(body, onOpen = open, links = parseRefs(links), attachments = parseRefs(attachments))
        Spacer(Modifier.height(6.dp))
        TermButton("save note", filled = true, enabled = title.isNotBlank(), onClick = {
            scope.launch {
                app.planner.saveNote(
                    PlannerNote(title = title.trim(), body = body, lastSyncedBody = body,
                        links = encodeRefs(parseRefs(links)), attachments = encodeRefs(parseRefs(attachments))),
                )
                say("note '${title.trim()}' saved")
                title = ""; links = ""; attachments = ""
            }
        })
    }

    notes.forEach { note ->
        val editing = remember(note.id) { mutableStateOf(note.body) }
        TerminalPanel(title = note.title, titleColor = if (note.hasPendingConflict) p.red else p.purple) {
            if (note.hasPendingConflict) {
                Text("sync paused — BOTH versions kept", color = p.red, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text("local:", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
                MarkdownPreview(note.body, onOpen = open, links = parseRefs(note.links), attachments = parseRefs(note.attachments))
                Spacer(Modifier.height(4.dp))
                Text("drive:", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
                MarkdownPreview(note.pendingRemoteBody ?: "", onOpen = open)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NoteConflictChoice.entries.forEach { choice ->
                        TermButton(choice.name.lowercase(), color = p.yellow, onClick = {
                            scope.launch { app.planner.resolveNoteConflict(note.id, choice); say("conflict resolved: ${choice.name.lowercase()}") }
                        })
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            TermTextField(value = editing.value, onValueChange = { editing.value = it }, label = "edit (markdown)", singleLine = false)
            Spacer(Modifier.height(6.dp))
            Text("preview", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
            MarkdownPreview(editing.value, onOpen = open, links = parseRefs(note.links), attachments = parseRefs(note.attachments))
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermButton("save", color = p.green, onClick = {
                    scope.launch { app.planner.saveNote(note.copy(body = editing.value)); say("note saved locally") }
                })
                TermButton("push .md", color = p.cyan, onClick = {
                    scope.launch {
                        when (val r = app.driveSync.pushNote(note.id)) {
                            is DriveSync.Outcome.Success -> say(r.message)
                            is DriveSync.Outcome.Failure -> say("failed: ${r.message}")
                            is DriveSync.Outcome.NeedsConsent -> say("drive consent needed — sign in again")
                        }
                    }
                })
                TermButton("pull .md", color = p.yellow, onClick = {
                    scope.launch {
                        when (val r = app.driveSync.pullNote(note.id)) {
                            is DriveSync.Outcome.Success -> say(r.message)
                            is DriveSync.Outcome.Failure -> say("failed: ${r.message}")
                            is DriveSync.Outcome.NeedsConsent -> say("drive consent needed — sign in again")
                        }
                    }
                })
            }
            Spacer(Modifier.height(4.dp))
            TermButton("delete", color = p.red, onClick = { scope.launch { app.planner.deleteNote(note); say("note deleted") } })
            Comment("drive markdown is two-way editable; a conflicting edit pauses sync and keeps both copies")
        }
    }
    if (notes.isEmpty()) Comment("# no notes yet")
}
