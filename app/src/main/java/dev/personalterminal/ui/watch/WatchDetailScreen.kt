package dev.personalterminal.ui.watch

import dev.personalterminal.domain.AppClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.db.Watch
import dev.personalterminal.data.db.WearLog
import dev.personalterminal.data.db.complicationSet
import dev.personalterminal.data.db.inRepair
import dev.personalterminal.data.db.owned
import dev.personalterminal.data.db.sold
import dev.personalterminal.domain.Uptime
import dev.personalterminal.data.db.displayName
import dev.personalterminal.ui.components.Comment
import dev.personalterminal.ui.components.ContributionHeatmap
import dev.personalterminal.ui.components.KeyValue
import dev.personalterminal.ui.components.PromptLine
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.components.TermTextField
import dev.personalterminal.ui.components.TerminalPanel
import dev.personalterminal.ui.navigation.Routes
import dev.personalterminal.ui.theme.Term
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun WatchDetailScreen(app: PersonalTerminalApp, nav: NavHostController, watchId: Long) {
    val p = Term.palette
    val scope = rememberCoroutineScope()
    val watch by remember(watchId) { app.watches.observeWatch(watchId) }.collectAsStateWithLifecycle(initialValue = null)
    val logs by remember(watchId) { app.watches.observeWearForWatch(watchId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val services by remember(watchId) { app.watches.observeServices(watchId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val readings by remember(watchId) { app.watches.observeAccuracy(watchId) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val straps by remember { app.watches.observeStraps() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val w = watch
    val today = AppClock.today()

    // ---- wrist-shot flow: pick a photo, then attach it to today (or a chosen past wear day) ----
    val shot = rememberPhotoPickerState(initialPath = null)
    shot.tag = w?.displayName
    var shotDay by remember { mutableStateOf(today) }
    var shotFor by remember { mutableStateOf<WearLog?>(null) } // non-null = replacing the photo of a specific log
    var status by remember { mutableStateOf<String?>(null) }
    var chooserTitle by remember { mutableStateOf<String?>(null) } // non-null = source dialog open
    if (photoPickerCamera(app, shot)) return
    val shotActions = rememberPhotoPickerActions(app, shot)
    chooserTitle?.let { PhotoSourceDialog(it, shotActions, onDismiss = { chooserTitle = null }) }
    // As soon as a photo has been picked/captured, persist it against the chosen day.
    LaunchedEffect(shot.photoPath, shot.version) {
        val path = shot.photoPath ?: return@LaunchedEffect
        val target = shotFor
        if (target != null) {
            app.watches.setWearPhoto(target, path)
            status = "photo updated for ${LocalDate.ofEpochDay(target.day).format(DateTimeFormatter.ofPattern("dd MMM"))}"
        } else {
            app.watches.addWristShot(watchId, shotDay, path)
            status = "wrist shot saved for ${shotDay.format(DateTimeFormatter.ofPattern("EEE dd MMM"))}"
        }
        shotFor = null
        shot.photoPath = null // picker is a one-shot input here; the log list is the source of truth
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (w == null) { PromptLine("watch show"); Comment("loading…"); return@Column }
        val color = p.named(w.color)
        PromptLine("watch show ${w.displayName}")

        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WatchThumb(app, w, 120.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(w.displayName, color = p.fg, style = MaterialTheme.typography.titleLarge)
                if (w.nickname.isNotBlank()) Text("${w.brand} ${w.model}", color = p.fgDim, style = MaterialTheme.typography.bodyMedium)
                if (w.reference.isNotBlank()) KeyValue("ref", w.reference)
                if (w.movement.isNotBlank()) KeyValue("movement", w.movement, valueColor = color)
                w.caseSizeMm?.let { KeyValue("case", "${it.toString().removeSuffix(".0")} mm") }
                if (w.waterResistanceM > 0) KeyValue("water", "${w.waterResistanceM} m", valueColor = if (w.waterResistanceM in 1..99) p.orange else color)
                KeyValue("wrist days", "${logs.size}", valueColor = color)
                logs.firstOrNull()?.let { KeyValue("last worn", LocalDate.ofEpochDay(it.log.day).format(DateTimeFormatter.ofPattern("dd MMM yyyy"))) }
                straps.firstOrNull { it.watchId == w.id }?.let { KeyValue("on strap", it.name, valueColor = p.cyan) }
                when (w.status) {
                    Watch.STATUS_REPAIR -> KeyValue("status", "in repair · since ${LocalDate.ofEpochDay(w.statusDay).format(DateTimeFormatter.ofPattern("dd MMM"))}", valueColor = p.orange)
                    Watch.STATUS_SOLD -> KeyValue("status", "sold " + LocalDate.ofEpochDay(w.statusDay).format(DateTimeFormatter.ofPattern("dd MMM yyyy")), valueColor = p.red)
                    Watch.STATUS_WISHLIST -> KeyValue("status", "☆ wishlist", valueColor = p.yellow)
                }
                // uptime: power reserve left / stopped, moon phase
                if (w.owned && (w.powerReserveHours > 0 || "moonphase" in w.complicationSet)) {
                    val up = Uptime.status(w, logs.firstOrNull()?.let { LocalDate.ofEpochDay(it.log.day) }, AppClock.now())
                    KeyValue("uptime", up.label, valueColor = when (up.state) { Uptime.State.STOPPED -> p.red; Uptime.State.LOW -> p.yellow; Uptime.State.RUNNING -> p.green; else -> p.fgDim })
                    up.moonAge?.let { KeyValue("moon", "${Uptime.moonGlyph(it)} ${Uptime.moonPhaseName(it)} · ${"%.1f".format(it)} d", valueColor = p.fgDim) }
                }
            }
        }
        val wornToday = logs.any { it.log.day == today.toEpochDay() }
        if (w.owned) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TermButton(if (wornToday) "[✓] worn today" else "wear today", filled = !wornToday, enabled = !wornToday && !w.inRepair, color = color, modifier = Modifier.weight(1f), onClick = {
                scope.launch { app.watches.logWear(w.id, today); status = "logged for today" }
            })
            TermButton("edit", color = p.fgDim, onClick = { nav.navigate(Routes.watchEdit(w.id)) })
        }
        // wind-and-set checklist before the next wear (stopped / low reserve, skipped short months)
        if (w.owned) {
            val up = Uptime.status(w, logs.firstOrNull()?.let { LocalDate.ofEpochDay(it.log.day) }, AppClock.now())
            if (up.checklist.isNotEmpty()) TerminalPanel(title = "before wearing", titleColor = if (up.state == Uptime.State.STOPPED) p.red else p.yellow) {
                up.checklist.forEach { step -> Text("[ ] $step", color = p.fg, style = MaterialTheme.typography.bodySmall) }
                Comment("power reserve ${w.powerReserveHours} h" + (w.complications.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""))
            }
        }
        // lifecycle: repair ↔ owned, sold, wishlist → owned
        LifecyclePanel(app, w, status = { status = it })

        // ---- add a wrist shot any time, not only while logging the wear ----
        TerminalPanel(title = "add wrist shot", titleColor = color) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("< prev", color = p.cyan, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { shotDay = shotDay.minusDays(1) }.padding(4.dp))
                Text(
                    if (shotDay == today) "today" else shotDay.format(DateTimeFormatter.ofPattern("EEE dd MMM")),
                    color = p.fg, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable { shotDay = today }.padding(4.dp),
                )
                Text("next >", color = if (shotDay == today) p.fgDim else p.cyan, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(enabled = shotDay != today) { shotDay = shotDay.plusDays(1) }.padding(4.dp))
            }
            Comment(
                if (logs.any { it.log.day == shotDay.toEpochDay() }) "attaches to the existing wear log for that day"
                else "no wear logged that day yet — logging one with the photo",
            )
            Spacer(Modifier.height(6.dp))
            PhotoPickerRow(app, shot, thumbSize = 64.dp, showRemove = false, actions = shotActions)
            status?.let { Spacer(Modifier.height(4.dp)); Text("# $it", color = p.green, style = MaterialTheme.typography.bodySmall) }
        }

        TerminalPanel(title = "wrist time") {
            val values = remember(logs) { logs.associate { it.log.day to 1f } }
            Row(Modifier.horizontalScroll(rememberScrollState(), reverseScrolling = true)) {
                ContributionHeatmap(values = values, weeks = 26, color = color)
            }
            // per-weekday distribution
            val byDow = IntArray(7)
            logs.forEach { byDow[LocalDate.ofEpochDay(it.log.day).dayOfWeek.value - 1]++ }
            val max = (byDow.maxOrNull() ?: 1).coerceAtLeast(1)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("mo", "tu", "we", "th", "fr", "sa", "su").forEachIndexed { i, d ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("▁▂▃▄▅▆▇█"[(byDow[i].toFloat() / max * 7).toInt().coerceIn(0, 7)].toString(), color = color, style = MaterialTheme.typography.titleMedium)
                        Text(d, color = p.fgDim, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (w.notes.isNotBlank()) TerminalPanel(title = "notes") { Text(w.notes, color = p.fg, style = MaterialTheme.typography.bodyMedium) }

        // ---- purchase / valuation
        if (w.purchasePrice != null || w.currentValue != null || w.purchaseDay != null) {
            TerminalPanel(title = "valuation", titleColor = p.yellow) {
                val cur = w.currency.ifBlank { "" }
                w.purchaseDay?.let { KeyValue("bought", LocalDate.ofEpochDay(it).format(DateTimeFormatter.ofPattern("dd MMM yyyy"))) }
                w.purchasePrice?.let { KeyValue("paid", "$cur ${"%,.0f".format(it)}".trim()) }
                w.currentValue?.let { v ->
                    val delta = w.purchasePrice?.let { v - it }
                    KeyValue("current value", "$cur ${"%,.0f".format(v)}".trim() + (delta?.let { d -> "  (${if (d >= 0) "+" else ""}${"%,.0f".format(d)})" } ?: ""), valueColor = if ((delta ?: 0.0) >= 0) p.green else p.red)
                }
                w.purchasePrice?.let { paid -> if (logs.isNotEmpty()) KeyValue("cost per wear", "$cur ${"%,.2f".format(paid / logs.size)}".trim(), valueColor = p.cyan) }
                if (w.sold) {
                    w.soldPrice?.let { sp ->
                        val gain = w.purchasePrice?.let { sp - it }
                        KeyValue("sold for", "$cur ${"%,.0f".format(sp)}".trim() + (gain?.let { g -> "  (${if (g >= 0) "+" else ""}${"%,.0f".format(g)} realised)" } ?: ""), valueColor = if ((gain ?: 0.0) >= 0) p.green else p.red)
                    }
                    w.purchaseDay?.let { d -> KeyValue("owned for", "${java.time.temporal.ChronoUnit.DAYS.between(LocalDate.ofEpochDay(d), LocalDate.ofEpochDay(w.statusDay))} days") }
                } else w.purchaseDay?.let { d -> KeyValue("owned for", "${java.time.temporal.ChronoUnit.DAYS.between(LocalDate.ofEpochDay(d), today)} days") }
            }
        }

        // ---- service / maintenance log
        TerminalPanel(title = "service log", titleColor = p.orange) {
            var nextDue by remember(services, w) { mutableStateOf<LocalDate?>(null) }
            LaunchedEffect(services, w) { nextDue = app.watches.nextServiceDue(w) }
            nextDue?.let { due ->
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, due)
                KeyValue("next service", due.format(DateTimeFormatter.ofPattern("MMM yyyy")) + if (days < 0) "  (overdue ${-days}d)" else "  (in ${days}d)", valueColor = if (days < 30) p.red else p.fg)
            } ?: Comment(if (w.serviceIntervalMonths > 0) "set a purchase date or log a service to get a due date" else "set a service interval in edit to get reminders")
            services.take(8).forEach { sv ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(LocalDate.ofEpochDay(sv.day).format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), color = p.fgDim, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    Text(sv.kind + (sv.cost?.let { "  ${w.currency} ${"%,.0f".format(it)}".trimEnd() } ?: "") + (if (sv.notes.isNotBlank()) " · ${sv.notes}" else ""), color = p.fg, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(" [x]", color = p.red, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { scope.launch { app.watches.deleteService(sv) } }.padding(4.dp))
                }
            }
            var kind by remember { mutableStateOf("service") }
            var cost by remember { mutableStateOf("") }
            var note by remember { mutableStateOf("") }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("service", "battery", "regulation", "strap", "polish", "other").forEach { k ->
                    Text(if (kind == k) "[$k]" else " $k ", color = if (kind == k) p.orange else p.fgDim, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { kind = k }.padding(2.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TermTextField(value = cost, onValueChange = { cost = it.filter { c -> c.isDigit() || c == '.' } }, placeholder = "cost", keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal, modifier = Modifier.weight(0.4f), prompt = "")
                TermTextField(value = note, onValueChange = { note = it }, placeholder = "note", modifier = Modifier.weight(0.6f), prompt = "", imeAction = androidx.compose.ui.text.input.ImeAction.Done)
            }
            Spacer(Modifier.height(4.dp))
            TermButton("log $kind today", color = p.orange, onClick = {
                scope.launch {
                    val next = if (kind == "service" && w.serviceIntervalMonths > 0) today.plusMonths(w.serviceIntervalMonths.toLong()).toEpochDay() else null
                    app.watches.saveService(dev.personalterminal.data.db.WatchService(watchId = w.id, day = today.toEpochDay(), kind = kind, cost = cost.toDoubleOrNull(), notes = note.trim(), nextDueDay = next))
                    cost = ""; note = ""
                    dev.personalterminal.reminders.WatchServiceReminder.check(app)
                }
            })
        }

        // ---- accuracy / drift
        TerminalPanel(title = "accuracy", titleColor = p.cyan) {
            val drift = remember(readings) { app.watches.drift(readings) }
            if (drift.secondsPerDay != null) {
                KeyValue("drift", "${if (drift.secondsPerDay >= 0) "+" else ""}%.1f s/day".format(drift.secondsPerDay), valueColor = if (kotlin.math.abs(drift.secondsPerDay) <= 6f) p.green else if (kotlin.math.abs(drift.secondsPerDay) <= 15f) p.yellow else p.red)
                KeyValue("over", "%.1f days · ${drift.readings} readings".format(drift.spanDays))
                drift.latestOffset?.let { KeyValue("current offset", "${if (it >= 0) "+" else ""}%.0f s".format(it)) }
                Comment(if (kotlin.math.abs(drift.secondsPerDay) <= 6f) "within COSC spec (−4/+6)" else if (drift.secondsPerDay > 0) "running fast" else "running slow")
            } else Comment("log the offset vs. an atomic clock (time.is) twice, a day apart, to see the daily rate")
            if (readings.isNotEmpty()) {
                Row { Text("offsets ", color = p.fgDim, style = MaterialTheme.typography.bodySmall); Text(dev.personalterminal.ui.components.sparkline(readings.takeLast(20).map { it.offsetSeconds }), color = p.cyan, style = MaterialTheme.typography.bodySmall) }
            }
            var offset by remember { mutableStateOf("") }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TermTextField(value = offset, onValueChange = { offset = it.filter { c -> c.isDigit() || c == '.' || c == '-' || c == '+' } }, placeholder = "+12 (seconds fast)", keyboardType = androidx.compose.ui.text.input.KeyboardType.Text, modifier = Modifier.weight(1f), prompt = "Δ ")
                TermButton("log", color = p.cyan, enabled = offset.replace("+", "").toFloatOrNull() != null, onClick = {
                    scope.launch { app.watches.addReading(dev.personalterminal.data.db.AccuracyReading(watchId = w.id, measuredAt = System.currentTimeMillis(), offsetSeconds = offset.replace("+", "").toFloat())); offset = "" }
                })
                if (readings.isNotEmpty()) TermButton("reset", color = p.red, onClick = { scope.launch { app.watches.resetAccuracy(w.id) } })
            }
            Comment("reset after you set the watch, then keep logging daily")
        }

        // ---- straps
        TerminalPanel(title = "strap", titleColor = p.purple) {
            val fitted = straps.firstOrNull { it.watchId == w.id }
            val candidates = straps.filter { it.widthMm == null || w.lugWidthMm == null || it.widthMm == w.lugWidthMm }
            if (straps.isEmpty()) Comment("no straps in the library yet") else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (fitted == null) "[none]" else " none ", color = if (fitted == null) p.purple else p.fgDim, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clickable { fitted?.let { f -> scope.launch { app.watches.fitStrap(f, null) } } }.padding(2.dp))
                    candidates.forEach { st ->
                        val sel = st.id == fitted?.id
                        Text(if (sel) "[${st.name}]" else " ${st.name} ", color = if (sel) p.purple else if (st.watchId != null) p.fgDim.copy(alpha = 0.6f) else p.fgDim, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clickable { scope.launch { app.watches.fitStrap(st, w.id) } }.padding(2.dp))
                    }
                }
                Comment("tap to fit · greyed straps are on another watch" + (w.lugWidthMm?.let { " · showing ${it}mm" } ?: ""))
            }
            Text("strap library →", color = p.cyan, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { nav.navigate(Routes.STRAPS) }.padding(top = 4.dp))
        }

        // ---- gallery & documents (0.4.0) ----
        val galleryPhotos by remember(app, w.id) { app.watches.observeWatchPhotos(w.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
        val watchDocs by remember(app, w.id) { app.watches.observeWatchDocuments(w.id) }.collectAsStateWithLifecycle(initialValue = emptyList())
        var photoKind by remember { mutableStateOf("dial") }
        var docKind by remember { mutableStateOf("receipt") }
        val photoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri?.let { u -> scope.launch { status = if (app.watches.addWatchPhoto(w.id, u, photoKind)) "photo added" else "photo import failed" } }
        }
        val docLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            uri?.let { u ->
                scope.launch {
                    val label = u.lastPathSegment?.substringAfterLast('/') ?: ""
                    status = if (app.watches.addWatchDocument(w.id, u, docKind, label)) "document attached" else "document import failed"
                }
            }
        }
        TerminalPanel(title = "gallery (${galleryPhotos.size})", titleColor = p.cyan) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("dial", "caseback", "papers").forEach { k ->
                    Text(
                        (if (photoKind == k) "[$k]" else " $k "),
                        color = if (photoKind == k) p.green else p.fgDim,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { photoKind = k }.padding(horizontal = 3.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                TermButton("+ photo", color = p.cyan, onClick = { photoLauncher.launch("image/*") })
            }
            if (galleryPhotos.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Comment("dial / caseback / papers — extra shots beyond the profile photo")
            } else {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    galleryPhotos.forEach { ph ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            PhotoImage(
                                app.watches.photoFile(ph.path), version = ph.path.hashCode(),
                                modifier = Modifier.size(88.dp).clip(RoundedCornerShape(6.dp)),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(ph.kind.ifBlank { "photo" }, color = p.fgDim, style = MaterialTheme.typography.labelSmall)
                                Text(" [x]", color = p.red, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.clickable { scope.launch { app.watches.deleteWatchPhoto(ph) } }.padding(2.dp))
                            }
                        }
                    }
                }
            }
        }
        TerminalPanel(title = "documents (${watchDocs.size})", titleColor = p.yellow) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                listOf("receipt", "warranty", "other").forEach { k ->
                    Text(
                        (if (docKind == k) "[$k]" else " $k "),
                        color = if (docKind == k) p.green else p.fgDim,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { docKind = k }.padding(horizontal = 3.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                TermButton("+ attach", color = p.yellow, onClick = { docLauncher.launch("*/*") })
            }
            if (watchDocs.isEmpty()) {
                Spacer(Modifier.height(4.dp))
                Comment("receipts & warranties — the insurance report lists them")
            } else {
                watchDocs.forEach { d ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(d.kind, color = p.yellow, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(8.dp))
                        Text(d.label, color = p.fg, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text(" [x]", color = p.red, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clickable { scope.launch { app.watches.deleteWatchDocument(d) } }.padding(2.dp))
                    }
                }
            }
        }
        TermButton("collection report →", color = p.yellow, onClick = { nav.navigate(dev.personalterminal.ui.navigation.Routes.COLLECTION_REPORT) })

        TerminalPanel(title = "wrist shots (${logs.count { it.log.photoPath != null }})") {
            val shots = logs.filter { it.log.photoPath != null }
            if (shots.isEmpty()) Comment("no photos yet · use \"add wrist shot\" above")
            else Comment("tap a photo to replace it · [x] removes just the photo")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shots.forEach { s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        PhotoImage(
                            app.watches.photoFile(s.log.photoPath!!), version = s.log.photoPath.hashCode(),
                            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(6.dp)).clickable {
                                shotFor = s.log; chooserTitle = "replace shot ${LocalDate.ofEpochDay(s.log.day).format(DateTimeFormatter.ofPattern("dd MMM"))}"
                            },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(LocalDate.ofEpochDay(s.log.day).format(DateTimeFormatter.ofPattern("dd MMM")), color = p.fgDim, style = MaterialTheme.typography.labelSmall)
                            Text(" [x]", color = p.red, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.clickable { scope.launch { app.watches.setWearPhoto(s.log, null); status = "photo removed" } }.padding(2.dp))
                        }
                    }
                }
            }
        }

        TerminalPanel(title = "log") {
            if (logs.isEmpty()) Comment("never worn (yet)")
            logs.take(30).forEach { l ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(LocalDate.ofEpochDay(l.log.day).format(DateTimeFormatter.ofPattern("yyyy-MM-dd EEE")).lowercase(), color = p.fg, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    Text(l.log.note, color = p.fgDim, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (l.log.photoPath != null) Text("📷", style = MaterialTheme.typography.labelSmall)
                    else Text("+📷", color = p.cyan, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clickable { shotFor = l.log; chooserTitle = "add shot ${LocalDate.ofEpochDay(l.log.day).format(DateTimeFormatter.ofPattern("dd MMM"))}" }.padding(4.dp))
                    Text(" [x]", color = p.red, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { scope.launch { app.watches.deleteWear(l.log) } }.padding(4.dp))
                }
            }
            if (logs.size > 30) Comment("… ${logs.size - 30} more")
        }
        TermButton("back", color = p.fgDim, onClick = { nav.popBackStack() })
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Lifecycle controls: `send for service` / `back from service`, `mark sold` with a price, and for
 * wishlist entries `save` + `bought it`. Sold and wishlist pieces keep their history; only the
 * status changes, so every action is reversible from here.
 */
@Composable
private fun LifecyclePanel(app: PersonalTerminalApp, w: Watch, status: (String) -> Unit) {
    val p = Term.palette
    val scope = rememberCoroutineScope()
    var sellPrice by remember(w.id) { mutableStateOf("") }
    var selling by remember(w.id) { mutableStateOf(false) }
    var repairCost by remember(w.id) { mutableStateOf("") }
    var saveAmount by remember(w.id) { mutableStateOf("") }
    val cur = w.currency
    fun touch() { app.habits.mutations.value = System.currentTimeMillis() }
    when (w.status) {
        Watch.STATUS_WISHLIST -> TerminalPanel(title = "wishlist", titleColor = p.yellow) {
            val t = w.targetPrice
            if (t != null && t > 0) {
                val pct = (w.savedSoFar / t).toFloat().coerceIn(0f, 1f)
                dev.personalterminal.ui.components.AsciiProgress(fraction = pct, width = 16, color = p.yellow, showPercent = true, label = "$cur ${"%,.0f".format(w.savedSoFar)} / ${"%,.0f".format(t)}".trim())
                if (w.savedSoFar >= t) Comment("fully funded – go get it", color = p.green)
            } else Comment("no target price yet · edit to set one")
            if (w.link.isNotBlank()) KeyValue("link", w.link.removePrefix("https://").removePrefix("http://").take(40), valueColor = p.cyan)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TermTextField(value = saveAmount, onValueChange = { saveAmount = it.filter { c -> c.isDigit() || c == '.' || c == '-' } }, placeholder = "amount", keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal, modifier = Modifier.weight(0.5f), prompt = "")
                TermButton("save", color = p.yellow, enabled = saveAmount.toDoubleOrNull() != null, onClick = {
                    scope.launch { app.watches.addSavings(w, saveAmount.toDouble()); saveAmount = ""; touch(); status("fund updated") }
                })
                TermButton("bought it", filled = true, color = p.green, onClick = {
                    scope.launch { app.watches.acquire(w); touch(); status("${w.displayName} joined the collection") }
                })
            }
            Comment("`save 200 ${w.displayName}` and `watch buy ${w.displayName}` work from the prompt too")
        }
        Watch.STATUS_SOLD -> TerminalPanel(title = "sold", titleColor = p.red) {
            Comment("history, photos and service log are kept; the watch no longer counts in stats or rotation")
            TermButton("undo – back in the collection", color = p.fgDim, onClick = { scope.launch { app.watches.markOwned(w); touch(); status("${w.displayName} → owned") } })
        }
        Watch.STATUS_REPAIR -> TerminalPanel(title = "in repair", titleColor = p.orange) {
            Comment("away since ${LocalDate.ofEpochDay(w.statusDay).format(DateTimeFormatter.ofPattern("dd MMM yyyy"))} · ${java.time.temporal.ChronoUnit.DAYS.between(LocalDate.ofEpochDay(w.statusDay), AppClock.today())} days · left out of `watch next` and the challenges")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TermTextField(value = repairCost, onValueChange = { repairCost = it.filter { c -> c.isDigit() || c == '.' } }, placeholder = "invoice", keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal, modifier = Modifier.weight(0.5f), prompt = "")
                TermButton("back from service", filled = true, color = p.orange, onClick = {
                    scope.launch { app.watches.backFromRepair(w, cost = repairCost.toDoubleOrNull()); repairCost = ""; touch(); status("${w.displayName} is back") }
                })
            }
        }
        else -> TerminalPanel(title = "lifecycle", titleColor = p.fgDim) {
            if (!selling) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermButton("send for service", color = p.orange, modifier = Modifier.weight(1f), onClick = {
                    scope.launch { app.watches.sendForRepair(w); touch(); status("${w.displayName} → in repair") }
                })
                TermButton("mark sold", color = p.red, modifier = Modifier.weight(1f), onClick = { selling = true })
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TermTextField(value = sellPrice, onValueChange = { sellPrice = it.filter { c -> c.isDigit() || c == '.' } }, placeholder = "sale price" + (cur.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""), keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal, modifier = Modifier.weight(0.6f), prompt = "")
                    TermButton("confirm sold", filled = true, color = p.red, onClick = {
                        scope.launch { app.watches.markSold(w, sellPrice.toDoubleOrNull()); selling = false; touch(); status("${w.displayName} marked sold") }
                    })
                    TermButton("x", color = p.fgDim, onClick = { selling = false })
                }
                w.purchasePrice?.let { paid -> sellPrice.toDoubleOrNull()?.let { sp -> Comment("realised ${if (sp - paid >= 0) "+" else ""}${"%,.0f".format(sp - paid)} against $cur ${"%,.0f".format(paid)} paid".trim(), color = if (sp >= paid) p.green else p.red) } }
            }
            Comment("`watch repair ${w.displayName}` · `watch sold ${w.displayName} 1500`")
        }
    }
}
