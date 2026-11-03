package dev.personalterminal.ui.planner

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.personalterminal.domain.planner.Markdown
import dev.personalterminal.domain.planner.MarkdownKind
import dev.personalterminal.domain.planner.MarkdownSpan
import dev.personalterminal.domain.planner.PlannerRef
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.theme.Term

/**
 * Read-only Markdown preview for planner notes. No WebView and no HTML: headings, bullets, task
 * boxes, quotes, code, bold/italic/links are rendered as styled text, and every inline or stored
 * link/attachment becomes a contextual chip the caller can open.
 */
@Composable
fun MarkdownPreview(
    markdown: String,
    onOpen: (PlannerRef) -> Unit,
    modifier: Modifier = Modifier,
    links: List<PlannerRef> = emptyList(),
    attachments: List<PlannerRef> = emptyList(),
) {
    val p = Term.palette
    val lines = remember(markdown) { Markdown.parse(markdown) }
    val inlineLinks = remember(markdown) { Markdown.links(markdown) }
    val allLinks = remember(inlineLinks, links) { (inlineLinks + links).distinctBy { it.uri } }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { line ->
            val prefix = when (line.kind) {
                MarkdownKind.HEADING, MarkdownKind.SUBHEADING -> ""
                MarkdownKind.BULLET -> "•  "
                MarkdownKind.CHECK -> if (line.checked) "[x] " else "[ ] "
                MarkdownKind.QUOTE -> "│  "
                MarkdownKind.CODE -> "   "
                MarkdownKind.BODY -> ""
            }
            val base = when (line.kind) {
                MarkdownKind.HEADING -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                MarkdownKind.SUBHEADING -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                MarkdownKind.CODE -> MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                else -> MaterialTheme.typography.bodyMedium
            }
            val textColor = when (line.kind) {
                MarkdownKind.HEADING, MarkdownKind.SUBHEADING -> p.green
                MarkdownKind.QUOTE -> p.fgDim
                MarkdownKind.CODE -> p.cyan
                MarkdownKind.CHECK -> if (line.checked) p.fgDim else p.fg
                else -> p.fg
            }
            Row(Modifier.fillMaxWidth()) {
                if (prefix.isNotEmpty()) Text(prefix, color = p.fgDim, style = base)
                Text(
                    text = annotated(line.spans, p.cyan),
                    color = textColor,
                    style = base,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        if (allLinks.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("links:", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
            allLinks.forEach { ref ->
                RefChip(ref, p.cyan, onOpen)
            }
        }
        if (attachments.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("attachments:", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
            attachments.forEach { ref -> RefChip(ref, p.yellow, onOpen) }
        }
    }
}

@Composable
private fun RefChip(ref: PlannerRef, color: Color, onOpen: (PlannerRef) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .border(1.dp, color.copy(alpha = 0.5f), androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
            .clickable { onOpen(ref) }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(ref.label, color = color, style = MaterialTheme.typography.labelMedium)
        Text(
            if (ref.internalRoute != null) "in-app →" else "open →",
            color = Term.palette.fgDim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun annotated(spans: List<MarkdownSpan>, linkColor: Color): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        if (span.text.isEmpty()) return@forEach
        val style = when {
            span.code -> SpanStyle(fontFamily = FontFamily.Monospace, color = linkColor)
            span.url != null -> SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
            span.bold -> SpanStyle(fontWeight = FontWeight.Bold)
            span.italic -> SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            else -> SpanStyle()
        }
        withStyle(style) { append(span.text) }
    }
}

/** A compact one-line rendering used in task rows and terminal prompts. */
@Composable
fun MarkdownLine(text: String, style: TextStyle = MaterialTheme.typography.bodySmall) {
    Text(Markdown.plain(text), color = Term.palette.fgDim, style = style, maxLines = 1)
}

/** Small helper so callers can offer a share/export button without duplicating the label. */
@Composable
fun MarkdownExportButton(label: String, onClick: () -> Unit) {
    TermButton(label, onClick = onClick, color = Term.palette.cyan)
}
