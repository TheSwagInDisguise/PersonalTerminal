package dev.personalterminal.planner

import dev.personalterminal.data.db.PlannerNote
import dev.personalterminal.data.db.PlannerPriority
import dev.personalterminal.data.db.PlannerTask
import dev.personalterminal.data.db.hasPendingConflict
import dev.personalterminal.domain.planner.Markdown
import dev.personalterminal.domain.planner.MarkdownKind
import dev.personalterminal.domain.planner.NoteConflictChoice
import dev.personalterminal.domain.planner.NoteMarkdownSync
import dev.personalterminal.domain.planner.NoteSyncResolution
import dev.personalterminal.domain.planner.PlannerRef
import dev.personalterminal.domain.planner.checklistItems
import dev.personalterminal.domain.planner.dueDate
import dev.personalterminal.domain.planner.encodeRefs
import dev.personalterminal.domain.planner.parseRefs
import dev.personalterminal.domain.planner.priorityValue
import dev.personalterminal.domain.planner.withChecklistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Pure planner logic: fast, no Android/Robolectric, run on every build. */
class PlannerLogicTest {

    @Test fun `one-off tasks keep project, priority, due date, reminder and checklist`() {
        val task = PlannerTask(
            title = "service the Speedmaster", projectId = 4, priority = PlannerPriority.HIGH.name,
            dueDay = LocalDate.of(2026, 11, 20).toEpochDay(), reminderAt = 1_700_000_000_000L,
            checklist = "0|book\n1|box",
        )
        assertEquals(PlannerPriority.HIGH, task.priorityValue)
        assertEquals(LocalDate.of(2026, 11, 20), task.dueDate)
        assertEquals(4L, task.projectId)
        assertEquals(2, task.checklistItems.size)
        assertFalse(task.checklistItems[0].done)
        assertTrue(task.checklistItems[1].done)
        val ticked = task.withChecklistItem(0, true)
        assertTrue(ticked.checklistItems[0].done)
        assertTrue(ticked.checklistItems[1].done)
        assertFalse(ticked.withChecklistItem(1, false).checklistItems[1].done)
    }

    @Test fun `contextual links and attachments round trip`() {
        val refs = listOf(
            PlannerRef("watch", "pt://watch/7"),
            PlannerRef("receipt", "content://media/12"),
            PlannerRef("habit", "pt://habit/3"),
        )
        val decoded = parseRefs(encodeRefs(refs))
        assertEquals(refs, decoded)
        assertEquals("watch/7", decoded[0].internalRoute)
        assertEquals("habit/3", decoded[2].internalRoute)
        assertEquals("planner", PlannerRef("task", "pt://task/5").internalRoute)
        assertEquals(null, decoded[1].internalRoute)
    }

    @Test fun `markdown parses headings, checkboxes, bold, code and links`() {
        val lines = Markdown.parse("# Heading\n\n- [x] packed\n- **bold** and `code`\n> quote\n[drive](https://example.com)")
        assertTrue(lines.any { it.kind == MarkdownKind.HEADING && it.spans.any { s -> s.text == "Heading" } })
        val check = lines.first { it.kind == MarkdownKind.CHECK }
        assertTrue(check.checked)
        val bold = lines.first { it.spans.any { s -> s.bold } }
        assertTrue(bold.spans.any { it.text == "bold" && it.bold })
        assertTrue(lines.first { it.spans.any { s -> s.code } }.spans.any { it.code })
        assertEquals(listOf(PlannerRef("drive", "https://example.com")), Markdown.links("[drive](https://example.com)"))
        assertEquals("Heading packed bold and code quote drive", Markdown.plain("# Heading\n\n- [x] packed\n- **bold** and `code`\n> quote\n[drive](https://example.com)"))
    }

    @Test fun `two-way markdown sync applies one-sided edits without clobbering`() {
        val base = PlannerNote(id = 1, title = "trip", body = "# v1", lastSyncedBody = "# v1")
        assertTrue(NoteMarkdownSync.resolve(base, "# v2", 100L) is NoteSyncResolution.ApplyRemote)
        val localEdit = base.copy(body = "# local")
        assertTrue(NoteMarkdownSync.resolve(localEdit, "# v1", 100L) is NoteSyncResolution.PushLocal)
        assertTrue(NoteMarkdownSync.resolve(base, "# v1", 100L) is NoteSyncResolution.Unchanged)
    }

    @Test fun `conflicting note pauses sync and retains both versions until a choice`() {
        val diverged = PlannerNote(id = 2, title = "trip", body = "# local", lastSyncedBody = "# v1")
        val conflict = NoteMarkdownSync.resolve(diverged, "# drive", 200L)
        assertTrue(conflict is NoteSyncResolution.Conflict)
        conflict as NoteSyncResolution.Conflict
        assertEquals("# local", conflict.localBody)
        assertEquals("# drive", conflict.remoteBody)

        val paused = diverged.copy(pendingRemoteBody = conflict.remoteBody, pendingRemoteAt = 200L, syncPaused = true)
        assertTrue(paused.hasPendingConflict)
        // a second pull must not overwrite either version
        assertTrue(NoteMarkdownSync.resolve(paused, "# drive v3", 300L) is NoteSyncResolution.Conflict)

        val keepBoth = NoteMarkdownSync.resolveConflict(paused, NoteConflictChoice.BOTH)
        assertTrue(keepBoth.body.contains("# local"))
        assertTrue(keepBoth.body.contains("# drive"))
        assertFalse(keepBoth.hasPendingConflict)
        assertEquals(null, keepBoth.pendingRemoteBody)
        assertEquals(keepBoth.body, keepBoth.lastSyncedBody)

        val keepRemote = NoteMarkdownSync.resolveConflict(paused, NoteConflictChoice.REMOTE)
        assertEquals("# drive", keepRemote.body)
        val keepLocal = NoteMarkdownSync.resolveConflict(paused, NoteConflictChoice.LOCAL)
        assertEquals("# local", keepLocal.body)
    }

    @Test fun `drive markdown front matter round trips the body`() {
        val note = PlannerNote(id = 9, title = "packing list", body = "# bag\n- [ ] passport")
        val md = NoteMarkdownSync.toMarkdown(note)
        assertEquals("packing list", NoteMarkdownSync.titleFromMarkdown(md))
        assertEquals(note.body, NoteMarkdownSync.bodyFromMarkdown(md))
        assertTrue(NoteMarkdownSync.fileName(note).startsWith("note-9-"))
        assertNotNull(NoteMarkdownSync.fileName(note))
    }
}
