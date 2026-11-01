package dev.personalterminal.data.repo

import dev.personalterminal.domain.AppClock
import android.content.Context
import dev.personalterminal.data.db.AppDatabase
import dev.personalterminal.data.db.Watch
import dev.personalterminal.data.db.WatchCount
import dev.personalterminal.data.db.WatchPhoto
import dev.personalterminal.data.db.WatchDocument
import dev.personalterminal.data.db.WearLog
import dev.personalterminal.data.db.WearLogWithWatch
import dev.personalterminal.data.db.WatchService
import dev.personalterminal.data.db.AccuracyReading
import dev.personalterminal.data.db.Strap
import dev.personalterminal.data.db.StrapSwap
import dev.personalterminal.data.db.displayName
import dev.personalterminal.data.db.owned
import dev.personalterminal.domain.Rotation
import dev.personalterminal.domain.Uptime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Watches, wear logs and the on-disk photo store (filesDir/watch_photos). */
class WatchRepository(private val context: Context, private val db: AppDatabase) {
    /** 7.3 sync journal — null in tests; wired by the app on creation. */
    var journal: dev.personalterminal.sync.SyncJournal? = null

    private val watchDao = db.watchDao()
    private val wearDao = db.wearLogDao()
    private val serviceDao = db.watchServiceDao()
    private val accuracyDao = db.accuracyDao()
    private val strapDao = db.strapDao()
    private val swapDao = db.strapSwapDao()

    val photoDir: File get() = File(context.filesDir, PHOTO_DIR).apply { mkdirs() }

    /** The collection: owned or away for service. Sold and wishlist pieces are excluded. */
    fun observeWatches(): Flow<List<Watch>> = watchDao.observeActive()
    fun observeAllWatches(): Flow<List<Watch>> = watchDao.observeAll()
    fun observeWishlist(): Flow<List<Watch>> = watchDao.observeByStatus(Watch.STATUS_WISHLIST)
    fun observeSold(): Flow<List<Watch>> = watchDao.observeByStatus(Watch.STATUS_SOLD)
    fun observeWatch(id: Long): Flow<Watch?> = watchDao.observeById(id)
    fun observeWearCounts(): Flow<List<WatchCount>> = watchDao.observeWearCounts()
    fun observeWearForDay(date: LocalDate): Flow<List<WearLogWithWatch>> = wearDao.observeForDay(date.toEpochDay())
    fun observeWearRange(from: LocalDate, to: LocalDate): Flow<List<WearLogWithWatch>> =
        wearDao.observeRange(from.toEpochDay(), to.toEpochDay())
    fun observeAllWear(): Flow<List<WearLogWithWatch>> = wearDao.observeAllWithWatch()
    fun observeWearForWatch(watchId: Long): Flow<List<WearLogWithWatch>> = wearDao.observeForWatch(watchId)

    suspend fun watch(id: Long): Watch? = watchDao.getById(id)
    suspend fun allWatches(): List<Watch> = watchDao.getAll()
    /** Non-archived watches currently in the collection (owned / in repair). */
    suspend fun ownedWatches(): List<Watch> = watchDao.getAll().filter { it.owned }
    suspend fun allWear(): List<WearLog> = wearDao.getAll()

    // ------------------------------------------------------------------ lifecycle (0.3.6)

    /** Send a watch away for service: status → repair from [date]; a `service` log entry is created when [logService]. */
    suspend fun sendForRepair(watch: Watch, date: LocalDate = AppClock.today(), note: String = "", logService: Boolean = true) {
        watchDao.update(watch.copy(status = Watch.STATUS_REPAIR, statusDay = date.toEpochDay()))
        if (logService) serviceDao.insert(WatchService(watchId = watch.id, day = date.toEpochDay(), kind = "service", notes = note.ifBlank { "dropped off" },
            nextDueDay = if (watch.serviceIntervalMonths > 0) date.plusMonths(watch.serviceIntervalMonths.toLong()).toEpochDay() else null))
    }

