package dev.personalterminal.ui.profile

import dev.personalterminal.domain.AppClock
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.db.displayName
import dev.personalterminal.data.db.sold
import dev.personalterminal.data.db.wished
import dev.personalterminal.data.prefs.Settings
import dev.personalterminal.domain.Progression
import dev.personalterminal.domain.Strength
import dev.personalterminal.ui.components.AsciiProgress
import dev.personalterminal.ui.components.Comment
import dev.personalterminal.ui.components.ContributionHeatmap
import dev.personalterminal.ui.components.KeyValue
import dev.personalterminal.ui.components.PromptLine
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.components.TerminalPanel
import dev.personalterminal.ui.navigation.Routes
import dev.personalterminal.ui.theme.Term
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun ProfileScreen(app: PersonalTerminalApp, nav: NavHostController) {
    val p = Term.palette
    val today = AppClock.today()
    val settings by app.prefs.settings.collectAsStateWithLifecycle(initialValue = Settings())
    val summary by remember { app.habits.observeDay(today) }.collectAsStateWithLifecycle(initialValue = null)
    val from = today.minusWeeks(52)
    val counts by remember { app.habits.observeCompletionCounts(from, today) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val recentXp by remember { app.habits.observeRecentXp(12) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val habits by remember { app.habits.observeHabits() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val watches by remember { app.watches.observeWatches() }.collectAsStateWithLifecycle(initialValue = emptyList())

    val s = summary
    val xp = s?.totalXp ?: 0
    val lp = Progression.progress(xp)
    val activeCount = habits.count { !it.archived }.coerceAtLeast(1)
    val heat = remember(counts, activeCount) { counts.associate { it.day to (it.count.toFloat() / activeCount).coerceIn(0f, 1f) } }
    val totalCompletions = counts.sumOf { it.count }
    val activeDays = counts.count { it.count > 0 }
    val bestStreak = s?.all?.maxOfOrNull { it.streak.best } ?: 0
    val longestCurrent = s?.all?.maxByOrNull { it.streak.current }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PromptLine("whoami", trailing = today.format(DateTimeFormatter.ISO_DATE))

        TerminalPanel(title = "user", titleColor = p.purple) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
                Text(settings.username, color = p.green, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("@${settings.hostname}", color = p.fgDim, style = MaterialTheme.typography.bodyLarge)
            }
            Text("${Progression.title(lp.level)} · level ${lp.level}", color = p.purple, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(6.dp))
            AsciiProgress(fraction = lp.fraction, width = 24, color = p.purple)
            Text("${lp.xpIntoLevel}/${lp.xpForNext} xp to level ${lp.level + 1}  ·  $xp xp total", color = p.fgDim, style = MaterialTheme.typography.bodySmall)
        }

        TerminalPanel(title = "contributions · last 52 weeks") {
            Row(Modifier.horizontalScroll(rememberScrollState(), reverseScrolling = true)) {
                ContributionHeatmap(values = heat, weeks = 52)
            }
            Spacer(Modifier.height(8.dp))
            KeyValue("total completions", "$totalCompletions")
            KeyValue("active days", "$activeDays / 365")
            KeyValue("best streak (any habit)", "⚡ $bestStreak", valueColor = p.orange)
            longestCurrent?.takeIf { it.streak.current > 0 }?.let { KeyValue("longest current", "${it.habit.name} ⚡${it.streak.current}", valueColor = p.orange) }
        }

        val strengths = s?.all?.filter { !it.habit.archived }?.mapNotNull { hs -> hs.strength?.let { hs to it } }.orEmpty()
        if (strengths.isNotEmpty()) TerminalPanel(title = "strength", titleColor = p.green, onClick = { nav.navigate(Routes.REVIEW) }) {
            val avg = strengths.map { it.second.score }.average().toInt()
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("$avg%", color = if (avg >= 70) p.green else if (avg >= Strength.SLIPPING) p.yellow else p.red, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.width(8.dp))
                Text("average across ${strengths.size} habit${if (strengths.size == 1) "" else "s"}", color = p.fgDim, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            strengths.sortedBy { it.second.score }.take(6).forEach { (hs, st) ->
                Row(Modifier.fillMaxWidth().clickable { nav.navigate(Routes.habitDetail(hs.habit.id)) }.padding(vertical = 1.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(hs.habit.name, color = p.named(hs.habit.color), style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
                    Text(Strength.sparkline(st.history), color = if (st.slipping) p.red else p.fgDim, style = MaterialTheme.typography.bodySmall)
                    Text(" %3d%% %s".format(st.score, st.arrow), color = if (st.slipping) p.red else p.fg, style = MaterialTheme.typography.bodySmall)
                }
            }
            Comment("a forgiving score: one miss costs ~7 pts, one day back earns ~7 · `strength` in the prompt")
        }

        TerminalPanel(title = "shields", titleColor = p.cyan) {
            Text("⛨ ${s?.shieldsAvailable ?: 0} available", color = p.cyan, style = MaterialTheme.typography.titleMedium)
            Comment("earn 1 shield every ${Progression.SHIELD_EVERY} completions (max ${Progression.MAX_SHIELDS} held)")
            Comment("a shield bridges one missed day so your streak survives")
            val next = Progression.SHIELD_EVERY - (totalCompletions % Progression.SHIELD_EVERY)
            Spacer(Modifier.height(4.dp))
            AsciiProgress(fraction = (totalCompletions % Progression.SHIELD_EVERY) / Progression.SHIELD_EVERY.toFloat(), width = 20, color = p.cyan, label = "$next to next")
        }

        TerminalPanel(title = "xp log") {
            if (recentXp.isEmpty()) Comment("complete a habit to earn xp")
            recentXp.forEach { e ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${LocalDate.ofEpochDay(e.day).format(DateTimeFormatter.ofPattern("MM-dd"))}  ${e.reason.replace('_', ' ')}" +
                            (habits.firstOrNull { it.id == e.habitId }?.let { "  ${it.name}" } ?: ""),
                        color = p.fg, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    )
                    Text("+${e.amount}", color = p.green, style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(4.dp))
            Comment("+${Progression.XP_COMPLETE} per habit · +${Progression.XP_MINIMUM} minimum version · +${Progression.XP_STREAK_WEEK} each 7-day streak · +${Progression.XP_PERFECT_DAY} perfect day")
        }

        val focusTotal by remember { app.habits.observeTotalFocusMinutes() }.collectAsStateWithLifecycle(initialValue = 0)
        TerminalPanel(title = "collection", titleColor = p.cyan) {
            val lifecycle by remember { app.watches.observeAllWatches() }.collectAsStateWithLifecycle(initialValue = emptyList())
            val wish = lifecycle.count { it.wished }; val soldN = lifecycle.count { it.sold }
            KeyValue("watches", "${watches.size}" + listOfNotNull(wish.takeIf { it > 0 }?.let { "$it wished" }, soldN.takeIf { it > 0 }?.let { "$it sold" }).takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = " · ").orEmpty())
            lifecycle.filter { it.wished && (it.targetPrice ?: 0.0) > 0 }.maxByOrNull { it.savedSoFar / it.targetPrice!! }?.let { w ->
                KeyValue("next", "${w.displayName} · ${((w.savedSoFar / w.targetPrice!!) * 100).toInt().coerceIn(0, 100)}% funded", valueColor = p.yellow)
            }
            KeyValue("habits", "${habits.count { !it.archived }} active · ${habits.count { it.archived }} archived")
            if (focusTotal > 0) KeyValue("focus logged", "${focusTotal / 60}h ${focusTotal % 60}m", valueColor = p.orange)
        }

        // Backup heartbeat: one line that answers "is my data safe?" – age, size, photos, last verification.
        TerminalPanel(title = "backup", titleColor = p.yellow, onClick = { nav.navigate(Routes.SETTINGS) }) {
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            var checking by remember { mutableStateOf(false) }
            var selfCheck by remember { mutableStateOf<String?>(null) }
            val linked = settings.driveAccountEmail.isNotBlank()
            val ageMs = System.currentTimeMillis() - settings.lastBackupAt
            val stale = settings.lastBackupAt == 0L || ageMs > (settings.backupIntervalHours.coerceAtLeast(6) * 2L) * 3_600_000L
            val line = when {
                !linked -> "drive not linked · local exports only"
                settings.lastBackupAt == 0L -> "no backup yet"
                else -> "last backup ${dev.personalterminal.domain.Sleep.ago(ageMs)}" +
                    (if (settings.lastBackupBytes > 0) " · ${"%.1f".format(settings.lastBackupBytes / 1_048_576.0)} MB" else "") +
                    (if (settings.lastBackupMedia > 0) " · ${settings.lastBackupMedia} photos" else "")
            }
            Text(line, color = if (!linked) p.fgDim else if (stale) p.red else p.fg, style = MaterialTheme.typography.bodyMedium)
            if (linked) {
                val v = settings.lastVerifiedStatus
                Text(
                    if (settings.lastVerifiedAt == 0L) "never verified · settings › verify downloads the newest archive and dry-runs a restore"
                    else "verified ${dev.personalterminal.domain.Sleep.ago(System.currentTimeMillis() - settings.lastVerifiedAt)} · ${v.substringBefore(" · ").take(48)}",
                    color = if (v.startsWith("ok")) p.green else if (v.startsWith("warn")) p.yellow else if (v.startsWith("error")) p.red else p.fgDim,
                    style = MaterialTheme.typography.labelSmall, maxLines = 2,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TermButton(if (checking) "checking…" else "self-check", color = p.cyan, enabled = !checking, onClick = {
                    scope.launch {
                        checking = true
                        selfCheck = runCatching { app.backups.selfCheck() }.fold({ "archive ok · ${it.summary} · ${it.bytes / 1024} KB" }, { "self-check failed: ${it.message}" })
                        checking = false
                    }
                })
                selfCheck?.let { Text(it, color = if (it.startsWith("archive ok")) p.green else p.red, style = MaterialTheme.typography.labelSmall, maxLines = 2, modifier = Modifier.weight(1f)) }
            }
            Comment("self-check writes an archive of the current data and reads it back – no upload")
        }

        TerminalPanel(title = "insights", titleColor = p.purple) {
            val newReview = settings.lastReviewDay < dev.personalterminal.domain.Schedule.weekStart(today).toEpochDay()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermButton(if (newReview) "review --week ●" else "review --week", color = p.cyan, onClick = { nav.navigate(Routes.REVIEW) }, modifier = Modifier.weight(1f))
                TermButton("review --year", color = p.orange, onClick = { nav.navigate(Routes.YEAR_REVIEW) }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermButton("achievements", color = p.yellow, onClick = { nav.navigate(Routes.ACHIEVEMENTS) }, modifier = Modifier.weight(1f))
                TermButton("vault", color = p.green, onClick = { nav.navigate(Routes.WATCH_BOX) }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermButton("insights", color = p.purple, onClick = { nav.navigate(Routes.INSIGHTS) }, modifier = Modifier.weight(1f))
                TermButton("journal", color = p.green, onClick = { nav.navigate(Routes.JOURNAL) }, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TermButton("planner", color = p.yellow, onClick = { nav.navigate(Routes.PLANNER) }, modifier = Modifier.weight(1f))
                TermButton("garden", color = p.green, onClick = { nav.navigate(Routes.GARDEN) }, modifier = Modifier.weight(1f))
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TermButton("settings", onClick = { nav.navigate(Routes.SETTINGS) }, modifier = Modifier.weight(1f))
            TermButton("timeline", onClick = { nav.navigate(Routes.TIMELINE) }, color = p.cyan, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
    }
}
