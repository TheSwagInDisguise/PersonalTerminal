package dev.personalterminal.data.backup

import android.content.Context
import dev.personalterminal.data.db.AppDatabase
import dev.personalterminal.data.db.Habit
import dev.personalterminal.data.db.HabitLog
import dev.personalterminal.data.db.PlannerNote
import dev.personalterminal.data.db.PlannerProject
import dev.personalterminal.data.db.PlannerTask
import dev.personalterminal.data.db.Routine
import dev.personalterminal.data.db.ShieldUse
import dev.personalterminal.data.db.Watch
import dev.personalterminal.data.db.WatchDocument
import dev.personalterminal.data.db.WatchPhoto
import dev.personalterminal.data.db.WearLog
import dev.personalterminal.data.db.XpEvent
import dev.personalterminal.data.db.FocusSession
import dev.personalterminal.data.db.WatchService
import dev.personalterminal.data.db.AccuracyReading
import dev.personalterminal.data.db.SkipRule
import dev.personalterminal.data.db.SleepLog
import dev.personalterminal.data.db.StrapSwap
import dev.personalterminal.data.db.Strap
import dev.personalterminal.data.prefs.NotificationPrefs
import dev.personalterminal.data.prefs.Settings
import dev.personalterminal.data.prefs.UserPrefs
import dev.personalterminal.data.repo.WatchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Serialisable snapshot of the whole database + user preferences.
 *
 * Schema 7 (0.5.0) adds the planner tables and the *full* watch media set: profile/wrist photos,
 * gallery `watch_photos` rows and `watch_documents` receipts/warranties. Every new field is
 * optional, so archives written by 0.2.0 … 0.4.2 still restore exactly as before.
 */
@Serializable
data class BackupPayload(
    val schemaVersion: Int = 7,
    val appVersion: String,
    val createdAt: Long,
    val routines: List<Routine>,
    val habits: List<Habit>,
    val habitLogs: List<HabitLog>,
    val shieldUses: List<ShieldUse>,
    val watches: List<Watch>,
    val wearLogs: List<WearLog>,
    val xpEvents: List<XpEvent>,
    val settings: BackupSettings,
    // schema 2 (0.3): optional so schema-1 archives still restore
    val focusSessions: List<FocusSession> = emptyList(),
    val watchServices: List<WatchService> = emptyList(),
    val accuracyReadings: List<AccuracyReading> = emptyList(),
    val straps: List<Strap> = emptyList(),
    // schema 3 (0.3.2): streak-insurance rules
    val skipRules: List<SkipRule> = emptyList(),
    // schema 4 (0.3.4): sleep anchors + strap swap history
    val sleepLogs: List<SleepLog> = emptyList(),
    val strapSwaps: List<StrapSwap> = emptyList(),
    // schema 7 (0.5.0): full watch media rows
    val watchPhotos: List<WatchPhoto> = emptyList(),
    val watchDocuments: List<WatchDocument> = emptyList(),
    // schema 7 (0.5.0): planner — projects, one-off tasks, Markdown notes
    val plannerProjects: List<PlannerProject> = emptyList(),
    val plannerTasks: List<PlannerTask> = emptyList(),
    val plannerNotes: List<PlannerNote> = emptyList(),
)

@Serializable
data class BackupSettings(
    val themeName: String,
    val themeMode: String,
    val username: String,
    val hostname: String,
    val pomodoroFocusMin: Int,
    val pomodoroBreakMin: Int,
    val pomodoroLongBreakMin: Int,
    val fontName: String = "jetbrains",
    val customPaletteJson: String = "",
    val quietStartMin: Int = 22 * 60,
    val quietEndMin: Int = 7 * 60,
    val remindersEnabled: Boolean = true,
    val crtEffect: Boolean = false,
    val accessibilityMode: Boolean = false,
    val notifications: BackupNotifications? = null,
) {
    companion object {
        fun from(s: Settings) = BackupSettings(
            s.themeName, s.themeMode.name, s.username, s.hostname, s.pomodoroFocusMin, s.pomodoroBreakMin, s.pomodoroLongBreakMin,
            s.fontName, s.customPaletteJson, s.quietStartMin, s.quietEndMin, s.remindersEnabled, s.crtEffect, s.accessibilityMode,
            BackupNotifications.from(s.notifications),
        )
    }
}

