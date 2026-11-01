package dev.personalterminal.ui.watch

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.db.Watch
import dev.personalterminal.data.db.WatchDocument
import dev.personalterminal.data.db.WatchPhoto
import dev.personalterminal.data.db.WatchService
import dev.personalterminal.data.db.displayName
import dev.personalterminal.data.db.inRepair
import dev.personalterminal.data.db.owned
import dev.personalterminal.data.db.sold
import dev.personalterminal.ui.components.Comment
import dev.personalterminal.ui.components.KeyValue
import dev.personalterminal.ui.components.PromptLine
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.components.TerminalPanel
import dev.personalterminal.ui.theme.Term
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Everything the insurance-style report renders (gathered once in the screen). */
private data class ReportModel(
    val active: List<Watch>,
    val sold: List<Watch>,
    val photos: Map<Long, List<WatchPhoto>>,
    val docs: Map<Long, List<WatchDocument>>,
    val services: Map<Long, List<WatchService>>,
)

@Composable
fun CollectionReportScreen(app: PersonalTerminalApp, nav: NavHostController) {
    val p = Term.palette
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var model by remember { mutableStateOf<ReportModel?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val ws = app.watches.allWatches()
        val active = ws.filter { it.owned || it.inRepair }
        val sold = ws.filter { it.sold }
        val photos = active.associateWith { app.watches.observeWatchPhotos(it.id).first() }
        val docs = active.associateWith { app.watches.observeWatchDocuments(it.id).first() }
        val services = app.watches.observeAllServices().first().groupBy { it.watchId }
        model = ReportModel(active, sold, photos, docs, services)
    }

    fun sharePdf() {
        val m = model ?: return
        scope.launch {
            runCatching {
                val f = buildReportPdf(ctx, m)
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                val i = Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(i, "share collection report"))
                status = "report ready: ${f.name}"
            }.onFailure { status = "pdf failed: ${it.message}" }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PromptLine("collection report", trailing = "insurance / archive")
        val m = model
        if (m == null) { Comment("collecting…"); return@Column }

        TerminalPanel(title = "summary", titleColor = p.yellow) {
            val totalValue = m.active.sumOf { it.currentValue ?: 0.0 }
            val currency = m.active.firstOrNull { it.currency.isNotBlank() }?.currency ?: ""
            KeyValue("pieces", "${m.active.size} active · ${m.sold.size} sold")
            if (totalValue > 0) KeyValue("current value", "$currency ${"%,.0f".format(totalValue)}".trim(), valueColor = p.green)
            KeyValue("photos", "${m.photos.values.sumOf { it.size }} gallery · profile shots included")
            KeyValue("documents", "${m.docs.values.sumOf { it.size }} attached")
            Spacer(Modifier.height(6.dp))
            TermButton("generate & share pdf", filled = true, color = p.yellow, onClick = { sharePdf() })
            status?.let { Spacer(Modifier.height(4.dp)); Text("# $it", color = p.green, style = MaterialTheme.typography.bodySmall) }
        }

        TerminalPanel(title = "contents") {
            m.active.forEach { w ->
                val bought = w.purchaseDay?.let { LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("dd MMM yyyy")) }
                Text(
                    w.displayName +
                        (w.reference.ifBlank { "" }.let { if (it.isBlank()) "" else "  · ref $it" }) +
                        (bought?.let { "  · bought $it" } ?: "") +
                        (w.currentValue?.let { "  · ${w.currency} ${"%,.0f".format(it)}" } ?: ""),
                    color = p.fg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Comment("pdf layout: photo · reference · purchase · value · service history · documents · totals")
        }
        TermButton("← back", color = p.fgDim, onClick = { nav.popBackStack() })
        Spacer(Modifier.height(8.dp))
    }
}

// ------------------------------------------------------------------ PDF

/** A4 points */
private const val PAGE_W = 595
private const val PAGE_H = 842

private val INK: Int = 0xFF1A1A1A.toInt()
private val DIM: Int = 0xFF6B6B62.toInt()
private val RULE: Int = 0xFFC9C9BE.toInt()
private val LIME: Int = 0xFF597321.toInt() // print-safe signal green (newsprint pair)

private fun buildReportPdf(context: Context, m: ReportModel): File {
    val doc = PdfDocument()
    val title = Paint().apply { color = INK; textSize = 16f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    val head = Paint().apply { color = INK; textSize = 12f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    val body = Paint().apply { color = INK; textSize = 9.5f; typeface = Typeface.MONOSPACE }
    val dim = Paint().apply { color = DIM; textSize = 9f; typeface = Typeface.MONOSPACE }
    val rule = Paint().apply { color = RULE; strokeWidth = 1f }
    val accent = Paint().apply { color = LIME; strokeWidth = 3f }

    var num = 1
    var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, num).create())
    var c = page.canvas
    var y = 56f
    val left = 46f
    val right = (PAGE_W - 46).toFloat()

    fun newPage() {
        doc.finishPage(page)
        num++
        page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, num).create())
        c = page.canvas
        y = 56f
    }

    fun need(h: Float) { if (y + h > PAGE_H - 56f) newPage() }

    fun line(text: String, paint: Paint, gap: Float) {
        need(gap)
        c.drawText(text, left, y, paint)
        y += gap
    }

    // ---- header
    c.drawText("PERSONAL TERMINAL — COLLECTION REPORT", left, y, title)
    y += 18f
    c.drawRect(left, y, right, y + 3f, accent)
    y += 20f
    line(
        "generated ${LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"))} · ${m.active.size} active · ${m.sold.size} sold",
        dim, 14f,
    )
    y += 6f

    m.active.forEachIndexed { idx, w ->
        need(160f)
        c.drawText("${idx + 1}. ${w.displayName}".uppercase(), left, y, head)
        y += 6f
        c.drawLine(left, y, right, y, rule)
        y += 14f

        fun kv(label: String, value: String) = line("${label.padEnd(16)}$value", body, 13f)
        if (w.reference.isNotBlank()) kv("reference", w.reference)
        if (w.movement.isNotBlank()) kv("movement", w.movement)
        w.purchaseDay?.let { kv("purchased", LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("dd MMM yyyy"))) }
        val cur = w.currency.ifBlank { "" }
        w.purchasePrice?.let { kv("paid", "$cur ${"%,.0f".format(it)}".trim()) }
        w.currentValue?.let { kv("current value", "$cur ${"%,.0f".format(it)}".trim()) }
        w.soldPrice?.let { kv("sold for", "$cur ${"%,.0f".format(it)}".trim()) }
        if (w.serviceIntervalMonths > 0) kv("service every", "${w.serviceIntervalMonths} months")
        if (w.notes.isNotBlank()) kv("notes", w.notes.replace('\n', ' ').take(90))

        m.services[w.id]?.sortedBy { it.day }?.forEach { s ->
            kv(
                "service",
                LocalDate.ofEpochDay(s.day).format(DateTimeFormatter.ofPattern("dd MMM yyyy")) +
                    (if (s.cost != null) "  ${cur} ${"%,.0f".format(s.cost!!)}" else "") +
                    (if (s.notes.isNotBlank()) "  ${s.notes.take(40)}" else ""),
            )
        }
        val docs = m.docs[w.id].orEmpty()
        if (docs.isEmpty() && m.photos[w.id].isNullOrEmpty()) {
            // nothing else to list
        }
        docs.forEach { d -> kv("document", "[${d.kind}] ${d.label}") }

        // profile photo + first gallery shot, right column
        val files = buildList {
            w.photoPath?.let { add(File(context.filesDir, "watch_photos/$it")) }
            m.photos[w.id]?.forEach { add(File(context.filesDir, "watch_photos/${it.path}")) }
        }.filter { it.exists() }.take(2)
        if (files.isNotEmpty()) {
            val blockTop = y - 12f
            var py = blockTop
            files.forEach { f ->
                val bmp = runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() ?: return@forEach
                val side = 64f
                val scaled = Bitmap.createScaledBitmap(bmp, side.toInt(), side.toInt(), true)
                if (scaled !== bmp) bmp.recycle()
                need(side + 8f)
                c.drawBitmap(scaled, right - side, py, null)
                scaled.recycle()
                py += side + 6f
            }
            if (py > y) y = py + 4f
        }

        y += 8f
        c.drawLine(left, y, right, y, rule)
        y += 16f
    }

    // ---- totals
    need(70f)
    c.drawRect(left, y, right, y + 3f, accent)
    y += 22f
    val totalValue = m.active.sumOf { it.currentValue ?: 0.0 }
    val cur = m.active.firstOrNull { it.currency.isNotBlank() }?.currency ?: ""
    line("TOTALS", head, 16f)
    line("active pieces          ${m.active.size}", body, 13f)
    if (totalValue > 0) line("current value          $cur ${"%,.0f".format(totalValue)}".trim(), body, 13f)
    val realised = m.sold.sumOf { it.soldPrice ?: 0.0 }
    if (realised > 0) line("realised (sold)        $cur ${"%,.0f".format(realised)}".trim(), body, 13f)
    line("documents attached     ${m.docs.values.sumOf { it.size }}", body, 13f)
    y += 10f
    line("generated by Personal Terminal · keep with insurance papers", dim, 13f)

    doc.finishPage(page)
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val out = File(dir, "collection-report-${LocalDate.now().format(DateTimeFormatter.ISO_DATE)}.pdf")
    out.outputStream().use { doc.writeTo(it) }
    doc.close()
    return out
}
