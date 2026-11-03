package dev.personalterminal.domain.planner

import dev.personalterminal.data.db.PlannerPriority
import dev.personalterminal.data.db.PlannerTask
import java.time.LocalDate

/** A contextual link or attachment: `pt://habit/3`, `https://…`, a content:// Uri or a file path. */
data class PlannerRef(val label: String, val uri: String) {
    /** In-app links are opened with the navigation controller; everything else with ACTION_VIEW. */
    val internalRoute: String?
        get() = when {
            uri.startsWith("pt://habit/") -> "habit/${uri.removePrefix("pt://habit/")}"
            uri.startsWith("pt://watch/") -> "watch/${uri.removePrefix("pt://watch/")}"
            uri.startsWith("pt://task/") -> "planner"
            uri.startsWith("pt://project/") -> "planner"
            uri.startsWith("pt://note/") -> "planner"
            else -> null
        }
}

/** Tab-separated records keep a flat, human-readable backup payload (no nested JSON in rows). */
fun parseRefs(raw: String): List<PlannerRef> = raw.lines().mapNotNull { line ->
    val sep = line.indexOf('\t')
    if (sep < 0) {
        val v = line.trim()
        if (v.isEmpty()) null else PlannerRef(v, v)
    } else {
        val label = line.substring(0, sep).trim().ifBlank { line.substring(sep + 1).trim() }
        val uri = line.substring(sep + 1).trim()
        if (uri.isEmpty()) null else PlannerRef(label, uri)
    }
}

fun encodeRefs(refs: List<PlannerRef>): String = refs.joinToString("\n") { "${it.label.replace('\t', ' ')}\t${it.uri.replace('\t', ' ')}" }

data class PlannerCheckItem(val label: String, val done: Boolean)

/** Checklist rows are `"0|label"` / `"1|label"`; unknown old rows count as unchecked. */
val PlannerTask.checklistItems: List<PlannerCheckItem>
    get() = checklist.lines().mapNotNull { line ->
        val sep = line.indexOf('|')
        val label = (if (sep < 0) line else line.substring(sep + 1)).trim()
        if (label.isEmpty()) null else PlannerCheckItem(label, sep >= 0 && line.substring(0, sep).trim() == "1")
    }

/** A copy with one checklist item ticked/unticked. Pure, so it is trivially unit-testable. */
fun PlannerTask.withChecklistItem(index: Int, done: Boolean): PlannerTask {
    val items = checklistItems.toMutableList()
    if (index !in items.indices) return this
    items[index] = items[index].copy(done = done)
    return copy(checklist = items.joinToString("\n") { "${if (it.done) 1 else 0}|${it.label}" }, updatedAt = System.currentTimeMillis())
}

val PlannerTask.priorityValue: PlannerPriority get() = PlannerPriority.of(priority)
val PlannerTask.dueDate: LocalDate? get() = dueDay?.let { LocalDate.ofEpochDay(it) }
val PlannerTask.checklistDone: Int get() = checklistItems.count { it.done }
val PlannerTask.checklistTotal: Int get() = checklistItems.size
/** Urgent / high first, then the earliest due date; undated tasks come last. */
val PlannerTask.sortKey: Long
    get() = -priorityValue.rank.toLong() * 1_000_000_000L - (dueDay ?: Long.MAX_VALUE / 2)