@Serializable
data class BackupNotifications(
    val habitReminders: Boolean = true,
    val habitCheckIn: Boolean = true,
    val checkInMinutes: Int = 20 * 60,
    val wearLog: Boolean = false,
    val wearLogMinutes: Int = 9 * 60,
    val watchService: Boolean = true,
    val timerAlerts: Boolean = true,
    val streakRisk: Boolean = true,
    val streakRiskMinutes: Int = 21 * 60,
    val streakRiskMinStreak: Int = 3,
    val weeklyReview: Boolean = false,
    val weeklyReviewMinutes: Int = 18 * 60,
    val morningBriefing: Boolean = false,
    val morningBriefingMinutes: Int = 7 * 60 + 30,
) {
    fun toPrefs() = NotificationPrefs(habitReminders, habitCheckIn, checkInMinutes, wearLog, wearLogMinutes, watchService, timerAlerts, streakRisk, streakRiskMinutes, streakRiskMinStreak, weeklyReview, weeklyReviewMinutes, morningBriefing, morningBriefingMinutes)
    companion object {
        fun from(n: NotificationPrefs) = BackupNotifications(n.habitReminders, n.habitCheckIn, n.checkInMinutes, n.wearLog, n.wearLogMinutes, n.watchService, n.timerAlerts, n.streakRisk, n.streakRiskMinutes, n.streakRiskMinStreak, n.weeklyReview, n.weeklyReviewMinutes, n.morningBriefing, n.morningBriefingMinutes)
    }
}

