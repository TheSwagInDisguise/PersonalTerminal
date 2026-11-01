package dev.personalterminal.ui.watch

import dev.personalterminal.domain.AppClock
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.db.Watch
import dev.personalterminal.data.db.displayName
import dev.personalterminal.ui.components.AsciiProgress
import dev.personalterminal.ui.components.Comment
import dev.personalterminal.ui.components.KeyValue
import dev.personalterminal.ui.components.PromptLine
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.components.TerminalPanel
import dev.personalterminal.ui.navigation.Routes
import dev.personalterminal.ui.theme.Term
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/** `watch stats` – wear share, neglected pieces, valuation, `watch next`, CSV export. */
@Composable
fun WatchStatsScreen(app: PersonalTerminalApp, nav: NavHostController) {
    val p = Term.palette
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val stats by remember { app.watches.observeCollectionStats() }.collectAsStateWithLifecycle(initialValue = null)
    var next by remember { mutableStateOf<Pair<Watch, String>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(stats) { next = app.watches.suggestNext() }
    val s = stats

    // CSV export via SAF or share sheet
    var pendingCsv by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val csv = pendingCsv ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            runCatching { ctx.contentResolver.openOutputStream(uri)!!.use { it.write(csv.toByteArray()) } }
                .onSuccess { status = "csv saved" }.onFailure { status = "save failed: ${it.message}" }
        }
        pendingCsv = null
    }
    fun export(name: String, build: suspend () -> String, share: Boolean) {
        scope.launch {
            val csv = build()
            if (share) {
                val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
                val f = File(dir, name).apply { writeText(csv) }
                val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                val i = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { ctx.startActivity(Intent.createChooser(i, "share $name")) }
            } else { pendingCsv = csv; saveLauncher.launch(name) }
        }
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PromptLine("watch stats")
        if (s == null) { Comment("loading…"); return@Column }

        TerminalPanel(title = "watch next", titleColor = p.green) {
            val n = next
            if (n == null) Comment("add a watch and log a few wears first") else {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("→ ${n.first.displayName}", color = p.named(n.first.color), style = MaterialTheme.typography.titleMedium)
                        Text(n.second, color = p.fgDim, style = MaterialTheme.typography.bodySmall)
                    }
                    TermButton("wear it", color = p.green, onClick = { scope.launch { app.watches.logWear(n.first.id, AppClock.today()); app.habits.mutations.value = System.currentTimeMillis(); status = "${n.first.displayName} logged for today" } })
                }
            }
            Comment("scores days-since-worn, under-worn pieces and avoids yesterday's watch")
        }

        TerminalPanel(title = "collection", titleColor = p.cyan) {
            KeyValue("watches", "${s.perWatch.size}")
            KeyValue("wear days logged", "${s.totalWearDays}" + if (s.daysCovered > 0) " of ${s.daysCovered} (${s.totalWearDays * 100 / s.daysCovered}%)" else "")
            if (s.totalPaid > 0) KeyValue("total paid", "${s.currency} ${"%,.0f".format(s.totalPaid)}".trim(), valueColor = p.yellow)
            if (s.totalValue > 0) KeyValue("est. value", "${s.currency} ${"%,.0f".format(s.totalValue)}".trim() + (if (s.totalPaid > 0) "  (${if (s.totalValue >= s.totalPaid) "+" else ""}${"%,.0f".format(s.totalValue - s.totalPaid)})" else ""), valueColor = if (s.totalValue >= s.totalPaid) p.green else p.red)
        }

        TerminalPanel(title = "wear share") {
            s.perWatch.forEach { ws ->
                Column(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.watchDetail(ws.watch.id)) }.padding(vertical = 3.dp)) {
                    Row {
                        Text(ws.watch.displayName, color = p.named(ws.watch.color), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${ws.wearDays}d", color = p.fg, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row {
                        AsciiProgress(fraction = ws.share, width = 16, color = p.named(ws.watch.color), showPercent = true)
                        Spacer(Modifier.weight(1f))
                        ws.costPerWear?.let { Text("${s.currency} ${"%.0f".format(it)}/wear".trim(), color = p.fgDim, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
        }

        TerminalPanel(title = "neglected", titleColor = p.red) {
            val neglected = s.neglected
            if (neglected.isEmpty()) Comment("everything got wrist time in the last 30 days") else neglected.forEach { ws ->
                Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.watchDetail(ws.watch.id)) }.padding(vertical = 2.dp)) {
                    Text(ws.watch.displayName, color = p.fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(ws.lastWorn?.let { "${ws.daysSinceWorn}d ago" } ?: "never worn", color = p.red, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        TerminalPanel(title = "export", titleColor = p.yellow) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermButton("wear log .csv", color = p.yellow, onClick = { export("wear-log.csv", { app.watches.wearLogCsv() }, share = false) }, modifier = Modifier.weight(1f))
                TermButton("collection .csv", color = p.yellow, onClick = { export("collection.csv", { app.watches.collectionCsv() }, share = false) }, modifier = Modifier.weight(1f))
                TermButton("insurance .pdf", color = p.yellow, onClick = { nav.navigate(Routes.COLLECTION_REPORT) }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermButton("share wear log", color = p.fgDim, onClick = { export("wear-log.csv", { app.watches.wearLogCsv() }, share = true) }, modifier = Modifier.weight(1f))
                TermButton("share collection", color = p.fgDim, onClick = { export("collection.csv", { app.watches.collectionCsv() }, share = true) }, modifier = Modifier.weight(1f))
            }
            status?.let { Comment(it, color = p.green) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TermButton("strap library", color = p.purple, onClick = { nav.navigate(Routes.STRAPS) }, modifier = Modifier.weight(1f))
            TermButton("back", color = p.fgDim, onClick = { nav.popBackStack() })
        }
        Spacer(Modifier.height(24.dp))
    }
}