    /** Back from service: status → owned. Optionally records the invoice on the drop-off entry. */
    suspend fun backFromRepair(watch: Watch, date: LocalDate = AppClock.today(), cost: Double? = null, note: String = "") {
        watchDao.update(watch.copy(status = Watch.STATUS_OWNED, statusDay = date.toEpochDay()))
        if (cost != null || note.isNotBlank()) {
            val open = serviceDao.getAll().filter { it.watchId == watch.id && it.kind == "service" }.maxByOrNull { it.day }
            if (open != null && open.day >= watch.statusDay) serviceDao.update(open.copy(cost = cost ?: open.cost, notes = listOf(open.notes, note).filter { it.isNotBlank() }.joinToString(" · ")))
            else serviceDao.insert(WatchService(watchId = watch.id, day = date.toEpochDay(), kind = "service", cost = cost, notes = note))
        }
    }

    /** Sold: keeps the history (wear log, photos, service) but leaves the collection; realised gain = price − paid. */
    suspend fun markSold(watch: Watch, price: Double?, date: LocalDate = AppClock.today()) {
        watchDao.update(watch.copy(status = Watch.STATUS_SOLD, statusDay = date.toEpochDay(), soldPrice = price))
        strapDao.getAll().filter { it.watchId == watch.id }.forEach { fitStrap(it, null, date, "watch sold") }
    }

    /** Un-sell / un-wish: the watch is back in the collection as owned from [date]. */
    suspend fun markOwned(watch: Watch, date: LocalDate = AppClock.today(), paid: Double? = watch.purchasePrice) {
        watchDao.update(watch.copy(status = Watch.STATUS_OWNED, statusDay = date.toEpochDay(), soldPrice = null,
            purchasePrice = paid ?: watch.purchasePrice, purchaseDay = watch.purchaseDay ?: date.toEpochDay()))
    }

    /** Wishlist entry → owned watch: the target price becomes the price paid unless [paid] is given. */
    suspend fun acquire(watch: Watch, paid: Double? = null, date: LocalDate = AppClock.today()) =
        markOwned(watch.copy(purchaseDay = date.toEpochDay(), targetPrice = watch.targetPrice), date, paid ?: watch.targetPrice)

    /** `save 200 bb58` – add to the fund of a wishlist watch (negative amounts withdraw; clamps at 0). */
    suspend fun addSavings(watch: Watch, amount: Double) =
        watchDao.update(watch.copy(savedSoFar = (watch.savedSoFar + amount).coerceAtLeast(0.0)))

    data class Lifecycle(val inRepair: List<Watch>, val wishlist: List<Watch>, val sold: List<Watch>) {
        val realisedGain: Double get() = sold.sumOf { (it.soldPrice ?: 0.0) - (it.purchasePrice ?: 0.0) }
        /** `next: BB58 · 62% funded` – the most-funded wishlist entry with a target. */
        val nextUp: Pair<Watch, Int>? get() = wishlist.filter { (it.targetPrice ?: 0.0) > 0 }
            .map { it to ((it.savedSoFar / it.targetPrice!!) * 100).toInt().coerceIn(0, 100) }.maxByOrNull { it.second }
    }

    suspend fun lifecycle(): Lifecycle {
        val all = watchDao.getAll().filter { !it.archived }
        return Lifecycle(
            inRepair = all.filter { it.status == Watch.STATUS_REPAIR },
            wishlist = all.filter { it.status == Watch.STATUS_WISHLIST }.sortedByDescending { it.savedSoFar / (it.targetPrice ?: Double.MAX_VALUE) },
            sold = all.filter { it.status == Watch.STATUS_SOLD }.sortedByDescending { it.statusDay },
        )
    }

    // ------------------------------------------------------------------ uptime + rotation

