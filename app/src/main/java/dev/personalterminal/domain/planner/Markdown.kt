package dev.personalterminal.domain.planner

/**
 * Small, dependency-free Markdown reader for planner notes. It covers the subset the app writes:
 * headings, bullets, task boxes, quotes, fenced/inline code, bold, italic and links. No HTML is
 * executed — the preview is a styled, read-only rendering, never a WebView.
 */
enum class MarkdownKind { HEADING, SUBHEADING, BODY, BULLET, CHECK, QUOTE, CODE }

data class MarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

data class MarkdownLine(val kind: MarkdownKind, val spans: List<MarkdownSpan>, val checked: Boolean = false)

object Markdown {
    private val INLINE = Regex("""\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`|\[([^\]]+)\]\(([^)]+)\)""")

    fun parse(src: String): List<MarkdownLine> {
        var fence = false
        return src.lines().mapNotNull { raw ->
            val line = raw.trimEnd()
            if (line.startsWith("```")) { fence = !fence; return@mapNotNull null }
            if (line.isBlank()) return@mapNotNull MarkdownLine(MarkdownKind.BODY, emptyList())
            if (fence) return@mapNotNull MarkdownLine(MarkdownKind.CODE, inline(line, stripMarkers = false))
            val (kind, text, checked) = when {
                line.startsWith("## ") -> Triple(MarkdownKind.SUBHEADING, line.removePrefix("## "), false)
                line.startsWith("# ") -> Triple(MarkdownKind.HEADING, line.removePrefix("# "), false)
                line.startsWith("- [ ] ") -> Triple(MarkdownKind.CHECK, line.removePrefix("- [ ] "), false)
                line.startsWith("- [x] ") || line.startsWith("- [X] ") ->
                    Triple(MarkdownKind.CHECK, line.substring(6), true)
                line.startsWith("- ") || line.startsWith("* ") -> Triple(MarkdownKind.BULLET, line.substring(2), false)
                line.startsWith("> ") -> Triple(MarkdownKind.QUOTE, line.removePrefix("> "), false)
                line.startsWith("    ") -> Triple(MarkdownKind.CODE, line.trimStart(), false)
                else -> Triple(MarkdownKind.BODY, line, false)
            }
            MarkdownLine(kind, inline(text), checked)
        }
    }

    /** Inline Markdown → typed spans. Unknown syntax is kept as literal text. */
    fun inline(text: String, stripMarkers: Boolean = true): List<MarkdownSpan> {
        if (!stripMarkers) return listOf(MarkdownSpan(text, code = true))
        val out = mutableListOf<MarkdownSpan>()
        var cursor = 0
        INLINE.findAll(text).forEach { m ->
            if (m.range.first > cursor) out += MarkdownSpan(text.substring(cursor, m.range.first))
            when {
                m.groupValues[1].isNotEmpty() -> out += MarkdownSpan(m.groupValues[1], bold = true)
                m.groupValues[2].isNotEmpty() -> out += MarkdownSpan(m.groupValues[2], italic = true)
                m.groupValues[3].isNotEmpty() -> out += MarkdownSpan(m.groupValues[3], code = true)
                m.groupValues[4].isNotEmpty() -> out += MarkdownSpan(m.groupValues[4], url = m.groupValues[5])
            }
            cursor = m.range.last + 1
        }
        if (cursor < text.length) out += MarkdownSpan(text.substring(cursor))
        return out.ifEmpty { listOf(MarkdownSpan(text)) }
    }

    /** Every inline `[label](uri)` link, for the contextual link chips under a preview. */
    fun links(src: String): List<PlannerRef> = src.lines().flatMap { line ->
        INLINE.findAll(line).mapNotNull { m ->
            if (m.groupValues[4].isNotEmpty()) PlannerRef(m.groupValues[4], m.groupValues[5]) else null
        }.toList()
    }

    /** Plain-text one-liner used in list rows and terminal-style prompts. */
    fun plain(src: String): String = src.lines().map { line ->
        INLINE.replace(line) { m ->
            when {
                m.groupValues[1].isNotEmpty() -> m.groupValues[1]
                m.groupValues[2].isNotEmpty() -> m.groupValues[2]
                m.groupValues[3].isNotEmpty() -> m.groupValues[3]
                m.groupValues[4].isNotEmpty() -> m.groupValues[4]
                else -> m.value
            }
        }
            .removePrefix("# ").removePrefix("## ").removePrefix("> ")
            .removePrefix("- [ ] ").removePrefix("- [x] ").removePrefix("- [X] ")
            .removePrefix("- ").removePrefix("* ")
            .trim()
    }.filter { it.isNotEmpty() }.joinToString(" ")
}
