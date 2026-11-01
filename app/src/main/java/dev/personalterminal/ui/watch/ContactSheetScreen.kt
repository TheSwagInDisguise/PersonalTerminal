package dev.personalterminal.ui.watch

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as GrColor
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.db.displayName
import dev.personalterminal.data.db.owned
import dev.personalterminal.ui.components.Comment
import dev.personalterminal.ui.components.KeyValue
import dev.personalterminal.ui.components.PromptLine
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.components.TerminalPanel
import dev.personalterminal.ui.theme.Term
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * 3.6 / A6 — a contact-sheet PNG at A6 print size (1748 × 1240 @150dpi):
 * every gallery + profile photo in a bordered grid with caption, ready to
 * print, pin, or slip into the service dossier.
 */
private const val SHEET_W = 1748
private const val SHEET_H = 1240

@Composable
fun ContactSheetScreen(app: PersonalTerminalApp, nav: NavHostController) {
    val p = Term.palette
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val watches by remember(app) { app.watches.observeWatches() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val photos by remember(app) { app.watches.observeAllPhotos() }.collectAsStateWithLifecycle(initialValue = emptyList())
    var status by remember { mutableStateOf<String?>(null) }

    fun shareSheet() {
        val byId = watches.associateBy { it.id }
        scope.launch {
            runCatching {
                val f = buildContactSheet(ctx, photos, byId)
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                val i = Intent(Intent.ACTION_SEND)
                    .setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(Intent.createChooser(i, "share contact sheet"))
                status = "sheet ready: ${f.name}"
            }.onFailure { status = "sheet failed: ${it.message}" }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PromptLine("contact sheet", trailing = "a6 · png · 150 dpi")
        TerminalPanel(title = "sheet", titleColor = p.cyan) {
            val ownedPhotos = photos.filter { watches.firstOrNull { w -> w.id == it.watchId }?.owned != false }
            KeyValue("photos", "${ownedPhotos.size} in grid (active collection)")
            KeyValue("format", "1748 × 1240 px · A6 landscape at 150 dpi")
            Spacer(Modifier.height(6.dp))
            TermButton("generate & share png", filled = true, color = p.cyan, onClick = { shareSheet() })
            status?.let {
                Spacer(Modifier.height(4.dp))
                Text("# $it", color = p.green, style = MaterialTheme.typography.bodySmall)
            }
        }
        TerminalPanel(title = "layout") {
            Comment("title block · 4 × 3 bordered cells · caption: watch, kind, date")
            Comment("print at 100% on A6 photo paper — the dossier contact sheet")
        }
        TermButton("← back", color = p.fgDim, onClick = { nav.popBackStack() })
        Spacer(Modifier.height(8.dp))
    }
}

private fun buildContactSheet(
    context: Context,
    photos: List<dev.personalterminal.data.db.WatchPhoto>,
    byId: Map<Long, dev.personalterminal.data.db.Watch>,
): File {
    val ink = GrColor.rgb(26, 26, 26)
    val dim = GrColor.rgb(107, 107, 98)
    val rule = GrColor.rgb(201, 201, 190)
    val paper = GrColor.rgb(250, 250, 246)
    val lime = GrColor.rgb(89, 115, 33)

    val bmp = Bitmap.createBitmap(SHEET_W, SHEET_H, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(paper)

    val title = Paint().apply { color = ink; textSize = 44f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    val sub = Paint().apply { color = dim; textSize = 26f; typeface = Typeface.MONOSPACE }
    val cap = Paint().apply { color = ink; textSize = 24f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    val capDim = Paint().apply { color = dim; textSize = 22f; typeface = Typeface.MONOSPACE }
    val cell = Paint().apply { color = rule; style = Paint.Style.STROKE; strokeWidth = 2f }
    val accent = Paint().apply { color = lime; strokeWidth = 6f }

    c.drawText("PERSONAL TERMINAL · CONTACT SHEET", 60f, 84f, title)
    val date = java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
    c.drawText("$date · A6 · ${photos.size} shots", 60f, 130f, sub)
    c.drawLine(60f, 156f, (SHEET_W - 60).toFloat(), 156f, accent)

    val cols = 4
    val rows = 3
    val marginX = 60f
    val gutter = 24f
    val cellW = (SHEET_W - 2 * marginX - (cols - 1) * gutter) / cols
    val photoH = 258f
    val captionH = 56f
    val cellH = photoH + captionH
    val rowGutter = 22f
    val top = 190f

    val shown = photos.sortedByDescending { it.createdAt }.take(cols * rows)
    val photoDir = File(context.filesDir, "watch_photos")
    shown.forEachIndexed { i, ph ->
        val col = i % cols
        val row = i / cols
        val x = marginX + col * (cellW + gutter)
        val y = top + row * (cellH + rowGutter)

        // photo box (fit, centred, white letterbox)
        c.drawRect(x, y, x + cellW, y + photoH, Paint().apply { color = GrColor.WHITE })
        val f = File(photoDir, ph.path)
        if (f.exists()) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val src = BitmapFactory.decodeFile(f.absolutePath, opts)
            if (src != null) {
                val s = minOf(cellW / src.width, photoH / src.height)
                val dw = src.width * s
                val dh = src.height * s
                val dx = x + (cellW - dw) / 2f
                val dy = y + (photoH - dh) / 2f
                c.drawBitmap(src, dx, dy, null)
                src.recycle()
            }
        }
        c.drawRect(x, y, x + cellW, y + photoH, cell)

        // caption
        val w = byId[ph.watchId]
        val name = (w?.displayName ?: "watch").take(20)
        val kind = ph.kind.ifBlank { "photo" }
        val taken = Instant.ofEpochMilli(ph.createdAt).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd MMM"))
        c.drawText("$name", x + 6f, y + photoH + 26f, cap)
        c.drawText("$kind · $taken", x + 6f, y + photoH + 52f, capDim)
    }

    if (photos.size > shown.size) {
        c.drawText("+ ${photos.size - shown.size} more in the app", 60f, SHEET_H - 40f, sub)
    }
    c.drawText("personal-terminal", (SHEET_W - 340).toFloat(), SHEET_H - 40f, sub)

    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val out = File(dir, "contact-sheet-${java.time.LocalDate.now().format(DateTimeFormatter.ISO_DATE)}.png")
    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bmp.recycle()
    return out
}