/**
 * Builds and restores `.ptbak` archives: a zip containing `data.json` plus every watch photo and
 * document. New archives namespace media as `media/photos/…` and `media/docs/…`; archives written
 * before 0.5.0 (`media/<name>`) are still read as photos. The same format is used for local export
 * (SAF), Google Drive full-archive backup/restore and onboarding merge.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val prefs: UserPrefs,
    private val watchRepo: WatchRepository,
    private val appVersion: String,
) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true; encodeDefaults = true }

    val backupDir: File get() = File(context.filesDir, "backups").apply { mkdirs() }

    suspend fun buildPayload(): BackupPayload = withContext(Dispatchers.IO) {
        val s = prefs.current()
        BackupPayload(
            appVersion = appVersion,
            createdAt = System.currentTimeMillis(),
            routines = db.routineDao().getAll(),
            habits = db.habitDao().getAll(),
            habitLogs = db.habitLogDao().getAll(),
            shieldUses = db.shieldDao().getAll(),
            watches = db.watchDao().getAll(),
            wearLogs = db.wearLogDao().getAll(),
            xpEvents = db.xpDao().getAll(),
            settings = BackupSettings.from(s),
            focusSessions = db.focusSessionDao().getAll(),
            watchServices = db.watchServiceDao().getAll(),
            accuracyReadings = db.accuracyDao().getAll(),
            straps = db.strapDao().getAll(),
            skipRules = db.skipRuleDao().getAll(),
            sleepLogs = db.sleepDao().getAll(),
            strapSwaps = db.strapSwapDao().getAll(),
            watchPhotos = db.watchPhotoDao().getAll(),
            watchDocuments = db.watchDocumentDao().getAll(),
            plannerProjects = db.plannerProjectDao().getAll(),
            plannerTasks = db.plannerTaskDao().getAll(),
            plannerNotes = db.plannerNoteDao().getAll(),
        )
    }

    /**
     * Writes a full archive to [out]. Returns number of media files included. When the user set a
     * backup passphrase the zip is wrapped in [BackupCrypto]'s AES-256-GCM envelope (`.ptbakx`).
     */
    suspend fun writeArchive(out: OutputStream, passphrase: String? = null): Int = withContext(Dispatchers.IO) {
        val pass = passphrase ?: prefs.current().backupPassphrase
        if (pass.isNotBlank()) {
            val plain = java.io.ByteArrayOutputStream()
            val n = writePlainArchive(plain)
            BufferedOutputStream(out).use { it.write(BackupCrypto.encrypt(plain.toByteArray(), pass)) }
            return@withContext n
        }
        writePlainArchive(out)
    }

    private suspend fun writePlainArchive(out: OutputStream): Int = withContext(Dispatchers.IO) {
        val payload = buildPayload()
        var media = 0
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_DATA))
            zip.write(json.encodeToString(payload).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // The FULL watch media set: profile + wrist shots + gallery rows, and every document.
            val photos = (
                payload.watches.mapNotNull { it.photoPath } +
                    payload.wearLogs.mapNotNull { it.photoPath } +
                    payload.watchPhotos.map { it.path }
                ).filter { it.isNotBlank() }.map { File(it).name }.toSet()
            val docs = payload.watchDocuments.map { it.path }.filter { it.isNotBlank() }.map { File(it).name }.toSet()

            photos.forEach { name ->
                val f = watchRepo.photoFile(name)
                if (f.isFile) {
                    zip.putNextEntry(ZipEntry("$ENTRY_MEDIA_DIR$ENTRY_PHOTOS_DIR$name"))
                    BufferedInputStream(FileInputStream(f)).use { it.copyTo(zip) }
                    zip.closeEntry()
                    media++
                }
            }
            docs.forEach { name ->
                val f = watchRepo.docFile(name)
                if (f.isFile) {
                    zip.putNextEntry(ZipEntry("$ENTRY_MEDIA_DIR$ENTRY_DOCS_DIR$name"))
                    BufferedInputStream(FileInputStream(f)).use { it.copyTo(zip) }
                    zip.closeEntry()
                    media++
                }
            }
        }
        media
    }

    /** Creates an archive file in the private backups dir (used by Drive upload). */
    suspend fun createLocalArchive(): File = withContext(Dispatchers.IO) {
        // keep only the newest local archive to save space
        backupDir.listFiles()?.forEach { it.delete() }
        val file = File(backupDir, fileName(encrypted = prefs.current().backupPassphrase.isNotBlank()))
        val media = FileOutputStream(file).use { writeArchive(it) }
        prefs.setBackupStats(file.length(), media)
        file
    }

    /** What [verifyArchive] found inside an archive – nothing is written to the database. */
    data class Verification(
        val createdAt: Long, val appVersion: String, val encrypted: Boolean, val bytes: Long,
        val habits: Int, val logs: Int, val watches: Int, val wearLogs: Int, val photosReferenced: Int, val photosPresent: Int,
        val documentsReferenced: Int = 0, val documentsPresent: Int = 0,
        val plannerProjects: Int = 0, val plannerTasks: Int = 0, val plannerNotes: Int = 0,
    ) {
        val photosMissing: Int get() = photosReferenced - photosPresent
        val documentsMissing: Int get() = documentsReferenced - documentsPresent
        val mediaPresent: Int get() = photosPresent + documentsPresent
        val ok: Boolean get() = photosMissing == 0 && documentsMissing == 0
        val summary: String
            get() = "$habits habits · $logs logs · $watches watches · $photosPresent/$photosReferenced photos · " +
                "$documentsPresent/$documentsReferenced docs · $plannerTasks tasks · $plannerNotes notes" +
                (if (encrypted) " · encrypted" else "")
    }

    /**
     * Full dry-run restore: decrypts (when needed), unzips, parses the JSON with the current schema
     * and checks every referenced photo AND document is in the archive. Throws [PassphraseRequired]
     * like [restoreArchive]; any other exception means the archive is corrupt.
     */
    suspend fun verifyArchive(input: InputStream, passphrase: String? = null): Verification = withContext(Dispatchers.IO) {
        val (plain, encrypted, bytes) = plainBytes(input, passphrase)
        val (p, present) = readArchive(ByteArrayInputStream(plain), null)
        val photoRefs = referencedKeys(p).first
        val docRefs = referencedKeys(p).second
        Verification(
            createdAt = p.createdAt, appVersion = p.appVersion, encrypted = encrypted, bytes = bytes,
            habits = p.habits.size, logs = p.habitLogs.size, watches = p.watches.size, wearLogs = p.wearLogs.size,
            photosReferenced = photoRefs.size, photosPresent = photoRefs.count { it in present },
            documentsReferenced = docRefs.size, documentsPresent = docRefs.count { it in present },
            plannerProjects = p.plannerProjects.size, plannerTasks = p.plannerTasks.size, plannerNotes = p.plannerNotes.size,
        )
    }

    /** Verifies a freshly built archive of the *current* data end-to-end (write → read back). */
    suspend fun selfCheck(): Verification = withContext(Dispatchers.IO) {
        val buf = java.io.ByteArrayOutputStream()
        writeArchive(buf)
        verifyArchive(ByteArrayInputStream(buf.toByteArray()))
    }

    /** Thrown when an encrypted archive is opened without (or with the wrong) passphrase. */
    class PassphraseRequired(message: String) : Exception(message)

    /**
     * Restores an archive (plain `.ptbak` or encrypted `.ptbakx`). Replaces ALL local data.
     * Returns a short human-readable summary. Throws [PassphraseRequired] for encrypted archives
     * when [passphrase] is missing or wrong (the stored backup passphrase is tried first).
     */
    suspend fun restoreArchive(input: InputStream, passphrase: String? = null): String = withContext(Dispatchers.IO) {
        val (plain, _, _) = plainBytes(input, passphrase)
        val staged = File(context.cacheDir, "restore_media_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val (p, _) = readArchive(ByteArrayInputStream(plain), staged)
            applyPayload(p)
            val media = installMedia(staged, replace = true)
            "restored ${p.habits.size} habits, ${p.habitLogs.size} logs, ${p.watches.size} watches, " +
                "${p.plannerTasks.size} tasks, ${p.plannerNotes.size} notes, $media media files " +
                "(backup from ${fmt(p.createdAt)})"
        } finally {
            staged.deleteRecursively()
        }
    }

    /**
     * Onboarding path for an *independent install*: merge a Drive archive into the data already on
     * this device instead of replacing it. Every table is id-offset and child references are
     * remapped, so both installs' habits, watches and planner items survive. Local preferences
     * are kept; media is copied in without deleting existing files (new names win on collision).
     */
    suspend fun mergeArchive(input: InputStream, passphrase: String? = null): String = withContext(Dispatchers.IO) {
        val (plain, _, _) = plainBytes(input, passphrase)
        val staged = File(context.cacheDir, "merge_media_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val (p, _) = readArchive(ByteArrayInputStream(plain), staged)
            val counts = mergePayload(p)
            val media = installMedia(staged, replace = false)
            "merged $counts, $media media files (archive from ${fmt(p.createdAt)})"
        } finally {
            staged.deleteRecursively()
        }
    }

    /** Decrypt when needed. Returns plain zip bytes, the encrypted flag and the original size. */
    private suspend fun plainBytes(input: InputStream, passphrase: String?): Triple<ByteArray, Boolean, Long> {
        val bytes = input.readBytes()
        val encrypted = bytes.size >= BackupCrypto.MAGIC.size && bytes.copyOf(BackupCrypto.MAGIC.size).contentEquals(BackupCrypto.MAGIC)
        val plain = if (encrypted) {
            val candidates = listOfNotNull(passphrase, prefs.current().backupPassphrase.takeIf { it.isNotBlank() })
            if (candidates.isEmpty()) throw PassphraseRequired("archive is encrypted – enter the backup passphrase")
            candidates.firstNotNullOfOrNull { p -> runCatching { BackupCrypto.decrypt(bytes, p) }.getOrNull() }
                ?: throw PassphraseRequired("wrong passphrase for this archive")
        } else bytes
        return Triple(plain, encrypted, bytes.size.toLong())
    }

    /** Reads `data.json` and every media entry. When [staged] is null the media is only audited. */
    private fun readArchive(plain: InputStream, staged: File?): Pair<BackupPayload, Set<String>> {
        var payload: BackupPayload? = null
        val present = mutableSetOf<String>()
        ZipInputStream(BufferedInputStream(plain)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val key = mediaKey(entry.name)
                when {
                    entry.name == ENTRY_DATA -> payload = json.decodeFromString(BackupPayload.serializer(), zip.readBytes().toString(Charsets.UTF_8))
                    key != null && !entry.isDirectory -> {
                        present += key
                        if (staged != null) {
                            val dir = if (key.startsWith("$ENTRY_DOCS_DIR")) File(staged, ENTRY_DOCS_DIR) else File(staged, ENTRY_PHOTOS_DIR)
                            dir.mkdirs()
                            val name = File(entry.name).name // flatten – no traversal
                            FileOutputStream(File(dir, name)).use { zip.copyTo(it) }
                        } else zip.readBytes()
                    }
                    else -> zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return (payload ?: error("archive does not contain $ENTRY_DATA")) to present
    }

    private fun referencedKeys(p: BackupPayload): Pair<Set<String>, Set<String>> {
        val photos = (
            p.watches.mapNotNull { it.photoPath } +
                p.wearLogs.mapNotNull { it.photoPath } +
                p.watchPhotos.map { it.path }
            ).filter { it.isNotBlank() }.map { "$ENTRY_PHOTOS_DIR${File(it).name}" }.toSet()
        val docs = p.watchDocuments.map { it.path }.filter { it.isNotBlank() }.map { "$ENTRY_DOCS_DIR${File(it).name}" }.toSet()
        return photos to docs
    }

    /** Legacy `media/<name>` entries are photos; new archives namespace both kinds. */
    private fun mediaKey(entryName: String): String? = when {
        entryName.startsWith("$ENTRY_MEDIA_DIR$ENTRY_PHOTOS_DIR") && !entryName.isDirectoryPath -> "$ENTRY_PHOTOS_DIR${File(entryName).name}"
        entryName.startsWith("$ENTRY_MEDIA_DIR$ENTRY_DOCS_DIR") && !entryName.isDirectoryPath -> "$ENTRY_DOCS_DIR${File(entryName).name}"
        entryName.startsWith(ENTRY_MEDIA_DIR) && !entryName.isDirectoryPath -> "$ENTRY_PHOTOS_DIR${File(entryName).name}"
        else -> null
    }

    private val String.isDirectoryPath: Boolean get() = endsWith("/")

    /** Copies staged media into the two content dirs. Returns the number of files installed. */
    private fun installMedia(staged: File, replace: Boolean): Int {
        if (replace) {
            watchRepo.photoDir.listFiles()?.forEach { it.delete() }
            watchRepo.docDir.listFiles()?.forEach { it.delete() }
        }
        var media = 0
        File(staged, ENTRY_PHOTOS_DIR).listFiles()?.forEach { f ->
            f.copyTo(File(watchRepo.photoDir, f.name), overwrite = true); media++
        }
        File(staged, ENTRY_DOCS_DIR).listFiles()?.forEach { f ->
            f.copyTo(File(watchRepo.docDir, f.name), overwrite = true); media++
        }
        return media
    }

    private suspend fun applyPayload(p: BackupPayload) {
        db.clearAllData()
        db.routineDao().insertAll(p.routines)
        db.habitDao().insertAll(p.habits)
        db.habitLogDao().insertAll(p.habitLogs)
        db.shieldDao().insertAll(p.shieldUses)
        db.watchDao().insertAll(p.watches)
        db.wearLogDao().insertAll(p.wearLogs)
        db.xpDao().insertAll(p.xpEvents)
        db.focusSessionDao().insertAll(p.focusSessions)
        db.watchServiceDao().insertAll(p.watchServices)
        db.accuracyDao().insertAll(p.accuracyReadings)
        db.strapDao().insertAll(p.straps)
        db.skipRuleDao().insertAll(p.skipRules)
        db.sleepDao().insertAll(p.sleepLogs)
        db.strapSwapDao().insertAll(p.strapSwaps)
        db.watchPhotoDao().insertAll(p.watchPhotos)
        db.watchDocumentDao().insertAll(p.watchDocuments)
        db.plannerProjectDao().insertAll(p.plannerProjects)
        db.plannerTaskDao().insertAll(p.plannerTasks)
        db.plannerNoteDao().insertAll(p.plannerNotes)
        prefs.setTheme(p.settings.themeName)
        runCatching { dev.personalterminal.data.prefs.ThemeMode.valueOf(p.settings.themeMode) }.getOrNull()?.let { prefs.setThemeMode(it) }
        prefs.setUsername(p.settings.username)
        prefs.setHostname(p.settings.hostname)
        prefs.setPomodoro(p.settings.pomodoroFocusMin, p.settings.pomodoroBreakMin, p.settings.pomodoroLongBreakMin)
        prefs.setFont(p.settings.fontName)
        if (p.settings.customPaletteJson.isNotBlank()) prefs.setCustomPalette(p.settings.customPaletteJson)
        prefs.setQuietHours(p.settings.quietStartMin, p.settings.quietEndMin)
        prefs.setRemindersEnabled(p.settings.remindersEnabled)
        p.settings.notifications?.let { prefs.setNotifications(it.toPrefs()) }
        prefs.setCrt(p.settings.crtEffect)
        prefs.setAccessibilityMode(p.settings.accessibilityMode)
    }

    /**
     * Non-destructive merge. A single offset per table moves incoming primary keys past the
     * largest local id, and every child reference is shifted by its parent's offset. No table is
     * cleared and no local preference is overwritten.
     */
    private suspend fun mergePayload(p: BackupPayload): String {
        fun shift(id: Long, off: Long): Long = if (id <= 0L) id else id + off
        fun shiftOrNull(id: Long?, off: Long): Long? = id?.let { if (it <= 0L) it else it + off }
        val ro = db.routineDao().getAll().maxOfOrNull { it.id } ?: 0L
        val ho = db.habitDao().getAll().maxOfOrNull { it.id } ?: 0L
        val wo = db.watchDao().getAll().maxOfOrNull { it.id } ?: 0L
        val so = db.strapDao().getAll().maxOfOrNull { it.id } ?: 0L
        val po = db.plannerProjectDao().getAll().maxOfOrNull { it.id } ?: 0L
        val to = db.plannerTaskDao().getAll().maxOfOrNull { it.id } ?: 0L
        val no = db.plannerNoteDao().getAll().maxOfOrNull { it.id } ?: 0L
        val xo = db.xpDao().getAll().maxOfOrNull { it.id } ?: 0L
        val fo = db.focusSessionDao().getAll().maxOfOrNull { it.id } ?: 0L
        val svo = db.watchServiceDao().getAll().maxOfOrNull { it.id } ?: 0L
        val ao = db.accuracyDao().getAll().maxOfOrNull { it.id } ?: 0L
        val wo2 = db.wearLogDao().getAll().maxOfOrNull { it.id } ?: 0L
        val swo = db.strapSwapDao().getAll().maxOfOrNull { it.id } ?: 0L
        val sko = db.skipRuleDao().getAll().maxOfOrNull { it.id } ?: 0L
        val pho = db.watchPhotoDao().getAll().maxOfOrNull { it.id } ?: 0L
        val doo = db.watchDocumentDao().getAll().maxOfOrNull { it.id } ?: 0L

        db.routineDao().insertAll(p.routines.map { it.copy(id = shift(it.id, ro)) })
        db.habitDao().insertAll(p.habits.map { it.copy(id = shift(it.id, ho), routineId = shiftOrNull(it.routineId, ro), anchorId = shift(it.anchorId, ho)) })
        db.habitLogDao().insertAll(p.habitLogs.map { it.copy(habitId = shift(it.habitId, ho), ruleId = shift(it.ruleId, sko)) })
        db.shieldDao().insertAll(p.shieldUses.map { it.copy(habitId = shift(it.habitId, ho)) })
        db.xpDao().insertAll(p.xpEvents.map { it.copy(id = shift(it.id, xo), habitId = shift(it.habitId, ho)) })
        db.focusSessionDao().insertAll(p.focusSessions.map { it.copy(id = shift(it.id, fo), habitId = shift(it.habitId, ho)) })
        db.skipRuleDao().insertAll(p.skipRules.map { rule ->
            rule.copy(
                id = shift(rule.id, sko),
                habitIds = rule.habitIds.split(',').mapNotNull { it.trim().toLongOrNull() }
                    .joinToString(",") { shift(it, ho).toString() },
            )
        })
        val existingSleep = db.sleepDao().getAll().map { it.day }.toSet()
        db.sleepDao().insertAll(p.sleepLogs.filter { it.day !in existingSleep })

        db.watchDao().insertAll(p.watches.map { it.copy(id = shift(it.id, wo)) })
        db.wearLogDao().insertAll(p.wearLogs.map { it.copy(id = shift(it.id, wo2), watchId = shift(it.watchId, wo), strapId = shiftOrNull(it.strapId, so)) })
        db.watchServiceDao().insertAll(p.watchServices.map { it.copy(id = shift(it.id, svo), watchId = shift(it.watchId, wo)) })
        db.accuracyDao().insertAll(p.accuracyReadings.map { it.copy(id = shift(it.id, ao), watchId = shift(it.watchId, wo)) })
        db.strapDao().insertAll(p.straps.map { it.copy(id = shift(it.id, so), watchId = shiftOrNull(it.watchId, wo)) })
        db.strapSwapDao().insertAll(p.strapSwaps.map { it.copy(id = shift(it.id, swo), strapId = shift(it.strapId, so), watchId = shiftOrNull(it.watchId, wo)) })
        db.watchPhotoDao().insertAll(p.watchPhotos.map { it.copy(id = shift(it.id, pho), watchId = shift(it.watchId, wo)) })
        db.watchDocumentDao().insertAll(p.watchDocuments.map { it.copy(id = shift(it.id, doo), watchId = shift(it.watchId, wo)) })

        db.plannerProjectDao().insertAll(p.plannerProjects.map { it.copy(id = shift(it.id, po)) })
        db.plannerTaskDao().insertAll(p.plannerTasks.map { it.copy(id = shift(it.id, to), projectId = shiftOrNull(it.projectId, po)) })
        db.plannerNoteDao().insertAll(p.plannerNotes.map { it.copy(id = shift(it.id, no), projectId = shiftOrNull(it.projectId, po), taskId = shiftOrNull(it.taskId, to)) })

        return "${p.habits.size} habits, ${p.watches.size} watches, ${p.plannerProjects.size} projects, " +
            "${p.plannerTasks.size} tasks, ${p.plannerNotes.size} notes"
    }

    /** Merge-import (used by the Loop / Habitica importers): adds rows without wiping anything. */
    suspend fun importHabits(routines: List<Routine>, habits: List<Habit>, logs: List<HabitLog>): String = withContext(Dispatchers.IO) {
        var newHabits = 0; var newLogs = 0
        val routineIds = mutableMapOf<String, Long>()
        routines.forEach { r ->
            val existing = db.routineDao().getAll().firstOrNull { it.name.equals(r.name, true) }
            routineIds[r.name] = existing?.id ?: db.routineDao().insert(r.copy(id = 0, position = db.routineDao().getAll().size))
        }
        val existingHabits = db.habitDao().getAll()
        habits.forEach { h ->
            val routineId = h.routineId?.let { rid -> routines.firstOrNull { it.id == rid }?.let { routineIds[it.name] } }
            val match = existingHabits.firstOrNull { it.name.equals(h.name, true) }
            val id = match?.id ?: db.habitDao().insert(h.copy(id = 0, routineId = routineId, position = db.habitDao().nextPosition())).also { newHabits++ }
            val mine = logs.filter { it.habitId == h.id }
            mine.forEach { l ->
                if (db.habitLogDao().get(id, l.day) == null) { db.habitLogDao().upsert(l.copy(habitId = id)); newLogs++ }
            }
        }
        "imported $newHabits new habits and $newLogs log entries"
    }

    fun fileName(now: Long = System.currentTimeMillis(), encrypted: Boolean = false): String =
        "personal-terminal_${DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()))}.${if (encrypted) EXT_ENCRYPTED else EXT}"

    private fun fmt(ms: Long) = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

    companion object {
        const val EXT = "ptbak"
        const val EXT_ENCRYPTED = "ptbakx"
        const val MIME = "application/zip"
        const val MIME_ENCRYPTED = "application/octet-stream"
        const val ENTRY_DATA = "data.json"
        const val ENTRY_MEDIA_DIR = "media/"
        const val ENTRY_PHOTOS_DIR = "photos/"
        const val ENTRY_DOCS_DIR = "docs/"
    }
}
