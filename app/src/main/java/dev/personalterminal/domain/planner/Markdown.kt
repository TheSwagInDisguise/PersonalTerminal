package dev.personalterminal.domain.planner

/**
 * Small, dependency-free Markdown reader for planner notes. It covers the subset the app writes:
 * headings, ordered/bulleted lists, task boxes, quotes, fenced/inline code, tables, horizontal
 * rules, bold, italic, strikethrough, links and images. No HTML is executed — the preview is a
 * styled, read-only rendering, never a WebView.
 */
enum class MarkdownKind { HEADING, SUBHEADING, BODY, BULLET, ORDERED, CHECK, QUOTE, CODE, RULE, TABLE }

data class MarkdownSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
    val imageUrl: String? = null,
)

data class MarkdownLine(
    val kind: MarkdownKind,
    val spans: List<MarkdownSpan>,
    val checked: Boolean = false,
    val cells: List<String> = emptyList(),
    val tableHeader: Boolean = false,
    val marker: String = "",
)

object Markdown {
    // Group order matters: image, bold, strike, italic, code, link.
    private val INLINE = Regex(
        """!\[([^\]]+)\]\(([^)]+)\)|\*\*(.+?)\*\*|~~(.+?)~~|\*(.+?)\*|`(.+?)`|\[([^\]]+)\]\(([^)]+)\)""",
    )
    private val ORDERED = Regex("""^(\d+)\.\s+(.*)$""")
    private val TABLE_SEPARATOR = Regex("""^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)+\|?\s*$""")
    private val PLAIN_SEPARATOR = Regex("""^[-|:\s*_]+$""")

    fun parse(src: String): List<MarkdownLine> {
        var fence = false
        var tableStarted = false
        return src.lines().mapNotNull { raw ->
            val line = raw.trimEnd()
            if (line.startsWith("```")) { fence = !fence; tableStarted = false; return@mapNotNull null }
            if (line.isBlank()) { tableStarted = false; return@mapNotNull MarkdownLine(MarkdownKind.BODY, emptyList()) }
            if (fence) return@mapNotNull MarkdownLine(MarkdownKind.CODE, inline(line, stripMarkers = false))
            if (TABLE_SEPARATOR.matches(line)) { tableStarted = true; return@mapNotNull null }
            if (line.trimStart().startsWith("|")) {
                val cells = line.trim().trim('|').split('|').map { it.trim() }
                val header = !tableStarted
                tableStarted = true
                return@mapNotNull MarkdownLine(
                    MarkdownKind.TABLE,
                    cells.map { MarkdownSpan(it) },
                    cells = cells,
                    tableHeader = header,
                )
            }
            tableStarted = false
            if (line.length >= 3 && (line.all { it == '-' } || line.all { it == '*' } || line.all { it == '_' })) {
                return@mapNotNull MarkdownLine(MarkdownKind.RULE, listOf(MarkdownSpan("─".repeat(24))))
            }
            val ordered = ORDERED.matchEntire(line)
            if (ordered != null) {
                return@mapNotNull MarkdownLine(
                    MarkdownKind.ORDERED,
                    inline(ordered.groupValues[2]),
                    marker = "${ordered.groupValues[1]}. ",
                )
            }
            val (kind, text, checked) = when {
                line.startsWith("### ") || line.startsWith("## ") ->
                    Triple(MarkdownKind.SUBHEADING, line.removePrefix("### ").removePrefix("## "), false)
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
                m.groupValues[1].isNotEmpty() -> out += MarkdownSpan(m.groupValues[1], imageUrl = m.groupValues[2])
                m.groupValues[3].isNotEmpty() -> out += MarkdownSpan(m.groupValues[3], bold = true)
                m.groupValues[4].isNotEmpty() -> out += MarkdownSpan(m.groupValues[4], strike = true)
                m.groupValues[5].isNotEmpty() -> out += MarkdownSpan(m.groupValues[5], italic = true)
                m.groupValues[6].isNotEmpty() -> out += MarkdownSpan(m.groupValues[6], code = true)
                m.groupValues[7].isNotEmpty() -> out += MarkdownSpan(m.groupValues[7], url = m.groupValues[8])
            }
            cursor = m.range.last + 1
        }
        if (cursor < text.length) out += MarkdownSpan(text.substring(cursor))
        return out.ifEmpty { listOf(MarkdownSpan(text)) }
    }

    /** Every inline `[label](uri)` link, for the contextual link chips under a preview. */
    fun links(src: String): List<PlannerRef> = src.lines().flatMap { line ->
        INLINE.findAll(line).mapNotNull { m ->
            if (m.groupValues[7].isNotEmpty()) PlannerRef(m.groupValues[7], m.groupValues[8]) else null
        }.toList()
    }

    /** Every inline `![alt](uri)` image, for the attachment chips under a preview. */
    fun images(src: String): List<PlannerRef> = src.lines().flatMap { line ->
        INLINE.findAll(line).mapNotNull { m ->
            if (m.groupValues[1].isNotEmpty()) PlannerRef(m.groupValues[1], m.groupValues[2]) else null
        }.toList()
    }

    /** Plain-text one-liner used in list rows and terminal-style prompts. */
    fun plain(src: String): String = src.lines().map { line ->
        var s = INLINE.replace(line) { m ->
            when {
                m.groupValues[1].isNotEmpty() -> m.groupValues[1]
                m.groupValues[3].isNotEmpty() -> m.groupValues[3]
                m.groupValues[4].isNotEmpty() -> m.groupValues[4]
                m.groupValues[5].isNotEmpty() -> m.groupValues[5]
                m.groupValues[6].isNotEmpty() -> m.groupValues[6]
                m.groupValues[7].isNotEmpty() -> m.groupValues[7]
                else -> m.value
            }
        }
        s = ORDERED.matchEntire(s)?.groupValues?.get(2) ?: s
        s = s.trim().trim('|').trim()
        if (PLAIN_SEPARATOR.matches(s) && s.count { it == '-' || it == '*' || it == '_' } >= 3) return@map ""
        s = s
            .removePrefix("### ").removePrefix("## ").removePrefix("# ").removePrefix("> ")
            .removePrefix("- [ ] ").removePrefix("- [x] ").removePrefix("- [X] ")
            .removePrefix("- ").removePrefix("* ")
            .trim()
        s.replace('|', ' ')
    }.filter { it.isNotBlank() }.joinToString(" ")
}
