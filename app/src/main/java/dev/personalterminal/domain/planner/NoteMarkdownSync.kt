package dev.personalterminal.domain.planner

import dev.personalterminal.data.db.PlannerNote
import dev.personalterminal.data.db.hasPendingConflict

/** What the user chose when a Drive Markdown note diverged from the local copy. */
enum class NoteConflictChoice { LOCAL, REMOTE, BOTH }

sealed interface NoteSyncResolution {
    /** Only Drive changed since the common base: apply it locally. */
    data class ApplyRemote(val body: String, val remoteAt: Long) : NoteSyncResolution
    /** Only the local copy changed since the common base: push it, do not overwrite it. */
    data class PushLocal(val body: String) : NoteSyncResolution
    /** Both sides changed. Pause note sync, retain BOTH versions until the user chooses. */
    data class Conflict(val localBody: String, val remoteBody: String, val remoteAt: Long) : NoteSyncResolution
    data object Unchanged : NoteSyncResolution
}

/**
 * Two-way editable Drive Markdown.
 *
 * A note keeps [PlannerNote.lastSyncedBody] as the last agreed base. A pull is safe in one
 * direction when only one side changed; when both did, sync PAUSES and the Drive text is stored
 * in [PlannerNote.pendingRemoteBody] while [PlannerNote.body] keeps the local text. Nothing is
 * overwritten and no further note sync runs until [resolveConflict] records a choice.
 *
 * This is a Markdown-file sync, NOT multi-device database sync: DriveSync still moves whole
 * `.ptbak` archives, and the append-only `sync_log` has no transport/replay yet.
 */
object NoteMarkdownSync {

    fun resolve(local: PlannerNote, remoteBody: String, remoteAt: Long): NoteSyncResolution = when {
        local.hasPendingConflict -> NoteSyncResolution.Conflict(
            local.body,
            local.pendingRemoteBody ?: remoteBody,
            local.pendingRemoteAt.takeIf { it > 0 } ?: remoteAt,
        )
        local.body == remoteBody -> NoteSyncResolution.Unchanged
        remoteBody == local.lastSyncedBody -> NoteSyncResolution.PushLocal(local.body)
        local.body == local.lastSyncedBody -> NoteSyncResolution.ApplyRemote(remoteBody, remoteAt)
        else -> NoteSyncResolution.Conflict(local.body, remoteBody, remoteAt)
    }

    /** Applies the user's choice. BOTH concatenates the two versions, keeping every byte of each. */
    fun resolveConflict(note: PlannerNote, choice: NoteConflictChoice): PlannerNote {
        val remote = note.pendingRemoteBody ?: return note.copy(syncPaused = false, pendingRemoteBody = null)
        val now = System.currentTimeMillis()
        val body = when (choice) {
            NoteConflictChoice.LOCAL -> note.body
            NoteConflictChoice.REMOTE -> remote
            NoteConflictChoice.BOTH -> listOf(
                note.body.trimEnd(),
                "---",
                "<!-- drive version retained: ${java.time.Instant.ofEpochMilli(note.pendingRemoteAt)} -->",
                remote.trimEnd(),
            ).joinToString("\n\n")
        }
        return note.copy(
            body = body,
            lastSyncedBody = body,
            lastSyncedAt = now,
            pendingRemoteBody = null,
            pendingRemoteAt = 0L,
            syncPaused = false,
            updatedAt = now,
        )
    }

    /** Stable Drive filename for a note (id first so two notes with the same title never collide). */
    fun fileName(note: PlannerNote): String {
        val slug = note.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40)
        return "note-${note.id}-${slug.ifBlank { "untitled" }}.md"
    }

    /** The Markdown file body is the note text; front matter records the title for round-trips. */
    fun toMarkdown(note: PlannerNote): String = buildString {
        append("---\n")
        append("title: ").append(note.title.replace('\n', ' ')).append('\n')
        append("note-id: ").append(note.id).append('\n')
        append("---\n\n")
        append(note.body)
    }

    fun titleFromMarkdown(md: String): String? {
        if (!md.startsWith("---\n")) return null
        return md.lineSequence().drop(1).takeWhile { it != "---" }
            .firstOrNull { it.startsWith("title: ") }?.removePrefix("title: ")
    }

    fun bodyFromMarkdown(md: String): String {
        if (!md.startsWith("---\n")) return md
        val end = md.indexOf("\n---\n", 4)
        return if (end < 0) md else md.substring(end + 5).removePrefix("\n")
    }
}
