package dev.personalterminal.ui.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.db.Habit
import dev.personalterminal.data.db.HabitType
import dev.personalterminal.data.db.TimeOfDay
import dev.personalterminal.data.db.checklistItems
import dev.personalterminal.data.db.ScheduleType
import dev.personalterminal.domain.AppClock
import dev.personalterminal.domain.Schedule
import dev.personalterminal.ui.components.Comment
import dev.personalterminal.ui.components.PromptLine
import dev.personalterminal.ui.components.RadioRow
import dev.personalterminal.ui.components.Stepper
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.components.TermTextField
import dev.personalterminal.ui.components.TerminalPanel
import dev.personalterminal.ui.theme.Palettes
import dev.personalterminal.ui.theme.Term
import kotlinx.coroutines.launch
import java.time.DayOfWeek

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HabitEditScreen(app: PersonalTerminalApp, nav: NavHostController, habitId: Long, routineId: Long?, initialName: String? = null) {
    val p = Term.palette
    val scope = rememberCoroutineScope()
    val routines by remember { app.habits.observeRoutines() }.collectAsStateWithLifecycle(initialValue = emptyList())

    // rememberSaveable: an edit in progress must survive a fold (cover ⇄ inner screen) – the
    // activity is recreated and plain remember would reset every field back to the database.
    // `original` stays a plain remember: Habit isn't saveable and is re-fetched in the effect.
    var loaded by rememberSaveable { mutableStateOf(habitId == 0L) }
    var original by remember { mutableStateOf<Habit?>(null) }
    var name by rememberSaveable { mutableStateOf(initialName ?: "") }
    var type by rememberSaveable { mutableStateOf(HabitType.CHECKBOX) }
    var target by rememberSaveable { mutableStateOf(1) }
    var unit by rememberSaveable { mutableStateOf("") }
    var schedule by rememberSaveable { mutableStateOf(ScheduleType.DAILY) }
    var daysMask by rememberSaveable { mutableStateOf(127) }
    var monthDay by rememberSaveable { mutableStateOf(15) }
    var timesPerWeek by rememberSaveable { mutableStateOf(3) }
    var selectedRoutine by rememberSaveable { mutableStateOf(routineId) }
    var color by rememberSaveable { mutableStateOf("green") }
    var notes by rememberSaveable { mutableStateOf("") }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var negative by rememberSaveable { mutableStateOf(false) }
    var reminder by rememberSaveable { mutableStateOf(-1) }
    var checkIn by rememberSaveable { mutableStateOf(false) }
    var items by rememberSaveable { mutableStateOf(listOf<String>()) }
    var newItem by rememberSaveable { mutableStateOf("") }
    var timeOfDay by rememberSaveable { mutableStateOf("") }
    var focusMin by rememberSaveable { mutableStateOf(0) }
    var breakMin by rememberSaveable { mutableStateOf(0) }
    var healthMetric by rememberSaveable { mutableStateOf("") }
    var minTarget by rememberSaveable { mutableStateOf(0) }
    var rampTo by rememberSaveable { mutableStateOf(0) }
    var rampWeeks by rememberSaveable { mutableStateOf(0) }
    var rampStartDay by rememberSaveable { mutableStateOf(0L) }
    var anchorId by rememberSaveable { mutableStateOf(0L) }
    var anchorRemind by rememberSaveable { mutableStateOf(true) }
    var area by rememberSaveable { mutableStateOf("") }
    val allHabits by remember { app.habits.observeActiveHabits() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by app.prefs.settings.collectAsStateWithLifecycle(initialValue = dev.personalterminal.data.prefs.Settings())

    LaunchedEffect(habitId) {
        // After a fold/recreation `original` must be re-fetched (save/archive/delete need it),
        // but the form fields are only filled on the very first load: `loaded` comes back true
        // through rememberSaveable, so a restored, half-typed form isn't clobbered by the DB.
        val firstLoad = !loaded
        if (habitId != 0L) app.habits.habit(habitId)?.let { h ->
            original = h
            if (firstLoad) {
                name = h.name; type = h.type; target = h.target; unit = h.unit; schedule = h.schedule
                daysMask = h.daysMask; timesPerWeek = h.timesPerWeek; selectedRoutine = h.routineId; color = h.color; notes = h.notes; monthDay = h.monthDay
                negative = h.negative; reminder = h.reminderMinutes; checkIn = h.checkIn; items = h.checklistItems; timeOfDay = h.timeOfDay; focusMin = h.focusMinutes; breakMin = h.breakMinutes; healthMetric = h.healthMetric
                minTarget = h.minTarget; rampTo = h.rampTo; rampWeeks = h.rampWeeks; rampStartDay = h.rampStartDay; anchorId = h.anchorId; anchorRemind = h.anchorRemind || h.anchorId == 0L; area = h.area
            }
        }
        loaded = true
    }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PromptLine(if (habitId == 0L) "habit add" else "habit edit ${original?.name ?: ""}")
        if (!loaded) { Comment("loading…"); return@Column }

        TermTextField(value = name, onValueChange = { name = it }, label = "name", placeholder = "e.g. drink water")
        if (habitId == 0L) {
            Text("→ pick from templates", color = p.cyan, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.clickable { nav.navigate(dev.personalterminal.ui.navigation.Routes.TEMPLATES) }.padding(vertical = 2.dp))
        }

        Column {
            Text("type:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
            RadioRow(options = HabitType.entries, selected = type, onSelect = {
                type = it
                if (it == HabitType.CHECKBOX || it == HabitType.CHECKLIST) target = 1
                else if (target <= 1) target = if (it == HabitType.TIMER) 25 else 8
                if (it == HabitType.TIMER) unit = "min"
                if (it != HabitType.CHECKBOX) negative = false
            }, label = { it.name.lowercase() })
            Comment(
                when (type) {
                    HabitType.CHECKBOX -> "simple win: done / not done"
                    HabitType.COUNTER -> "count towards a daily target, e.g. 8 cups"
                    HabitType.TIMER -> "minutes of focus; use the pomodoro timer to log"
                    HabitType.CHECKLIST -> "a list of steps; the habit is done when every item is ticked"
                },
            )
            if (type == HabitType.CHECKLIST) {
                Spacer(Modifier.height(6.dp))
                Text("items:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                items.forEachIndexed { i, item ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("[ ]", color = p.fgDim, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(item, color = p.fg, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text("▲", color = if (i > 0) p.cyan else p.fgDim, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clickable(enabled = i > 0) { items = items.toMutableList().apply { add(i - 1, removeAt(i)) } }.padding(4.dp))
                        Text("▼", color = if (i < items.lastIndex) p.cyan else p.fgDim, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clickable(enabled = i < items.lastIndex) { items = items.toMutableList().apply { add(i + 1, removeAt(i)) } }.padding(4.dp))
                        Text("✕", color = p.red, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { items = items.filterIndexed { j, _ -> j != i } }.padding(4.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TermTextField(value = newItem, onValueChange = { newItem = it.take(40) }, placeholder = if (items.isEmpty()) "e.g. shoes, towel, bottle" else "another step", modifier = Modifier.weight(1f),
                        imeAction = ImeAction.Done, onImeAction = { if (newItem.isNotBlank() && items.size < 20) { items = items + newItem.trim(); newItem = "" } })
                    Spacer(Modifier.width(8.dp))
                    TermButton("+ add", enabled = newItem.isNotBlank() && items.size < 20, onClick = { items = items + newItem.trim(); newItem = "" })
                }
                Comment(if (items.isEmpty()) "type an item and press add · commas split into several (up to 20)" else "${items.size} item${if (items.size > 1) "s" else ""} · tick them one by one on today")
            }
            if (type == HabitType.CHECKBOX) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { negative = !negative }.padding(top = 6.dp, bottom = 2.dp)) {
                    Text(if (negative) "[✓]" else "[ ]", color = if (negative) p.red else p.fgDim, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text("avoid-habit (e.g. no sugar)", color = p.fg, style = MaterialTheme.typography.bodyMedium)
                }
                if (negative) Comment("every scheduled day counts as kept unless you log a slip; the streak grows on its own")
            }
        }

        if (type == HabitType.COUNTER || type == HabitType.TIMER) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("target:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                    Stepper(value = target, onChange = { target = it }, min = 1, max = 10_000)
                }
                TermTextField(value = unit, onValueChange = { unit = it.take(10) }, label = "unit", placeholder = if (type == HabitType.TIMER) "min" else "cups", modifier = Modifier.weight(1f))
            }
            // minimum version: the two-minute rule as a field
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Column {
                    Text("minimum:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                    Stepper(value = minTarget, onChange = { minTarget = it }, min = 0, max = (target - 1).coerceAtLeast(0), color = p.yellow)
                }
                Spacer(Modifier.width(12.dp))
                Comment(if (minTarget > 0) "$minTarget ${unit.ifBlank { "" }} keeps the streak on a thin day → logged as [~] min, half the xp".trim() else "0 = off · a floor that still counts (e.g. 2 pages of 20)", modifier = Modifier.weight(1f))
            }
            // ramp: grow the target without weekly edits
            Column(Modifier.padding(top = 6.dp)) {
                Text("ramp:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Chip(label = "off", selected = rampWeeks == 0, color = p.fgDim) { rampTo = 0; rampWeeks = 0; rampStartDay = 0L }
                    listOf(4, 8, 12).forEach { w -> Chip(label = "$w weeks", selected = rampWeeks == w, color = p.purple) { rampWeeks = w; if (rampTo <= 0) rampTo = target * 2; if (rampStartDay == 0L) rampStartDay = AppClock.today().toEpochDay() } }
                }
                if (rampWeeks > 0) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("to ", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                    Stepper(value = rampTo, onChange = { rampTo = it }, min = 1, max = 10_000, color = p.purple)
                    Spacer(Modifier.width(8.dp))
                    Text("over ", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                    Stepper(value = rampWeeks, onChange = { rampWeeks = it }, min = 1, max = 52, suffix = "w", color = p.purple)
                }
                Comment(
                    if (rampWeeks > 0 && rampTo != target) "$target → $rampTo ${unit.trim()} in $rampWeeks weekly steps from ${java.time.LocalDate.ofEpochDay(if (rampStartDay > 0) rampStartDay else AppClock.today().toEpochDay())} · each day is judged against that day's target".trim()
                    else "target grows one step a week – the streak keeps counting against the day's target",
                )
            }
        }

        Column {
            Text("stack after:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                Chip(label = "nothing", selected = anchorId == 0L, color = p.fgDim) { anchorId = 0L }
                allHabits.filter { it.id != habitId && it.anchorId != habitId }.forEach { a -> Chip(label = a.name, selected = anchorId == a.id, color = p.cyan) { anchorId = a.id } }
            }
            if (anchorId != 0L) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { anchorRemind = !anchorRemind }.padding(top = 6.dp, bottom = 2.dp)) {
                    Text(if (anchorRemind) "[✓]" else "[ ]", color = if (anchorRemind) p.cyan else p.fgDim, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text("nudge me the moment ${allHabits.firstOrNull { it.id == anchorId }?.name ?: "the anchor"} is ticked", color = p.fg, style = MaterialTheme.typography.bodyMedium)
                }
                Comment("habit stacking: shown as a chain on today, greyed until the anchor is done")
            } else Comment("`after X` – anchor this habit to one you already do (habit stacking)")
        }

        Column {
            Text("life area:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                Chip(label = "none", selected = area.isBlank(), color = p.fgDim) { area = "" }
                dev.personalterminal.domain.Areas.all.forEach { a -> Chip(label = "${a.glyph} ${a.label}", selected = area == a.id, color = p.green) { area = a.id } }
            }
            Comment("feeds the balance radar in the weekly review")
        }

        Column {
            Text("schedule:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
            RadioRow(options = ScheduleType.entries, selected = schedule, onSelect = { schedule = it }, label = {
                when (it) {
                    ScheduleType.DAILY -> "daily"; ScheduleType.WEEKLY -> "x/week"; ScheduleType.SPECIFIC_DAYS -> "days"
                    ScheduleType.BIWEEKLY -> "biweekly"; ScheduleType.MONTHLY -> "monthly"
                }
            })
            when (schedule) {
                ScheduleType.SPECIFIC_DAYS -> Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        val on = Schedule.isDayEnabled(daysMask, d)
                        Text(
                            d.name.take(2).lowercase(),
                            color = if (on) p.bg else p.fgDim,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier
                                .background(if (on) p.green else p.bgAlt, RoundedCornerShape(4.dp))
                                .border(1.dp, if (on) p.green else p.border, RoundedCornerShape(4.dp))
                                .clickable { daysMask = Schedule.toggle(daysMask, d) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                ScheduleType.WEEKLY -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("times per week: ", color = p.fgDim, style = MaterialTheme.typography.bodyMedium)
                    Stepper(value = timesPerWeek, onChange = { timesPerWeek = it }, min = 1, max = 7)
                }
                ScheduleType.BIWEEKLY -> Column(Modifier.padding(top = 6.dp)) {
                    Comment("on the masked weekdays, every other week (parity anchored at creation)")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DayOfWeek.entries.forEach { d ->
                            val on = Schedule.isDayEnabled(daysMask, d)
                            Text(
                                d.name.take(2).lowercase(),
                                color = if (on) p.bg else p.fgDim,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .background(if (on) p.green else p.bgAlt, RoundedCornerShape(4.dp))
                                    .border(1.dp, if (on) p.green else p.border, RoundedCornerShape(4.dp))
                                    .clickable { daysMask = Schedule.toggle(daysMask, d) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
                ScheduleType.MONTHLY -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("day of month: ", color = p.fgDim, style = MaterialTheme.typography.bodyMedium)
                    Stepper(value = monthDay, onChange = { monthDay = it }, min = 1, max = 31)
                }
                ScheduleType.DAILY -> {}
            }
        }

        Column {
            Text("time of day:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                Chip(label = "auto", selected = timeOfDay.isBlank(), color = p.fgDim) { timeOfDay = "" }
                TimeOfDay.entries.forEach { t -> Chip(label = "${t.glyph} ${t.label}", selected = timeOfDay == t.name, color = p.cyan) { timeOfDay = t.name } }
            }
            Comment(if (timeOfDay.isBlank()) "auto = from the reminder time, else anytime · groups the today screen when `sections` is on" else "shown under ${TimeOfDay.of(timeOfDay).label} on today")
        }

        Column {
            Text("routine:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Chip(label = "unsorted", selected = selectedRoutine == null, color = p.fgDim) { selectedRoutine = null }
                    routines.forEach { r -> Chip(label = "${r.icon} ${r.name}", selected = selectedRoutine == r.id, color = p.purple) { selectedRoutine = r.id } }
                }
            }
        }

        Column {
            Text("reminder:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                Chip(label = "off", selected = reminder < 0, color = p.fgDim) { reminder = -1 }
                listOf(7 * 60, 9 * 60, 12 * 60 + 30, 18 * 60, 21 * 60).forEach { m ->
                    Chip(label = "%02d:%02d".format(m / 60, m % 60), selected = reminder == m, color = p.yellow) { reminder = m }
                }
            }
            if (reminder >= 0) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("fine-tune: ", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                    Stepper(value = reminder / 60, onChange = { reminder = it * 60 + reminder % 60 }, min = 0, max = 23, suffix = "h", color = p.yellow)
                    Spacer(Modifier.width(8.dp))
                    Stepper(value = reminder % 60, onChange = { reminder = (reminder / 60) * 60 + it }, min = 0, max = 59, suffix = "m", color = p.yellow)
                }
                Comment(
                    if (settings.hasQuietHours) "muted during quiet hours %02d:%02d–%02d:%02d (settings)".format(settings.quietStartMin / 60, settings.quietStartMin % 60, settings.quietEndMin / 60, settings.quietEndMin % 60)
                    else "no quiet hours configured",
                )
            }
            // evening check-in: "did you do it?" if still unlogged at the global check-in time
            val ci = settings.notifications
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { checkIn = !checkIn }.padding(top = 6.dp, bottom = 2.dp)) {
                Text(if (checkIn) "[✓]" else "[ ]", color = if (checkIn) p.yellow else p.fgDim, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("check-in at %02d:%02d if not logged".format(ci.checkInMinutes / 60, ci.checkInMinutes % 60), color = p.fg, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (!ci.habitCheckIn || !settings.remindersEnabled) "check-ins are switched off in settings › notifications" else "asks \"done today?\" with done / skip buttons · time in settings",
                        color = if (!ci.habitCheckIn || !settings.remindersEnabled) p.red else p.fgDim, style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }

        if (type == HabitType.TIMER) {
            Column {
                Text("focus interval:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Chip(label = "global (${settings.pomodoroFocusMin}/${settings.pomodoroBreakMin})", selected = focusMin == 0, color = p.fgDim) { focusMin = 0; breakMin = 0 }
                    listOf(15 to 3, 25 to 5, 50 to 10, 90 to 20).forEach { (f, b) ->
                        Chip(label = "$f/$b", selected = focusMin == f && breakMin == b, color = p.orange) { focusMin = f; breakMin = b }
                    }
                }
                if (focusMin > 0) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("focus ", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                    Stepper(value = focusMin, onChange = { focusMin = it }, min = 1, max = 180, suffix = "m", color = p.orange)
                    Spacer(Modifier.width(10.dp))
                    Text("break ", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                    Stepper(value = breakMin, onChange = { breakMin = it }, min = 1, max = 60, suffix = "m", color = p.cyan)
                }
                Comment("used when you start the timer from this habit")
            }
        }

        if ((type == HabitType.COUNTER || type == HabitType.TIMER) && settings.healthConnect) {
            Column {
                Text("health connect:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Chip(label = "none", selected = healthMetric.isBlank(), color = p.fgDim) { healthMetric = "" }
                    dev.personalterminal.health.HealthMetric.entries.forEach { m ->
                        Chip(label = m.label, selected = healthMetric == m.id, color = p.green) { healthMetric = m.id; if (unit.isBlank()) unit = m.unitHint }
                    }
                }
                Comment("today's value is filled in automatically from Health Connect")
            }
        }

        Column {
            Text("color:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Palettes.colorNames.forEach { c ->
                    val col = p.named(c)
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(col, RoundedCornerShape(4.dp))
                            .border(if (color == c) 2.dp else 0.dp, if (color == c) p.fg else col, RoundedCornerShape(4.dp))
                            .clickable { color = c },
                        contentAlignment = Alignment.Center,
                    ) { if (color == c) Text("✓", color = p.bg, fontWeight = FontWeight.Bold) }
                }
            }
        }

        TermTextField(value = notes, onValueChange = { notes = it }, label = "notes", placeholder = "optional", singleLine = false, imeAction = ImeAction.Default, keyboardType = KeyboardType.Text)

        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TermButton(
                if (habitId == 0L) "create" else "save", filled = true, modifier = Modifier.weight(1f), enabled = name.isNotBlank() && (type != HabitType.CHECKLIST || items.isNotEmpty()),
                onClick = {
                    scope.launch {
                        val cleanItems = items.flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.take(20)
                        val h = (original ?: Habit(name = "")).copy(
                            name = name.trim(), type = type,
                            target = when (type) { HabitType.CHECKBOX -> 1; HabitType.CHECKLIST -> cleanItems.size.coerceAtLeast(1); else -> target.coerceAtLeast(1) },
                            unit = unit.trim(), schedule = schedule, daysMask = if (daysMask == 0) 127 else daysMask, monthDay = monthDay.coerceIn(1, 31),
                            timesPerWeek = timesPerWeek, routineId = selectedRoutine, color = color, notes = notes.trim(),
                            negative = negative && type == HabitType.CHECKBOX, reminderMinutes = reminder, checkIn = checkIn,
                            focusMinutes = if (type == HabitType.TIMER) focusMin else 0, breakMinutes = if (type == HabitType.TIMER) breakMin else 0,
                            healthMetric = if (type == HabitType.COUNTER || type == HabitType.TIMER) healthMetric else "",
                            checklist = if (type == HabitType.CHECKLIST) cleanItems.joinToString("\n") else "",
                            timeOfDay = timeOfDay,
                            minTarget = if (type == HabitType.COUNTER || type == HabitType.TIMER) minTarget.coerceIn(0, (target - 1).coerceAtLeast(0)) else 0,
                            rampTo = if ((type == HabitType.COUNTER || type == HabitType.TIMER) && rampWeeks > 0 && rampTo != target) rampTo else 0,
                            rampWeeks = if ((type == HabitType.COUNTER || type == HabitType.TIMER) && rampWeeks > 0 && rampTo != target) rampWeeks else 0,
                            rampStartDay = if ((type == HabitType.COUNTER || type == HabitType.TIMER) && rampWeeks > 0 && rampTo != target) (if (rampStartDay > 0) rampStartDay else AppClock.today().toEpochDay()) else 0L,
                            anchorId = anchorId, anchorRemind = anchorId != 0L && anchorRemind,
                            area = area,
                        )
                        app.habits.saveHabit(h)
                        dev.personalterminal.reminders.ReminderScheduler.reschedule(app)
                        nav.popBackStack()
                    }
                },
            )
            TermButton("cancel", onClick = { nav.popBackStack() }, color = p.fgDim)
        }
        if (original != null) {
            TerminalPanel(title = "challenge", titleColor = p.yellow) {
                val c = dev.personalterminal.domain.Challenges
                val today = dev.personalterminal.domain.AppClock.today()
                if (c.isActive(original!!, today)) {
                    Text(c.status(original!!, today), color = p.fg, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    TermButton("clear challenge", color = p.yellow, onClick = {
                        scope.launch { app.habits.saveHabit(original!!.copy(chStart = 0L, chEnd = 0L, chStake = 0, chResolved = false)) }
                    })
                } else {
                    if (c.isSettling(original!!, today)) {
                        Comment("window closed — run `challenge ${original!!.name}` at the prompt for the certificate")
                        Spacer(Modifier.height(4.dp))
                    }
                    var chDays by remember(original) { mutableStateOf(30) }
                    var chStake by remember(original) { mutableStateOf(1) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("days:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(8.dp))
                        listOf(14, 30, 75, 100).forEach { d ->
                            Text(
                                (if (chDays == d) "[$d]" else " $d "),
                                color = if (chDays == d) p.green else p.fgDim,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.clickable { chDays = d }.padding(horizontal = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("stake:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(8.dp))
                        listOf(0, 1, 3, 5).forEach { s ->
                            Text(
                                (if (chStake == s) "[${if (s == 0) "none" else "$s"}]" else " ${if (s == 0) "none" else "$s"} "),
                                color = if (chStake == s) p.green else p.fgDim,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.clickable { chStake = s }.padding(horizontal = 4.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    TermButton(
                        "start $chDays-day challenge",
                        color = p.yellow,
                        onClick = {
                            scope.launch {
                                app.habits.saveHabit(
                                    original!!.copy(
                                        chStart = today.toEpochDay(),
                                        chEnd = today.toEpochDay() + chDays - 1,
                                        chStake = chStake,
                                        chResolved = false,
                                    )
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Comment("win = half the scheduled days · stake pays +10 xp per point, −5 on a miss")
                }
            }
            TerminalPanel(title = "pause", titleColor = p.yellow) {
                val today = dev.personalterminal.domain.AppClock.today()
                val pausedLabel = dev.personalterminal.domain.Schedule.pauseLabel(original!!, today)
                var until by remember(original) { mutableStateOf("") }
                if (pausedLabel != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(pausedLabel, color = p.yellow, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TermButton("resume now", color = p.green, onClick = { scope.launch { app.habits.pauseHabit(original!!, null) } })
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("1w", "2w", "1m").forEach { q -> TermButton(q, color = p.yellow, onClick = { scope.launch { app.habits.pauseHabit(original!!, dev.personalterminal.domain.Schedule.parseUntil(q, today)) } }) }
                        TermTextField(value = until, onValueChange = { until = it.take(10) }, placeholder = "yyyy-mm-dd", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f), prompt = "until ", imeAction = ImeAction.Done)
                        val parsed = dev.personalterminal.domain.Schedule.parseUntil(until, today)
                        TermButton("go", color = p.yellow, enabled = parsed != null, onClick = { scope.launch { app.habits.pauseHabit(original!!, parsed) } })
                    }
                }
                Comment("paused habits leave today & the widget and come back by themselves; the streak is kept")
            }
            TerminalPanel(title = "danger zone", titleColor = p.red, borderColor = p.red.copy(alpha = 0.5f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TermButton("archive", color = p.yellow, onClick = { scope.launch { app.habits.setArchived(original!!, true); nav.popBackStack() } })
                    if (!confirmDelete) TermButton("rm -rf", color = p.red, onClick = { confirmDelete = true })
                    else TermButton("confirm delete", color = p.red, filled = true, onClick = {
                        scope.launch { app.habits.deleteHabit(original!!); nav.popBackStack(dev.personalterminal.ui.navigation.Routes.HABITS, false) }
                    })
                }
                Comment("archive keeps history; delete removes logs too")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val p = Term.palette
    Text(
        text = (if (selected) "(•) " else "( ) ") + label,
        color = if (selected) color else p.fgDim,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.clickable(onClick = onClick).padding(vertical = 3.dp),
    )
}
