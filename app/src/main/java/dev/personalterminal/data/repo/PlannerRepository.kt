package dev.personalterminal.data.repo

import android.content.Context
import dev.personalterminal.data.db.AppDatabase
import dev.personalterminal.data.db.PlannerNote
import dev.personalterminal.data.db.PlannerProject
import dev.personalterminal.data.db.PlannerTask
import dev.personalterminal.domain.planner.NoteConflictChoice
import dev.personalterminal.domain.planner.NoteMarkdownSync
import dev.personalterminal.domain.planner.NoteSyncResolution
import dev.personalterminal.domain.planner.withChecklistItem
import dev.personalterminal.reminders.PlannerReminder
import dev.personalterminal.sync.SyncJournal
import dev.personalterminal.sync.noteConflict
import dev.personalterminal.sync.noteResolved
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One-off planner work, deliberately separate from [HabitRepository]: no streak, no schedule,
 * no XP. Every live row write is appended to the existing [SyncJournal] groundwork; bulk paths
 * (backup restore / merge) bypass it, as documented on SyncJournal.
 */
class PlannerRepository(private val context: Context, private val db: AppDatabase) {
    var journal: SyncJournal? = null

    private val projects = db.plannerProjectDao()
    private val tasks = db.plannerTaskDao()
    private val notes = db.plannerNoteDao()

    /** Bumped on every live write; the app uses it to refresh widgets / request a backup. */
    val mutations = MutableStateFlow(0)
    private fun changed() { mutations.value++ }

    fun observeProjects(): Flow<List<PlannerProject>> = projects.observeActive()
    fun observeTasks(): Flow<List<PlannerTask>> = tasks.observeOpen()
    fun observeAllTasks(): Flow<List<PlannerTask>> = tasks.observeAll()
    fun observeTasksForProject(projectId: Long): Flow<List<PlannerTask>> = tasks.observeForProject(projectId)
    fun observeNotes(): Flow<List<PlannerNote>> = notes.observeAll()
    fun observeConflicts(): Flow<List<PlannerNote>> = notes.observeConflicts()

    suspend fun allProjects(): List<PlannerProject> = projects.getAll()
    suspend fun allTasks(): List<PlannerTask> = tasks.getAll()
    suspend fun allNotes(): List<PlannerNote> = notes.getAll()
    suspend fun project(id: Long): PlannerProject? = projects.getById(id)
    suspend fun task(id: Long): PlannerTask? = tasks.getById(id)
    suspend fun note(id: Long): PlannerNote? = notes.getById(id)

    suspend fun saveProject(project: PlannerProject): Long {
        val row = project.copy(updatedAt = System.currentTimeMillis())
        val id = if (row.id == 0L) projects.insert(row) else { projects.update(row); row.id }
        journal?.upsert("planner_projects", id, row.copy(id = id))
        changed()
        return id
    }

    suspend fun deleteProject(project: PlannerProject) {
        projects.delete(project)
        journal?.delete("planner_projects", project.id)
        changed()
    }

    suspend fun saveTask(task: PlannerTask): Long {
        val row = task.copy(updatedAt = System.currentTimeMillis())
        val id = if (row.id == 0L) tasks.insert(row) else { tasks.update(row); row.id }
        val saved = row.copy(id = id)
        journal?.upsert("planner_tasks", id, saved)
        runCatching {
            if (!saved.completed && saved.reminderAt != null) PlannerReminder.schedule(context, saved)
            else PlannerReminder.cancel(context, id)
        }
        changed()
        return id
    }

    suspend fun deleteTask(task: PlannerTask) {
        tasks.delete(task)
        journal?.delete("planner_tasks", task.id)
        runCatching { PlannerReminder.cancel(context, task.id) }
        changed()
    }

    suspend fun setTaskCompleted(task: PlannerTask, completed: Boolean): PlannerTask {
        val row = task.copy(
            completed = completed,
            completedAt = if (completed) System.currentTimeMillis() else null,
            updatedAt = System.currentTimeMillis(),
        )
        saveTask(row)
        return row
    }

    suspend fun toggleChecklistItem(task: PlannerTask, index: Int, done: Boolean): PlannerTask {
        val row = task.withChecklistItem(index, done)
        if (row != task) saveTask(row)
        return row
    }

    suspend fun saveNote(note: PlannerNote): Long {
        val row = note.copy(updatedAt = System.currentTimeMillis())
        val id = if (row.id == 0L) notes.insert(row) else { notes.update(row); row.id }
        journal?.upsert("planner_notes", id, row.copy(id = id))
        changed()
        return id
    }

    suspend fun deleteNote(note: PlannerNote) {
        notes.delete(note)
        journal?.delete("planner_notes", note.id)
        changed()
    }

    /**
     * Two-way Drive Markdown pull. A divergence in both copies PAUSES note sync and retains both
     * versions on the row; no text is discarded. Returns the outcome for the caller to show.
     */
    suspend fun pullNoteMarkdown(noteId: Long, remoteBody: String, remoteAt: Long, driveFileId: String = ""): NoteSyncResolution {
        val note = notes.getById(noteId) ?: return NoteSyncResolution.Unchanged
        return when (val r = NoteMarkdownSync.resolve(note, remoteBody, remoteAt)) {
            is NoteSyncResolution.ApplyRemote -> {
                saveNote(
                    note.copy(
                        body = r.body, driveFileId = driveFileId.ifBlank { note.driveFileId },
                        lastSyncedBody = r.body, lastSyncedAt = r.remoteAt,
                        pendingRemoteBody = null, pendingRemoteAt = 0L, syncPaused = false,
                    ),
                )
                r
            }
            is NoteSyncResolution.Conflict -> {
                val paused = note.copy(
                    driveFileId = driveFileId.ifBlank { note.driveFileId },
                    pendingRemoteBody = r.remoteBody, pendingRemoteAt = r.remoteAt, syncPaused = true,
                )
                saveNote(paused)
                journal?.noteConflict(paused, r.remoteBody)
                r
            }
            is NoteSyncResolution.PushLocal, is NoteSyncResolution.Unchanged -> r
        }
    }

    /** The user picked local / Drive / both. Both versions stay in the resulting Markdown. */
    suspend fun resolveNoteConflict(noteId: Long, choice: NoteConflictChoice): PlannerNote? {
        val note = notes.getById(noteId) ?: return null
        val resolved = NoteMarkdownSync.resolveConflict(note, choice)
        saveNote(resolved)
        journal?.noteResolved(resolved, choice.name)
        return resolved
    }

    /** Called after a successful Drive Markdown upload: the pushed text becomes the new base. */
    suspend fun markNoteSynced(noteId: Long, driveFileId: String, body: String): PlannerNote? {
        val note = notes.getById(noteId) ?: return null
        if (note.syncPaused) return null // never silently resolve a pending conflict
        val now = System.currentTimeMillis()
        val synced = note.copy(
            body = body, driveFileId = driveFileId, lastSyncedBody = body, lastSyncedAt = now,
            pendingRemoteBody = null, pendingRemoteAt = 0L, syncPaused = false, updatedAt = now,
        )
        saveNote(synced)
        return synced
    }
}
