package dev.personalterminal.ui.watch

import dev.personalterminal.domain.AppClock
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.db.displayName
import dev.personalterminal.ui.components.Comment
import dev.personalterminal.ui.components.PromptLine
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.components.TermTextField
import dev.personalterminal.ui.components.TerminalPanel
import dev.personalterminal.ui.navigation.Routes
import dev.personalterminal.ui.theme.Term
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** "What's on the wrist today?" – pick a watch, optionally snap a wrist shot, add a note. */
@Composable
fun WearLogScreen(app: PersonalTerminalApp, nav: NavHostController, epochDay: Long?) {
    val p = Term.palette
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(epochDay?.let { LocalDate.ofEpochDay(it) } ?: AppClock.today()) }
    val watches by remember { app.watches.observeWatches() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val existing by remember(date) { app.watches.observeWearForDay(date) }.collectAsStateWithLifecycle(initialValue = emptyList())
    var selected by remember { mutableLongStateOf(0L) }
    var note by remember { mutableStateOf("") }
    val photo = rememberPhotoPickerState(initialPath = null)
    photo.tag = watches.firstOrNull { it.id == selected }?.displayName
    if (photoPickerCamera(app, photo)) return

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PromptLine("wear log", trailing = date.format(DateTimeFormatter.ofPattern("EEE dd MMM")))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("< prev day", color = p.cyan, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { date = date.minusDays(1) }.padding(4.dp))
            val isToday = date == AppClock.today()
            Text("next day >", color = if (isToday) p.fgDim else p.cyan, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable(enabled = !isToday) { date = date.plusDays(1) }.padding(4.dp))
        }

        if (existing.isNotEmpty()) TerminalPanel(title = "already logged", titleColor = p.cyan) {
            existing.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    if (e.log.photoPath != null) { AsyncImage(model = app.watches.photoFile(e.log.photoPath), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(40.dp).clip(RoundedCornerShape(4.dp))); Spacer(Modifier.width(8.dp)) }
                    Text("⌚ ${e.watch.displayName}", color = p.fg, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("[x]", color = p.red, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { scope.launch { app.watches.deleteWear(e.log) } }.padding(4.dp))
                }
            }
            Comment("you can log more than one watch per day (e.g. a swap)")
        }

        Text("which watch?", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
        if (watches.isEmpty()) {
            Comment("no watches yet")
            TermButton("watch add", color = p.cyan, onClick = { nav.navigate(Routes.watchEdit()) })
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            watches.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { w ->
                        val sel = selected == w.id
                        Column(
                            Modifier.weight(1f).aspectRatio(0.9f)
                                .background(if (sel) p.bgHighlight else p.bgAlt, RoundedCornerShape(6.dp))
                                .border(if (sel) 2.dp else 1.dp, if (sel) p.named(w.color) else p.border, RoundedCornerShape(6.dp))
                                .clickable { selected = w.id }.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            WatchThumb(app, w, 56.dp)
                            Spacer(Modifier.height(4.dp))
                            Text(w.displayName, color = if (sel) p.fg else p.fgDim, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Text("wrist shot (optional):", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
        PhotoPickerRow(app, photo, thumbSize = 84.dp)
        TermTextField(value = note, onValueChange = { note = it }, label = "note", placeholder = "strap, occasion, mood…", imeAction = ImeAction.Done)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TermButton("log it", filled = true, color = p.cyan, enabled = selected != 0L, modifier = Modifier.weight(1f), onClick = {
                scope.launch {
                    app.watches.logWear(selected, date, photo.photoPath, note.trim())
                    runCatching { dev.personalterminal.widget.VaultWidgetReceiver.refreshAll(app) }
                    nav.popBackStack()
                }
            })
            TermButton("cancel", color = p.fgDim, onClick = { nav.popBackStack() })
        }
        Spacer(Modifier.height(24.dp))
    }
}
