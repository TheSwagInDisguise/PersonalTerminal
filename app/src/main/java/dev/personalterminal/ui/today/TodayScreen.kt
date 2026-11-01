package dev.personalterminal.ui.today

import dev.personalterminal.domain.AppClock
import dev.personalterminal.domain.Streaks
import dev.personalterminal.domain.Sleep
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.db.HabitType
import dev.personalterminal.data.db.isPausedOn
import dev.personalterminal.data.db.ScheduleType
import dev.personalterminal.domain.DaySummary
import dev.personalterminal.domain.HabitStatus
import dev.personalterminal.domain.Progression
import dev.personalterminal.domain.Schedule
import dev.personalterminal.domain.Targets
import dev.personalterminal.domain.Stacks
import dev.personalterminal.ui.components.AsciiProgress
import dev.personalterminal.ui.components.BracketCheckbox
import dev.personalterminal.ui.components.Comment
import dev.personalterminal.ui.components.PromptLine
import dev.personalterminal.ui.components.Tag
import dev.personalterminal.ui.components.TerminalPanel
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.navigation.Routes
import dev.personalterminal.ui.theme.Term
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import dev.personalterminal.automation.Commands
import dev.personalterminal.data.db.TimeOfDay
import dev.personalterminal.data.db.checklistItems
import dev.personalterminal.data.db.hasItem
import dev.personalterminal.data.db.section
import dev.personalterminal.ui.components.TermTextField
import java.time.format.DateTimeFormatter

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun TodayScreen(app: PersonalTerminalApp, nav: NavHostController) {
    val p = Term.palette
    val scope = rememberCoroutineScope()
    var date by remember { mutableStateOf(AppClock.today()) }
    val summary by remember { snapshotFlow { date }.flatMapLatest { app.habits.observeDay(it) } }
        .collectAsStateWithLifecycle(initialValue = null)
    val wearToday by remember(date) { app.watches.observeWearForDay(date) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val sleepLog by remember(date) { app.habits.observeSleep(date) }.collectAsStateWithLifecycle(initialValue = null)
    val settings by app.prefs.settings.collectAsStateWithLifecycle(initialValue = dev.personalterminal.data.prefs.Settings())

    val isToday = date == AppClock.today()
    val s = summary
    val ctx = LocalContext.current
    var command by remember { mutableStateOf("") }
    var output by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    fun runCommand() {
        val line = command
        if (line.isBlank()) return
        scope.launch {
            val r = Commands.run(ctx, app, line, date)
            output = r.ok to (if (r.message.isBlank()) "" else "$ $line\n${r.message}")
            command = ""
            r.navigate?.let { route -> runCatching { nav.navigate(route) } }
        }
    }

    // ---- keyboard TUI (0.3.8): j / k move the selection, space / enter ticks the row ----
    var selHabitId by remember { mutableStateOf<Long?>(null) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    var fieldFocused by remember { mutableStateOf(false) }
    var refocusRoot by remember { mutableStateOf(false) }

    data class Block(val key: String, val icon: String, val name: String, val color: androidx.compose.ui.graphics.Color, val habits: List<HabitStatus>, val current: Boolean = false)
    val nowSection = TimeOfDay.at(AppClock.now().let { it.hour * 60 + it.minute })
    val blocks: List<Block> = if (s == null) emptyList() else if (settings.todaySections) {
        val bySection = s.all.groupBy { it.habit.section }
        TimeOfDay.entries.mapNotNull { t -> bySection[t]?.let { Block("tod-${t.name}", t.glyph, t.label, if (t == nowSection && isToday) p.yellow else p.purple, it, current = t == nowSection && isToday) } }
    } else s.groups.map { g -> Block("routine-${g.routine?.id ?: -1}", g.routine?.icon ?: ">", g.name, p.purple, g.habits) }
    val byId = s?.all?.associate { it.habit.id to it.habit } ?: emptyMap()

    fun dueOf(group: Block): List<HabitStatus> {
        val dueRaw = group.habits.filter { it.isDueToday }
        val base = if (settings.todaySort == "strength") (s?.weakestFirst(dueRaw) ?: dueRaw) else dueRaw
        return base.sortedBy { Stacks.depth(it.habit, byId) }
    }

    // Flat ordered view of the rows the list renders — drives selection and scrolling.
    val flatDue: List<HabitStatus> = blocks.flatMap { dueOf(it) }
    val indexByHabit: Map<Long, Int> = buildMap {
        var idx = 4 // prompt · day nav · command line · summary
        blocks.forEach { group ->
            val due = dueOf(group)
            val offDay = group.habits.filter { !it.isDueToday }
            if (due.isEmpty() && offDay.isEmpty()) return@forEach
            idx++ // group header
            due.forEach { put(it.habit.id, idx++) }
            if (offDay.isNotEmpty()) idx++
        }
    }
    fun moveSelection(delta: Int) {
        if (flatDue.isEmpty()) { selHabitId = null; return }
        val cur = selHabitId?.let { id -> flatDue.indexOfFirst { it.habit.id == id } } ?: -1
        val next = (if (cur < 0) (if (delta > 0) -1 else 0) else cur + delta).coerceIn(0, flatDue.lastIndex)
        selHabitId = flatDue[next].habit.id
        indexByHabit[flatDue[next].habit.id]?.let { idx -> scope.launch { listState.animateScrollToItem(idx) } }
    }
    fun toggleSelection() {
        val id = selHabitId ?: return
        if (flatDue.none { it.habit.id == id }) return
        scope.launch { app.habits.toggle(id, date) }
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    LaunchedEffect(refocusRoot) {
        if (refocusRoot) { refocusRoot = false; focusRequester.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { ev ->
                when {
                    ev.type != KeyEventType.KeyDown || fieldFocused -> false
                    ev.key == Key.J || ev.key == Key.DirectionDown -> { moveSelection(1); true }
                    ev.key == Key.K || ev.key == Key.DirectionUp -> { moveSelection(-1); true }
                    (ev.key == Key.Spacebar || ev.key == Key.Enter) && selHabitId != null -> { toggleSelection(); true }
                    else -> false
                }
            },
    ) {
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PromptLine(command = if (isToday) "today" else "day ${date.format(DateTimeFormatter.ISO_DATE)}", trailing = date.format(DateTimeFormatter.ofPattern("EEE dd MMM")))
        }
        // Day navigation  < yesterday | today | tomorrow >
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("< prev", color = p.cyan, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { date = date.minusDays(1) }.padding(4.dp))
                if (!isToday) Text("[ today ]", color = p.yellow, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { date = AppClock.today() }.padding(4.dp))
                else Text(settings.prompt, color = p.fgDim, style = MaterialTheme.typography.labelSmall)
                Text("next >", color = if (isToday) p.fgDim else p.cyan, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.alpha(if (isToday) 0.4f else 1f).clickable(enabled = !isToday) { date = date.plusDays(1) }.padding(4.dp))
            }
        }
        item(key = "cmdline") {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$ ", color = p.green, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    TermTextField(
                        value = command, onValueChange = { command = it }, placeholder = "done stretch · add 2 water · timer 25 · wear speedy · help",
                        singleLine = true, imeAction = ImeAction.Go, onImeAction = { runCommand() },
                        modifier = Modifier.weight(1f).onFocusChanged {
                            fieldFocused = it.isFocused
                            if (!it.isFocused) refocusRoot = true
                        },
                    )
                    if (command.isNotBlank()) Text(" ↵", color = p.cyan, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable { runCommand() }.padding(4.dp))
                }
                val out = output
                if (out != null && out.second.isNotBlank()) {
                    Text(out.second, color = if (out.first) p.fgDim else p.red, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, top = 2.dp).clickable { output = null })
                }
            }
        }
        if (s == null) {
            item { Comment("loading…") }
            return@LazyColumn
        }
        item { SummaryPanel(s, onShieldInfo = { nav.navigate(Routes.PROFILE) }) }

        // Active challenges (0.4.0): one card, progress bars, tap → detail.
        val activeChallenges = s.all.filter { dev.personalterminal.domain.Challenges.isActive(it.habit, date) }
        if (activeChallenges.isNotEmpty()) {
            item(key = "challenges") {
                TerminalPanel(title = "challenges", titleColor = p.yellow) {
                    activeChallenges.forEach { hs ->
                        val c = dev.personalterminal.domain.Challenges
                        Text(
                            (if (hs.completed) "[✓] " else "[ ] ") +
                                hs.habit.name + "  " + (c.label(hs.habit, date) ?: "") + "  " + c.progressBar(hs.habit, date) +
                                if (hs.habit.chStake > 0) "  ⛨${hs.habit.chStake}" else "",
                            color = if (hs.completed) p.green else p.fg,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { nav.navigate(Routes.habitDetail(hs.habit.id)) }
                                .padding(vertical = 3.dp),
                        )
                    }
                    Comment("window closes → certificate at the prompt · `challenge <habit>` for status")
                }
            }
        }

        if (s.all.isEmpty()) {
            item {
                TerminalPanel(title = "empty") {
                    Text("no habits yet.", color = p.fg)
                    Comment("run `habit add` to create your first one")
                    Spacer(Modifier.height(8.dp))
                    TermButton("habit add", onClick = { nav.navigate(Routes.habitEdit()) })
                }
            }
        }

        // Blocks / due order / index map were computed above the list (keyboard TUI needs them).
        blocks.forEach { group ->
            val dueHabits = dueOf(group)
            val offDay = group.habits.filter { !it.isDueToday }
            if (dueHabits.isEmpty() && offDay.isEmpty()) return@forEach
            item(key = group.key) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("── ${group.icon} ${group.name} ", color = group.color, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text("${dueHabits.count { it.completed }}/${dueHabits.size}", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
                    if (group.current) { Spacer(Modifier.width(6.dp)); Text("← now", color = p.yellow, style = MaterialTheme.typography.labelSmall) }
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.weight(1f).height(1.dp).background(p.border))
                }
            }
            items(dueHabits, key = { "h-${it.habit.id}" }) { hs ->
                HabitRow(
                    hs = hs,
                    selected = hs.habit.id == selHabitId,
                    onToggle = { scope.launch { app.habits.toggle(hs.habit.id, date) } },
                    onToggleItem = { i -> scope.launch { app.habits.toggleItem(hs.habit.id, i, date) } },
                    onIncrement = { scope.launch { app.habits.addValue(hs.habit.id, 1, date) } },
                    onDecrement = { scope.launch { app.habits.addValue(hs.habit.id, -1, date) } },
                    onTimer = { nav.navigate(Routes.timer(hs.habit.id)) },
                    onOpen = { nav.navigate(Routes.habitDetail(hs.habit.id)) },
                    onShield = { day -> scope.launch { app.habits.useShield(hs.habit.id, day) } },
                    shieldsAvailable = s.shieldsAvailable,
                    expanded = expanded == hs.habit.id,
                    onExpand = { expanded = if (expanded == hs.habit.id) null else hs.habit.id },
                    onSkip = { reason -> scope.launch { app.habits.skip(hs.habit.id, reason, date); expanded = null } },
                    onUnskip = { scope.launch { app.habits.unskip(hs.habit.id, date) } },
                    onAnnotate = { note, mood -> scope.launch { app.habits.annotate(hs.habit.id, date, note = note, mood = mood) } },
                    onLateLog = { day -> scope.launch { app.habits.logLate(hs.habit.id, day) } },
                    isToday = isToday,
                    waitingOn = Stacks.waitingOn(hs, s.all),
                    chain = Stacks.chainLabel(hs, s.all),
                    onMinimum = { scope.launch { app.habits.logMinimum(hs.habit.id, date) } },
                )
            }
            if (offDay.isNotEmpty()) {
                item(key = "off-${group.key}") {
                    val (paused, unscheduled) = offDay.partition { it.habit.isPausedOn(date) }
                    Column {
                        if (unscheduled.isNotEmpty()) Text(
                            "  # not scheduled today: " + unscheduled.joinToString(", ") { it.habit.name },
                            color = p.fgDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (paused.isNotEmpty()) Text(
                            "  # paused: " + paused.joinToString(", ") { "${it.habit.name} → ${LocalDate.ofEpochDay(it.habit.pausedUntil).format(DateTimeFormatter.ofPattern("dd MMM")).lowercase()}" },
                            color = p.fgDim, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        // Sleep anchors – one quiet line; expands into the log-a-time row when tapped
        item(key = "sleep") {
            var open by remember { mutableStateOf(false) }
            val text = Sleep.summary(sleepLog)
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 4.dp, vertical = 2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (text != null) "☾ " else "☾ ", color = p.purple, style = MaterialTheme.typography.bodySmall)
                    Text(text ?: "# sleep not logged · `sleep 23:30` tonight, `wake 06:45` in the morning", color = if (text != null) p.fg else p.fgDim,
                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (sleepLog?.source == dev.personalterminal.data.db.SleepLog.SOURCE_HEALTH) Tag("health", p.purple)
                }
                if (open) {
                    var bed by remember(sleepLog) { mutableStateOf(sleepLog?.bedMinutes?.let { Sleep.formatClock(it) } ?: "") }
                    var wake by remember(sleepLog) { mutableStateOf(sleepLog?.wakeMinutes?.let { Sleep.formatClock(it) } ?: "") }
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TermTextField(value = bed, onValueChange = { bed = it.take(7) }, placeholder = "23:30", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, modifier = Modifier.weight(1f), prompt = "bed ", imeAction = ImeAction.Next)
                        TermTextField(value = wake, onValueChange = { wake = it.take(7) }, placeholder = "06:45", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number, modifier = Modifier.weight(1f), prompt = "up ", imeAction = ImeAction.Done)
                        val b = Sleep.parseClock(bed); val w = Sleep.parseClock(wake)
                        TermButton("log", color = p.purple, enabled = b != null || w != null, onClick = {
                            scope.launch { app.habits.logSleep(date, bedMinutes = b?.let { Sleep.bedMinutesFor(it) }, wakeMinutes = w); open = false }
                        })
                        if (sleepLog != null) TermButton("rm", color = p.red, onClick = { scope.launch { app.habits.clearSleep(date); open = false } })
                    }
                    Comment("the night before ${date.format(DateTimeFormatter.ofPattern("EEE dd"))} · insights compares habits after short vs full nights")
                }
            }
        }

        // Watch of the day
        item(key = "watch") {
            Spacer(Modifier.height(4.dp))
            TerminalPanel(title = "watch", titleColor = p.cyan, onClick = { nav.navigate(Routes.wearLog(date.toEpochDay())) }) {
                if (wearToday.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BracketCheckbox(checked = false, color = p.cyan)
                        Spacer(Modifier.width(8.dp))
                        Text("log today's watch", color = p.fg)
                        Spacer(Modifier.weight(1f))
                        Text("⌁", color = p.cyan)
                    }
                } else {
                    wearToday.forEach { w ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BracketCheckbox(checked = true, color = p.cyan)
                            Spacer(Modifier.width(8.dp))
                            Text(w.watch.nickname.ifBlank { "${w.watch.brand} ${w.watch.model}" }, color = p.fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (w.log.photoPath != null) Tag("photo", p.cyan)
                        }
                    }
                }
            }
        }
        item(key = "footer") {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermButton("habit add", onClick = { nav.navigate(Routes.habitEdit()) }, modifier = Modifier.weight(1f))
                TermButton("timeline", onClick = { nav.navigate(Routes.TIMELINE) }, color = p.cyan, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    }
}

@Composable
private fun SummaryPanel(s: DaySummary, onShieldInfo: () -> Unit) {
    val p = Term.palette
    val lp = Progression.progress(s.totalXp)
    TerminalPanel(title = "status") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("progress ", color = p.fgDim, style = MaterialTheme.typography.bodyMedium)
            AsciiProgress(fraction = s.fraction, width = 14, color = if (s.isPerfect) p.yellow else p.green)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("${s.done}/${s.due.size} done" + if (s.partial > 0) " · ${s.partial} min" else "", color = p.fg, style = MaterialTheme.typography.bodyMedium)
            Text("lvl ${lp.level} · ${s.totalXp} xp", color = p.purple, style = MaterialTheme.typography.bodyMedium)
            Text("⛨ ${s.shieldsAvailable}", color = if (s.shieldsAvailable > 0) p.cyan else p.fgDim, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable(onClick = onShieldInfo))
        }
        if (s.isPerfect) {
            Spacer(Modifier.height(4.dp))
            Text("★ perfect day — +${Progression.XP_PERFECT_DAY} xp", color = p.yellow, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HabitRow(
    hs: HabitStatus,
    /** Hardware-keyboard selection highlight (j/k on Today). */
    selected: Boolean = false,
    onToggle: () -> Unit,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    onTimer: () -> Unit,
    onOpen: () -> Unit,
    onShield: (LocalDate) -> Unit,
    shieldsAvailable: Int,
    onToggleItem: (Int) -> Unit = {},
    expanded: Boolean = false,
    onExpand: () -> Unit = {},
    onSkip: (String) -> Unit = {},
    onUnskip: () -> Unit = {},
    onAnnotate: (String?, Int?) -> Unit = { _, _ -> },
    onLateLog: (LocalDate) -> Unit = {},
    isToday: Boolean = true,
    /** Habit stacking: the unfinished anchor this row waits on (row is dimmed until it is ticked). */
    waitingOn: HabitStatus? = null,
    /** `meditate → journal → stretch` when the habit is part of a stack. */
    chain: String? = null,
    onMinimum: () -> Unit = {},
) {
    val p = Term.palette
    val color = p.named(hs.habit.color)
    val h = hs.habit
    val struck = hs.completed && !h.negative
    val waiting = waitingOn != null && !hs.completed && !hs.skipped
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (selected) p.bgHighlight else if (hs.completed || hs.skipped) p.bgAlt.copy(alpha = 0.5f) else p.bgAlt, RoundedCornerShape(6.dp))
            .then(if (selected) Modifier.border(1.5.dp, p.green, RoundedCornerShape(6.dp)) else Modifier)
            .then(if (waiting) Modifier.alpha(0.55f) else Modifier)
            .combinedClickable(onClick = { if (h.type == HabitType.CHECKBOX && !h.negative && !hs.skipped) onToggle() else onExpand() }, onLongClick = onExpand)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                hs.skipped -> Text("[»]", color = p.fgDim, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, modifier = Modifier.clickable(onClick = onUnskip))
                h.negative -> Text(if (hs.slipped) "[✗]" else "[✓]", color = if (hs.slipped) p.red else color, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onToggle))
                else -> BracketCheckbox(checked = hs.completed, partial = hs.partial || (hs.value > 0 && !hs.completed), color = if (hs.partial) p.yellow else color,
                    modifier = Modifier.clickable(onClick = onToggle))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    h.name, color = if (struck || hs.skipped) p.fgDim else if (hs.slipped) p.red else p.fg,
                    textDecoration = if (struck) TextDecoration.LineThrough else null,
                    style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (hs.streak.current > 0) Text("⚡${hs.streak.current}", color = p.orange, style = MaterialTheme.typography.labelSmall)
                    if (hs.streak.shieldedDays > 0) Text("⛨${hs.streak.shieldedDays}", color = p.cyan, style = MaterialTheme.typography.labelSmall)
                    dev.personalterminal.domain.Challenges.label(h, AppClock.today())?.let { lbl ->
                        Text(lbl + if (h.chStake > 0) " ⛨${h.chStake}" else "", color = p.yellow, style = MaterialTheme.typography.labelSmall)
                    }
                    val outlook = if (h.schedule == ScheduleType.WEEKLY) Schedule.weekOutlook(h, hs.weekCount, AppClock.today()) else null
                    val sched = when {
                        hs.skipped -> "skipped" + (hs.log?.skipReason?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                        h.negative -> if (hs.slipped) "slipped today" else "avoid · clean so far"
                        outlook != null -> outlook.label
                        else -> Schedule.describe(h)
                    }
                    val schedColor = when {
                        outlook == null || hs.skipped || hs.completed -> p.fgDim
                        outlook.lost -> p.red
                        outlook.lastChance -> p.yellow
                        else -> p.fgDim
                    }
                    Text(sched, color = schedColor, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (hs.partial) Text("[~] min", color = p.yellow, style = MaterialTheme.typography.labelSmall)
                    hs.strength?.takeIf { it.slipping && !hs.completed }?.let { Text("↓${it.score}%", color = p.red, style = MaterialTheme.typography.labelSmall) }
                    if (hs.mood > 0) Text("★".repeat(hs.mood), color = p.yellow, style = MaterialTheme.typography.labelSmall)
                    if (hs.note.isNotBlank()) Text("✎", color = p.cyan, style = MaterialTheme.typography.labelSmall)
                    if (h.reminderMinutes >= 0 || (h.anchorId > 0L && h.anchorRemind)) Text("⏰", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
                }
                val ramp = if (h.type == HabitType.COUNTER || h.type == HabitType.TIMER) Targets.rampLabel(h, AppClock.today()) else null
                if (chain != null || ramp != null) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (chain != null) Text((if (waiting) "⌛ " else "⛓ ") + chain, color = if (waiting) p.yellow else p.fgDim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (ramp != null) Text("↗ $ramp", color = p.purple, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when (h.type) {
                HabitType.CHECKBOX -> {}
                HabitType.CHECKLIST -> Text("${hs.value}/${h.checklistItems.size}", color = p.fg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                HabitType.COUNTER -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("[-]", color = if (hs.value > 0) color else p.fgDim, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.clickable(enabled = hs.value > 0, onClick = onDecrement).padding(4.dp))
                    Text("${hs.value}/${hs.target}", color = p.fg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text("[+]", color = color, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable(onClick = onIncrement).padding(4.dp))
                }
                HabitType.TIMER -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${hs.value}/${hs.target}m", color = p.fg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("[▶]", color = color, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clickable(onClick = onTimer).padding(4.dp))
                }
            }
        }
        if (h.type == HabitType.CHECKLIST) {
            // sub-items: `  [✓] shoes   [ ] towel` – each tap toggles one step
            Spacer(Modifier.height(4.dp))
            h.checklistItems.forEachIndexed { i, item ->
                val on = hs.log?.hasItem(i) == true
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = !hs.skipped) { onToggleItem(i) }.padding(start = 26.dp, top = 3.dp, bottom = 3.dp)) {
                    BracketCheckbox(checked = on, color = color)
                    Spacer(Modifier.width(8.dp))
                    Text(item, color = if (on) p.fgDim else p.fg, textDecoration = if (on) TextDecoration.LineThrough else null,
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        } else if (h.type != HabitType.CHECKBOX) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsciiProgress(fraction = hs.fraction, width = 20, color = if (hs.partial) p.yellow else color, showPercent = true, label = h.unit.ifBlank { null }, modifier = Modifier.weight(1f))
                if (h.minTarget > 0 && !hs.completed && !hs.skipped && hs.value < h.minTarget) {
                    Text("[~ min ${h.minTarget}]", color = p.yellow, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.clickable(onClick = onMinimum).padding(start = 6.dp, top = 2.dp, bottom = 2.dp))
                }
            }
        }
        val late = hs.streak.lateLogDay
        val repair = hs.streak.repairableDay
        if (late != null && isToday) {
            // repair window: the previous scheduled day was never logged → tick it late, no shield spent
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${late.format(DateTimeFormatter.ofPattern("EEE"))} not logged · until ${Streaks.LATE_LOG_CUTOFF_HOUR}:00", color = p.yellow, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TermButton("did it ${if (late == AppClock.today().minusDays(1)) "yesterday" else late.format(DateTimeFormatter.ofPattern("EEE"))}", onClick = { onLateLog(late) }, color = p.yellow)
            }
        } else if (repair != null && shieldsAvailable > 0) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("streak broke on ${repair.format(DateTimeFormatter.ofPattern("EEE dd"))}", color = p.red, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                TermButton("use shield ⛨", onClick = { onShield(repair) }, color = p.cyan)
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            RowActions(hs = hs, color = color, onOpen = onOpen, onSkip = onSkip, onUnskip = onUnskip, onAnnotate = onAnnotate, onToggle = onToggle)
        }
    }
}

/** Expanded strip under a habit row: skip with reason, note, mood, open. */
@Composable
private fun RowActions(
    hs: HabitStatus,
    color: androidx.compose.ui.graphics.Color,
    onOpen: () -> Unit,
    onSkip: (String) -> Unit,
    onUnskip: () -> Unit,
    onAnnotate: (String?, Int?) -> Unit,
    onToggle: () -> Unit,
) {
    val p = Term.palette
    var mode by remember(hs.habit.id) { mutableStateOf("") }
    var text by remember(hs.habit.id, mode) { mutableStateOf(if (mode == "note") hs.note else "") }
    Column(Modifier.fillMaxWidth().background(p.bg.copy(alpha = 0.4f), RoundedCornerShape(4.dp)).padding(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("mood", color = p.fgDim, style = MaterialTheme.typography.labelSmall)
            (1..5).forEach { m ->
                Text(if (m <= hs.mood) "★" else "☆", color = if (m <= hs.mood) p.yellow else p.fgDim, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.clickable { onAnnotate(null, if (hs.mood == m) 0 else m) }.padding(horizontal = 2.dp))
            }
            Spacer(Modifier.weight(1f))
            if (hs.habit.negative) TermButton(if (hs.slipped) "clear slip" else "log slip", color = p.red, onClick = onToggle)
            if (hs.skipped) TermButton("unskip", color = p.cyan, onClick = onUnskip)
            else TermButton("skip", color = if (mode == "skip") p.yellow else p.fgDim, onClick = { mode = if (mode == "skip") "" else "skip" })
            TermButton("note", color = if (mode == "note") p.cyan else p.fgDim, onClick = { mode = if (mode == "note") "" else "note" })
            TermButton("open", color = color, onClick = onOpen)
        }
        if (mode == "skip") {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("sick", "travel", "rest day", "no time").forEach { r -> Tag(r, p.yellow, Modifier.clickable { onSkip(r) }) }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TermTextField(value = text, onValueChange = { text = it }, placeholder = "other reason…", singleLine = true, imeAction = ImeAction.Done,
                    onImeAction = { onSkip(text) }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                TermButton("skip today", color = p.yellow, onClick = { onSkip(text) })
            }
            Comment("a skip bridges the streak without spending a shield")
        }
        if (mode == "note") {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TermTextField(value = text, onValueChange = { text = it }, placeholder = "how did it go?", singleLine = true, imeAction = ImeAction.Done,
                    onImeAction = { onAnnotate(text, null); mode = "" }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                TermButton("save", color = p.cyan, onClick = { onAnnotate(text, null); mode = "" })
            }
        }
        if (hs.note.isNotBlank() && mode != "note") Comment("✎ ${hs.note}", color = p.cyan)
    }
}
