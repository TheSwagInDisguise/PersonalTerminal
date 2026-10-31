package dev.personalterminal.ui.watch

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
import androidx.compose.foundation.horizontalScroll
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
import androidx.navigation.NavHostController
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.db.Watch
import dev.personalterminal.data.db.complicationSet
import dev.personalterminal.domain.AppClock
import dev.personalterminal.domain.Uptime
import dev.personalterminal.ui.components.Comment
import dev.personalterminal.ui.components.PromptLine
import dev.personalterminal.ui.components.TermButton
import dev.personalterminal.ui.components.TermTextField
import dev.personalterminal.ui.components.TerminalPanel
import dev.personalterminal.ui.theme.Palettes
import dev.personalterminal.ui.theme.Term
import kotlinx.coroutines.launch

private val movements = listOf("automatic", "manual", "quartz", "solar", "spring drive", "smart")

@Composable
fun WatchEditScreen(app: PersonalTerminalApp, nav: NavHostController, watchId: Long, startOnWishlist: Boolean = false) {
    val p = Term.palette
    val scope = rememberCoroutineScope()
    // rememberSaveable: an edit in progress must survive a fold (cover ⇄ inner screen) – the
    // activity is recreated and plain remember would reset every field back to the database.
    // `original` stays a plain remember: Watch isn't saveable and is re-fetched in the effect.
    var original by remember { mutableStateOf<Watch?>(null) }
    var loaded by rememberSaveable { mutableStateOf(watchId == 0L) }
    var brand by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    var reference by rememberSaveable { mutableStateOf("") }
    var movement by rememberSaveable { mutableStateOf("") }
    var caseSize by rememberSaveable { mutableStateOf("") }
    var color by rememberSaveable { mutableStateOf("cyan") }
    var notes by rememberSaveable { mutableStateOf("") }
    var lugWidth by rememberSaveable { mutableStateOf("") }
    var purchasePrice by rememberSaveable { mutableStateOf("") }
    var purchaseDate by rememberSaveable { mutableStateOf("") }
    var currentValue by rememberSaveable { mutableStateOf("") }
    var currency by rememberSaveable { mutableStateOf("") }
    var serviceInterval by rememberSaveable { mutableStateOf("") }
    var powerReserve by rememberSaveable { mutableStateOf("") }
    var complications by rememberSaveable { mutableStateOf(setOf<String>()) }
    var wishlist by rememberSaveable { mutableStateOf(startOnWishlist) }
    var targetPrice by rememberSaveable { mutableStateOf("") }
    var link by rememberSaveable { mutableStateOf("") }
    val photo = rememberPhotoPickerState(initialPath = null, prefix = "watch")
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(watchId) {
        // After a fold/recreation `original` must be re-fetched (save/status/delete need it),
        // but the form fields are only filled on the very first load: `loaded` comes back true
        // through rememberSaveable, so a restored, half-typed form isn't clobbered by the DB.
        val firstLoad = !loaded
        if (watchId != 0L) app.watches.watch(watchId)?.let { w ->
            original = w
            if (firstLoad) {
                brand = w.brand; model = w.model; nickname = w.nickname; reference = w.reference; movement = w.movement
                caseSize = w.caseSizeMm?.toString()?.removeSuffix(".0") ?: ""; color = w.color; notes = w.notes
                lugWidth = w.lugWidthMm?.toString() ?: ""; purchasePrice = w.purchasePrice?.let { "%.0f".format(it) } ?: ""
                purchaseDate = w.purchaseDay?.let { java.time.LocalDate.ofEpochDay(it).toString() } ?: ""; currentValue = w.currentValue?.let { "%.0f".format(it) } ?: ""
                currency = w.currency; serviceInterval = if (w.serviceIntervalMonths > 0) w.serviceIntervalMonths.toString() else ""
                powerReserve = if (w.powerReserveHours > 0) w.powerReserveHours.toString() else ""; complications = w.complicationSet
                wishlist = w.status == Watch.STATUS_WISHLIST; targetPrice = w.targetPrice?.let { "%.0f".format(it) } ?: ""; link = w.link
            }
            if (photo.photoPath == null) photo.photoPath = w.photoPath
        }
        loaded = true
    }

    if (photoPickerCamera(app, photo)) return

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PromptLine(if (watchId == 0L) (if (wishlist) "wish add" else "watch add") else "watch edit")
        if (!loaded) { Comment("loading…"); return@Column }
        if (watchId == 0L || original?.status == Watch.STATUS_WISHLIST) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (!wishlist) "(•) in the collection" else "( ) in the collection", color = if (!wishlist) p.cyan else p.fgDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { wishlist = false })
                Text(if (wishlist) "(•) ☆ wishlist" else "( ) ☆ wishlist", color = if (wishlist) p.yellow else p.fgDim, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { wishlist = true })
            }
        }

        // Profile photo: camera, recent photos or any image file
        PhotoPickerRow(app, photo, placeholder = "⌚", placeholderColor = p.named(color))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TermTextField(value = brand, onValueChange = { brand = it }, label = "brand", placeholder = "Seiko", modifier = Modifier.weight(1f))
            TermTextField(value = model, onValueChange = { model = it }, label = "model", placeholder = "SKX007", modifier = Modifier.weight(1f))
        }
        TermTextField(value = nickname, onValueChange = { nickname = it }, label = "nickname", placeholder = "optional, e.g. the diver")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TermTextField(value = reference, onValueChange = { reference = it }, label = "reference", placeholder = "ref no.", modifier = Modifier.weight(1f))
            TermTextField(value = caseSize, onValueChange = { caseSize = it.filter { c -> c.isDigit() || c == '.' }.take(5) }, label = "case mm", placeholder = "40", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
        }
        Column {
            Text("movement:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                movements.take(3).forEach { m -> MoveChip(m, movement == m) { movement = if (movement == m) "" else m } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                movements.drop(3).forEach { m -> MoveChip(m, movement == m) { movement = if (movement == m) "" else m } }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            TermTextField(value = powerReserve, onValueChange = { powerReserve = it.filter { c -> c.isDigit() }.take(4) }, label = "power reserve (h)", placeholder = if (movement == "manual" || movement == "automatic") "42" else "–", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
            Column(Modifier.weight(1.4f)) {
                Text("complications:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Uptime.COMPLICATIONS.forEach { c ->
                        val on = c in complications
                        Text(if (on) "[$c]" else " $c ", color = if (on) p.cyan else p.fgDim, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.clickable { complications = if (on) complications - c else complications + c }.padding(vertical = 4.dp))
                    }
                }
            }
        }
        Comment("`uptime` tells you which watches have stopped and what to set before wearing them")
        Column {
            Text("accent:", color = p.fgDim, style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                Palettes.colorNames.forEach { c ->
                    val col = p.named(c)
                    Box(Modifier.size(28.dp).background(col, RoundedCornerShape(4.dp)).border(if (color == c) 2.dp else 0.dp, if (color == c) p.fg else col, RoundedCornerShape(4.dp)).clickable { color = c },
                        contentAlignment = Alignment.Center) { if (color == c) Text("✓", color = p.bg, fontWeight = FontWeight.Bold) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TermTextField(value = lugWidth, onValueChange = { lugWidth = it.filter { c -> c.isDigit() }.take(2) }, label = "lug mm", placeholder = "20", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
            TermTextField(value = serviceInterval, onValueChange = { serviceInterval = it.filter { c -> c.isDigit() }.take(3) }, label = "service every (months)", placeholder = "60", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f))
        }
        if (wishlist) TerminalPanel(title = "wishlist", titleColor = p.yellow) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TermTextField(value = targetPrice, onValueChange = { targetPrice = it.filter { c -> c.isDigit() || c == '.' } }, label = "target price", placeholder = "4500", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1.3f))
                TermTextField(value = currency, onValueChange = { currency = it.uppercase().take(3) }, label = "cur", placeholder = "AUD", modifier = Modifier.weight(0.7f))
            }
            TermTextField(value = link, onValueChange = { link = it.trim() }, label = "link", placeholder = "https://…", keyboardType = KeyboardType.Uri)
            Comment("`save 200 <watch>` grows the fund · `watch buy <watch>` moves it into the collection")
        }
        else TerminalPanel(title = "purchase & valuation", titleColor = p.yellow) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TermTextField(value = purchaseDate, onValueChange = { purchaseDate = it.filter { c -> c.isDigit() || c == '-' }.take(10) }, label = "bought (yyyy-mm-dd)", placeholder = "2021-06-15", keyboardType = KeyboardType.Number, modifier = Modifier.weight(1.3f))
                TermTextField(value = currency, onValueChange = { currency = it.uppercase().take(3) }, label = "cur", placeholder = "AUD", modifier = Modifier.weight(0.7f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TermTextField(value = purchasePrice, onValueChange = { purchasePrice = it.filter { c -> c.isDigit() || c == '.' } }, label = "paid", placeholder = "1200", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
                TermTextField(value = currentValue, onValueChange = { currentValue = it.filter { c -> c.isDigit() || c == '.' } }, label = "current value", placeholder = "1500", keyboardType = KeyboardType.Decimal, modifier = Modifier.weight(1f))
            }
            Comment("drives cost-per-wear, collection value and service reminders")
        }
        TermTextField(value = notes, onValueChange = { notes = it }, label = "notes", placeholder = "service history, strap, story…", singleLine = false, imeAction = ImeAction.Default)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TermButton(if (watchId == 0L) (if (wishlist) "add to wishlist" else "add to collection") else "save", filled = true, color = p.cyan, modifier = Modifier.weight(1f), enabled = brand.isNotBlank() || model.isNotBlank() || nickname.isNotBlank(), onClick = {
                scope.launch {
                    val w = (original ?: Watch(brand = "", model = "")).copy(
                        brand = brand.trim(), model = model.trim(), nickname = nickname.trim(), reference = reference.trim(), movement = movement,
                        caseSizeMm = caseSize.toFloatOrNull(), color = color, notes = notes.trim(), photoPath = photo.photoPath,
                        lugWidthMm = lugWidth.toIntOrNull(), purchasePrice = purchasePrice.toDoubleOrNull(), currentValue = currentValue.toDoubleOrNull(),
                        purchaseDay = runCatching { java.time.LocalDate.parse(purchaseDate).toEpochDay() }.getOrNull(), currency = currency.trim(),
                        serviceIntervalMonths = serviceInterval.toIntOrNull() ?: 0,
                        powerReserveHours = powerReserve.toIntOrNull() ?: 0, complications = Uptime.COMPLICATIONS.filter { it in complications }.joinToString(","),
                        targetPrice = targetPrice.toDoubleOrNull(), link = link,
                        status = when {
                            wishlist -> Watch.STATUS_WISHLIST
                            original?.status == Watch.STATUS_WISHLIST -> Watch.STATUS_OWNED // toggled off the wishlist = acquired
                            else -> original?.status ?: Watch.STATUS_OWNED
                        },
                        statusDay = if (original == null || wishlist != (original?.status == Watch.STATUS_WISHLIST)) AppClock.today().toEpochDay() else original!!.statusDay,
                    )
                    app.watches.saveWatch(w)
                    app.habits.mutations.value = System.currentTimeMillis()
                    nav.popBackStack()
                }
            })
            TermButton("cancel", color = p.fgDim, onClick = { nav.popBackStack() })
        }
        if (original != null) {
            TerminalPanel(title = "danger zone", titleColor = p.red, borderColor = p.red.copy(alpha = 0.5f)) {
                if (!confirmDelete) TermButton("rm watch", color = p.red, onClick = { confirmDelete = true })
                else TermButton("confirm delete (removes wear logs)", color = p.red, filled = true, onClick = {
                    scope.launch { app.watches.deleteWatch(original!!); nav.popBackStack(dev.personalterminal.ui.navigation.Routes.WATCHES, false) }
                })
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MoveChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val p = Term.palette
    Text(
        label, color = if (selected) p.bg else p.fgDim, style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(if (selected) p.cyan else p.bgAlt, RoundedCornerShape(4.dp)).border(1.dp, if (selected) p.cyan else p.border, RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 5.dp),
    )
}