    /** Power-reserve status of every eligible watch (mechanical with a reserve, or a moon phase). */
    suspend fun uptime(now: java.time.LocalDateTime = AppClock.now()): List<Uptime.Status> {
        val watches = Uptime.eligible(watchDao.getAll())
        if (watches.isEmpty()) return emptyList()
        val lastWorn = wearDao.getAll().groupBy { it.watchId }.mapValues { (_, l) -> LocalDate.ofEpochDay(l.maxOf { it.day }) }
        return watches.map { Uptime.status(it, lastWorn[it.id], now) }
    }

    suspend fun challenges(today: LocalDate = AppClock.today()): List<Rotation.Challenge> =
        Rotation.challenges(watchDao.getAll(), wearDao.getAll(), today)

    suspend fun saveWatch(watch: Watch): Long =
        if (watch.id == 0L) watchDao.insert(watch) else { watchDao.update(watch); watch.id }

    suspend fun deleteWatch(watch: Watch) = withContext(Dispatchers.IO) {
        // remove photos belonging to this watch's logs + profile photo
        wearDao.getAll().filter { it.watchId == watch.id }.forEach { it.photoPath?.let { p -> deletePhoto(p) } }
        watch.photoPath?.let { deletePhoto(it) }
        watchDao.delete(watch)
    }

    suspend fun logWear(watchId: Long, date: LocalDate, photoPath: String? = null, note: String = "", strapId: Long? = null): Long {
        // one entry per watch per day – logging twice just refreshes the note/strap
        wearDao.getForWatchAndDay(watchId, date.toEpochDay()).firstOrNull()?.let { existing ->
            wearDao.update(existing.copy(note = note.ifBlank { existing.note }, strapId = strapId ?: existing.strapId))
            return existing.id
        }
        val strap = strapId ?: strapDao.getAll().firstOrNull { it.watchId == watchId }?.id
        return wearDao.insert(WearLog(watchId = watchId, day = date.toEpochDay(), photoPath = photoPath, note = note, strapId = strap))
    }

    suspend fun updateWear(log: WearLog) = wearDao.update(log)

    /**
     * Attach (or replace) the wrist shot of an existing wear log. The previous photo file, if any,
     * is deleted so the photo store never accumulates orphans.
     */
    suspend fun setWearPhoto(log: WearLog, photoPath: String?) = withContext(Dispatchers.IO) {
        if (log.photoPath != null && log.photoPath != photoPath) deletePhoto(log.photoPath)
        wearDao.update(log.copy(photoPath = photoPath))
    }

    /**
     * "Add a wrist shot for this watch on [date]": reuses today's existing log for the watch when
     * there is one (so a second shot replaces the first instead of creating a duplicate wear day),
     * otherwise logs a new wear with the photo. Returns the affected log id.
     */
    suspend fun addWristShot(watchId: Long, date: LocalDate, photoPath: String, note: String = ""): Long {
        val existing = wearDao.getForWatchAndDay(watchId, date.toEpochDay()).firstOrNull()
        return if (existing != null) {
            setWearPhoto(existing.copy(note = existing.note.ifBlank { note }), photoPath)
            existing.id
        } else logWear(watchId, date, photoPath, note)
    }

    suspend fun deleteWear(log: WearLog) = withContext(Dispatchers.IO) {
        log.photoPath?.let { deletePhoto(it) }
        wearDao.delete(log)
    }

    suspend fun wearLog(id: Long): WearLog? = wearDao.getById(id)

    /** Returns the absolute file for a stored relative photo path. */
    fun photoFile(relative: String): File = File(photoDir, relative)

    /** Creates a fresh destination file for a new photo. Returns the *relative* name. */
    fun newPhotoName(prefix: String = "wrist"): String = "${prefix}_${System.currentTimeMillis()}.jpg"

    private fun deletePhoto(relative: String) {
        runCatching { photoFile(relative).takeIf { it.exists() }?.delete() }
    }

    // ------------------------------------------------------------------ collection stats

    data class WatchStats(
        val watch: Watch,
        val wearDays: Int,
        val share: Float,
        val lastWorn: LocalDate?,
        val daysSinceWorn: Int?,
        val costPerWear: Double?,
    )

    data class CollectionStats(
        val perWatch: List<WatchStats>,
        val totalWearDays: Int,
        val daysCovered: Int,
        val totalPaid: Double,
        val totalValue: Double,
        val currency: String,
    ) {
        /** Watches not worn for 30+ days (or never), most neglected first. */
        val neglected: List<WatchStats> get() = perWatch.filter { (it.daysSinceWorn ?: Int.MAX_VALUE) >= 30 }.sortedByDescending { it.daysSinceWorn ?: Int.MAX_VALUE }
    }

    fun observeCollectionStats(today: LocalDate = AppClock.today()): Flow<CollectionStats> =
        kotlinx.coroutines.flow.combine(watchDao.observeActive(), wearDao.observeAllWithWatch()) { watches, logs ->
            collectionStats(watches, logs.map { it.log }, today)
        }

    suspend fun collectionStats(today: LocalDate = AppClock.today()): CollectionStats =
        collectionStats(watchDao.getAll().filter { it.owned }, wearDao.getAll(), today)

    private fun collectionStats(watches: List<Watch>, allLogs: List<WearLog>, today: LocalDate): CollectionStats {
        val ids = watches.map { it.id }.toSet()
        val logs = allLogs.filter { it.watchId in ids }
        val total = logs.map { it.day }.distinct().size
        val perWatch = watches.map { w ->
            val mine = logs.filter { it.watchId == w.id }
            val days = mine.map { it.day }.distinct()
            val last = days.maxOrNull()?.let { LocalDate.ofEpochDay(it) }
            WatchStats(
                watch = w, wearDays = days.size,
                share = if (logs.isEmpty()) 0f else days.size.toFloat() / logs.map { it.day }.size,
                lastWorn = last, daysSinceWorn = last?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today).toInt() },
                costPerWear = w.purchasePrice?.takeIf { days.isNotEmpty() }?.let { it / days.size },
            )
        }.sortedByDescending { it.wearDays }
        val first = logs.minOfOrNull { it.day }
        return CollectionStats(
            perWatch = perWatch, totalWearDays = total,
            daysCovered = first?.let { (today.toEpochDay() - it + 1).toInt() } ?: 0,
            totalPaid = watches.sumOf { it.purchasePrice ?: 0.0 }, totalValue = watches.sumOf { it.currentValue ?: it.purchasePrice ?: 0.0 },
            currency = watches.firstNotNullOfOrNull { it.currency.takeIf { c -> c.isNotBlank() } } ?: "",
        )
    }

    /**
     * `watch next`: rotation suggestion. Scores each watch by days since last worn (dominant),
     * a penalty when it was worn yesterday, and a small bonus for the least-worn pieces overall.
     * Returns the pick plus a one-line reason.
     */
    suspend fun suggestNext(today: LocalDate = AppClock.today()): Pair<Watch, String>? {
        val stats = collectionStats(today).perWatch.filter { it.watch.status != Watch.STATUS_REPAIR }
        if (stats.isEmpty()) return null
        val maxWear = stats.maxOf { it.wearDays }.coerceAtLeast(1)
        val scored = stats.map { s ->
            val since = s.daysSinceWorn ?: 365
            val neglect = since.coerceAtMost(365).toDouble()
            val underworn = (1.0 - s.wearDays.toDouble() / maxWear) * 10
            val yesterday = if (since <= 1) -50.0 else 0.0
            s to (neglect + underworn + yesterday)
        }.sortedByDescending { it.second }
        val (pick, _) = scored.first()
        val reason = when {
            pick.lastWorn == null -> "never worn yet"
            pick.daysSinceWorn!! >= 30 -> "neglected for ${pick.daysSinceWorn} days"
            else -> "last worn ${pick.daysSinceWorn} days ago · ${pick.wearDays} wears total"
        }
        return pick.watch to reason
    }

    // ------------------------------------------------------------------ memories

    data class Memory(val log: WearLogWithWatch, val label: String, val monthsAgo: Int)

    /**
     * "On this day": wrist shots taken 1, 3, 6, 12, 24 … months before [today] (±[toleranceDays] so a
     * memory is not lost because the exact day had no photo). One entry per horizon, newest first.
     */
    suspend fun memories(today: LocalDate = AppClock.today(), toleranceDays: Long = 3): List<Memory> {
        val withPhoto = wearDao.getAll().filter { it.photoPath != null }
        if (withPhoto.isEmpty()) return emptyList()
        val byId = watchDao.getAll().associateBy { it.id }
        val horizons = listOf(1, 3, 6, 12, 24, 36, 48, 60)
        val out = mutableListOf<Memory>()
        for (m in horizons) {
            val anchor = today.minusMonths(m.toLong()).toEpochDay()
            val pick = withPhoto.filter { kotlin.math.abs(it.day - anchor) <= toleranceDays }.minByOrNull { kotlin.math.abs(it.day - anchor) * 10 + (if (it.day == anchor) 0 else 1) } ?: continue
            val w = byId[pick.watchId] ?: continue
            val label = when (m) { 1 -> "1 month ago"; 12 -> "1 year ago"; 24 -> "2 years ago"; 36 -> "3 years ago"; 48 -> "4 years ago"; 60 -> "5 years ago"; else -> "$m months ago" }
            if (out.none { it.log.log.id == pick.id }) out += Memory(WearLogWithWatch(pick, w), label, m)
        }
        return out
    }

    // ------------------------------------------------------------------ service log

    fun observeServices(watchId: Long): Flow<List<WatchService>> = serviceDao.observeForWatch(watchId)
    fun observeAllServices(): Flow<List<WatchService>> = serviceDao.observeAll()
    suspend fun saveService(s: WatchService): Long = serviceDao.insert(s)
    suspend fun deleteService(s: WatchService) = serviceDao.delete(s)
    suspend fun servicesDueBy(day: LocalDate): List<WatchService> = serviceDao.dueBy(day.toEpochDay())

    /** Next service due for a watch: explicit `nextDueDay` of the latest entry, else last service + interval. */
    suspend fun nextServiceDue(watch: Watch): LocalDate? {
        val all = serviceDao.getAll().filter { it.watchId == watch.id && it.kind == "service" }.sortedByDescending { it.day }
        val last = all.firstOrNull()
        last?.nextDueDay?.let { return LocalDate.ofEpochDay(it) }
        if (watch.serviceIntervalMonths <= 0) return null
        val base = last?.let { LocalDate.ofEpochDay(it.day) } ?: watch.purchaseDay?.let { LocalDate.ofEpochDay(it) } ?: return null
        return base.plusMonths(watch.serviceIntervalMonths.toLong())
    }

    // ------------------------------------------------------------------ accuracy / drift

    data class Drift(val secondsPerDay: Float?, val readings: Int, val latestOffset: Float?, val spanDays: Float)

    fun observeAccuracy(watchId: Long): Flow<List<AccuracyReading>> = accuracyDao.observeForWatch(watchId)
    suspend fun addReading(r: AccuracyReading): Long = accuracyDao.insert(r)
    suspend fun deleteReading(r: AccuracyReading) = accuracyDao.delete(r)
    suspend fun resetAccuracy(watchId: Long) = accuracyDao.deleteForWatch(watchId)

    /** Least-squares slope of offset over time → seconds per day. Needs ≥2 readings ≥ 6 h apart. */
    fun drift(readings: List<AccuracyReading>): Drift {
        val sorted = readings.sortedBy { it.measuredAt }
        if (sorted.size < 2) return Drift(null, sorted.size, sorted.lastOrNull()?.offsetSeconds, 0f)
        val t0 = sorted.first().measuredAt
        val xs = sorted.map { (it.measuredAt - t0) / 86_400_000.0 }
        val ys = sorted.map { it.offsetSeconds.toDouble() }
        val span = xs.last()
        if (span < 0.25) return Drift(null, sorted.size, sorted.last().offsetSeconds, span.toFloat())
        val mx = xs.average(); val my = ys.average()
        val num = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        val den = xs.sumOf { (it - mx) * (it - mx) }
        val slope = if (den == 0.0) 0.0 else num / den
        return Drift(slope.toFloat(), sorted.size, sorted.last().offsetSeconds, span.toFloat())
    }

    // ------------------------------------------------------------------ straps

    fun observeStraps(): Flow<List<Strap>> = strapDao.observeAll()
    fun observeStrapCounts(): Flow<List<WatchCount>> = wearDao.observeStrapCounts()
    suspend fun straps(): List<Strap> = strapDao.getAll()
    suspend fun saveStrap(s: Strap): Long = strapDao.insert(s)
    suspend fun deleteStrap(s: Strap) { swapDao.deleteForStrap(s.id); strapDao.delete(s) }
    /**
     * Fit [strap] to [watchId] (null = back in the drawer). Only one strap per watch at a time; the
     * displaced strap goes to the drawer. Every change is appended to the swap log, and today's wear
     * entry for the watch inherits the new strap.
     */
    suspend fun fitStrap(strap: Strap, watchId: Long?, date: LocalDate = AppClock.today(), note: String = "") {
        if (strap.watchId == watchId) return
        val day = date.toEpochDay()
        if (watchId != null) strapDao.getAll().filter { it.watchId == watchId && it.id != strap.id }.forEach {
            strapDao.update(it.copy(watchId = null))
            swapDao.insert(StrapSwap(strapId = it.id, watchId = null, day = day, note = "replaced by ${strap.name}"))
        }
        strapDao.update(strap.copy(watchId = watchId))
        swapDao.insert(StrapSwap(strapId = strap.id, watchId = watchId, day = day, note = note))
        if (watchId != null) wearDao.getForWatchAndDay(watchId, day).firstOrNull()?.let { wearDao.update(it.copy(strapId = strap.id)) }
    }

    fun observeStrapSwaps(limit: Int = 30): Flow<List<StrapSwap>> = swapDao.observeRecent(limit)
    suspend fun strapSwaps(strapId: Long): List<StrapSwap> = swapDao.getForStrap(strapId)

    /** Days the strap has been on its current watch (null when in the drawer or never logged). */
    suspend fun strapFittedDays(strap: Strap, today: LocalDate = AppClock.today()): Int? {
        if (strap.watchId == null) return null
        val last = swapDao.latestForStrap(strap.id)?.takeIf { it.watchId == strap.watchId } ?: return null
        return (today.toEpochDay() - last.day).toInt().coerceAtLeast(0)
    }

    /** "on speedy · 23 d" style summary per strap for the straps page. */
    suspend fun strapFitSummaries(today: LocalDate = AppClock.today()): Map<Long, Int> =
        strapDao.getAll().filter { it.watchId != null }.mapNotNull { s -> strapFittedDays(s, today)?.let { s.id to it } }.toMap()

    // ------------------------------------------------------------------ CSV export

    /** RFC-4180-ish CSV of the wear log joined with watch + strap names. */
    suspend fun wearLogCsv(): String {
        val watches = watchDao.getAll().associateBy { it.id }
        val straps = strapDao.getAll().associateBy { it.id }
        val sb = StringBuilder("date,watch,brand,model,reference,strap,note,photo\n")
        wearDao.getAll().sortedBy { it.day }.forEach { l ->
            val w = watches[l.watchId]
            sb.append(listOf(
                LocalDate.ofEpochDay(l.day).toString(), w?.displayName ?: "", w?.brand ?: "", w?.model ?: "", w?.reference ?: "",
                l.strapId?.let { straps[it]?.name } ?: "", l.note, l.photoPath ?: "",
            ).joinToString(",") { csv(it) }).append('\n')
        }
        return sb.toString()
    }

    /** One row per watch with collection stats, purchase and valuation data. */
    suspend fun collectionCsv(today: LocalDate = AppClock.today()): String {
        val stats = collectionStats(today)
        val sb = StringBuilder("brand,model,nickname,reference,movement,case_mm,lug_mm,purchase_date,purchase_price,current_value,currency,wear_days,share,last_worn,cost_per_wear,service_interval_months,status,sold_price,power_reserve_h,complications\n")
        stats.perWatch.forEach { s ->
            val w = s.watch
            sb.append(listOf(
                w.brand, w.model, w.nickname, w.reference, w.movement, w.caseSizeMm?.toString() ?: "", w.lugWidthMm?.toString() ?: "",
                w.purchaseDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "", w.purchasePrice?.toString() ?: "", w.currentValue?.toString() ?: "",
                w.currency, s.wearDays.toString(), "%.3f".format(s.share), s.lastWorn?.toString() ?: "", s.costPerWear?.let { "%.2f".format(it) } ?: "",
                w.serviceIntervalMonths.toString(), w.status, "", w.powerReserveHours.toString(), w.complications,
            ).joinToString(",") { csv(it) }).append('\n')
        }
        // sold pieces keep their row (history + realised gain) below the collection
        watchDao.getAll().filter { !it.archived && it.status == Watch.STATUS_SOLD }.forEach { w ->
            sb.append(listOf(
                w.brand, w.model, w.nickname, w.reference, w.movement, w.caseSizeMm?.toString() ?: "", w.lugWidthMm?.toString() ?: "",
                w.purchaseDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "", w.purchasePrice?.toString() ?: "", w.currentValue?.toString() ?: "",
                w.currency, "", "", "", "", w.serviceIntervalMonths.toString(), w.status, w.soldPrice?.toString() ?: "", w.powerReserveHours.toString(), w.complications,
            ).joinToString(",") { csv(it) }).append('\n')
        }
        return sb.toString()
    }

    private fun csv(v: String): String = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    // ---- gallery photos & documents (0.4.0) ----

    fun observeWatchPhotos(watchId: Long): Flow<List<WatchPhoto>> = db.watchPhotoDao().observeForWatch(watchId)
    fun observeWatchDocuments(watchId: Long): Flow<List<WatchDocument>> = db.watchDocumentDao().observeForWatch(watchId)
    fun observeAllPhotos(): Flow<List<WatchPhoto>> = db.watchPhotoDao().observeAll()

    /** Copies the picked image into the watch gallery (processed like every other shot). */
    suspend fun addWatchPhoto(watchId: Long, src: android.net.Uri, kind: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val name = "extra_${watchId}_${System.currentTimeMillis()}.jpg"
            dev.personalterminal.ui.watch.importPhoto(context, src, File(photoDir, name))
            db.watchPhotoDao().insert(WatchPhoto(watchId = watchId, path = name, kind = kind))
            true
        }.getOrDefault(false)
    }

    suspend fun deleteWatchPhoto(p: WatchPhoto) = withContext(Dispatchers.IO) {
        runCatching { File(photoDir, p.path).delete() }
        db.watchPhotoDao().delete(p)
    }

    /** Copies a receipt / warranty / other document raw into `filesDir/watch_docs`. */
    suspend fun addWatchDocument(watchId: Long, src: android.net.Uri, kind: String, label: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "watch_docs").apply { mkdirs() }
            val safe = label.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { kind.ifBlank { "doc" } }
            val name = "${System.currentTimeMillis()}_$safe"
            context.contentResolver.openInputStream(src)?.use { input ->
                File(dir, name).outputStream().use { input.copyTo(it) }
            } ?: return@runCatching false
            db.watchDocumentDao().insert(WatchDocument(watchId = watchId, path = name, kind = kind, label = safe))
            true
        }.getOrDefault(false)
    }

    suspend fun deleteWatchDocument(d: WatchDocument) = withContext(Dispatchers.IO) {
        runCatching { File(context.filesDir, "watch_docs/${d.path}").delete() }
        db.watchDocumentDao().delete(d)
    }

    fun documentFile(d: WatchDocument): File = File(context.filesDir, "watch_docs/${d.path}")

    companion object {
        const val PHOTO_DIR = "watch_photos"
    }
}
