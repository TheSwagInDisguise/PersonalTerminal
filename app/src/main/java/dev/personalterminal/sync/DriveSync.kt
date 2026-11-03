package dev.personalterminal.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.backup.BackupManager
import dev.personalterminal.data.prefs.UserPrefs
import dev.personalterminal.domain.planner.NoteMarkdownSync
import dev.personalterminal.domain.planner.NoteSyncResolution
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Orchestrates FULL-ARCHIVE backup/restore to Drive (`.ptbak` / `.ptbakx`) plus a separate
 * two-way Markdown mirror for planner notes.
 *
 * IMPORTANT: this is NOT multi-device sync. The append-only `SyncEvent` / `SyncJournal`
 * groundwork has no event transport or replay yet; DriveSync moves whole archives and Markdown
 * files, not journal events. Used interactively (Settings / Boot / Planner) and from
 * [DriveBackupWorker].
 */
class DriveSync(
    private val context: Context,
    private val auth: GoogleAuth,
    private val backups: BackupManager,
    private val prefs: UserPrefs,
) {
    sealed class Outcome {
        data class Success(val message: String) : Outcome()
        data class NeedsConsent(val intent: android.content.Intent) : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    /** Runs a full backup. Silent when consent was previously granted. */
    suspend fun backupNow(accessToken: String? = null): Outcome {
        val token = accessToken ?: when (val a = auth.authorizeDrive()) {
            is GoogleAuth.AuthzResult.Granted -> a.accessToken
            is GoogleAuth.AuthzResult.NeedsResolution -> return Outcome.NeedsConsent(a.intent)
            is GoogleAuth.AuthzResult.Error -> return fail(a.message)
        }
        return try {
            val client = DriveClient(token)
            val current = prefs.current()
            val folderId = current.driveFolderId.ifBlank { client.ensureFolder() }
            if (folderId != current.driveFolderId) prefs.setDriveAccount(current.driveAccountEmail, folderId)
            val archive = backups.createLocalArchive()
            val remote = client.upload(folderId, archive, if (archive.name.endsWith(BackupManager.EXT_ENCRYPTED)) BackupManager.MIME_ENCRYPTED else BackupManager.MIME)
            client.prune(folderId, DriveClient.KEEP_BACKUPS)
            archive.delete()
            val msg = "uploaded ${remote.name} (${remote.size / 1024} KB)"
            prefs.setLastBackup(System.currentTimeMillis(), "ok: $msg")
            Outcome.Success(msg)
        } catch (e: Exception) {
            Log.w(TAG, "backup failed", e)
            // A 401 usually means the folder id is stale or token expired
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun listRemote(accessToken: String? = null): Result<List<DriveClient.RemoteBackup>> {
        val token = accessToken ?: when (val a = auth.authorizeDrive()) {
            is GoogleAuth.AuthzResult.Granted -> a.accessToken
            is GoogleAuth.AuthzResult.NeedsResolution -> return Result.failure(ConsentRequired(a.intent))
            is GoogleAuth.AuthzResult.Error -> return Result.failure(IllegalStateException(a.message))
        }
        return runCatching {
            val client = DriveClient(token)
            val folder = prefs.current().driveFolderId.ifBlank { client.ensureFolder() }
            client.list(folder)
        }
    }

    suspend fun restoreRemote(fileId: String, accessToken: String? = null, passphrase: String? = null): Outcome {
        val token = accessToken ?: when (val a = auth.authorizeDrive()) {
            is GoogleAuth.AuthzResult.Granted -> a.accessToken
            is GoogleAuth.AuthzResult.NeedsResolution -> return Outcome.NeedsConsent(a.intent)
            is GoogleAuth.AuthzResult.Error -> return fail(a.message)
        }
        return try {
            val tmp = File(context.cacheDir, "restore_${System.currentTimeMillis()}.${BackupManager.EXT}")
            FileOutputStream(tmp).use { DriveClient(token).download(fileId, it) }
            val summary = FileInputStream(tmp).use { backups.restoreArchive(it, passphrase) }
            tmp.delete()
            Outcome.Success(summary)
        } catch (e: BackupManager.PassphraseRequired) {
            Outcome.Failure(e.message ?: "passphrase required")
        } catch (e: Exception) {
            Log.w(TAG, "restore failed", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Backup health check: downloads the newest archive in the Drive folder and runs a dry-run
     * restore on it ([BackupManager.verifyArchive]). Records the result for the profile heartbeat.
     */
    suspend fun verifyLatest(accessToken: String? = null, passphrase: String? = null): Outcome {
        val token = accessToken ?: when (val a = auth.authorizeDrive()) {
            is GoogleAuth.AuthzResult.Granted -> a.accessToken
            is GoogleAuth.AuthzResult.NeedsResolution -> return Outcome.NeedsConsent(a.intent)
            is GoogleAuth.AuthzResult.Error -> return Outcome.Failure(a.message)
        }
        return try {
            val client = DriveClient(token)
            val folder = prefs.current().driveFolderId.ifBlank { client.ensureFolder() }
            val newest = client.list(folder).maxByOrNull { it.modifiedAt } ?: return Outcome.Failure("no backups in Drive yet")
            val tmp = File(context.cacheDir, "verify_${System.currentTimeMillis()}.bin")
            FileOutputStream(tmp).use { client.download(newest.id, it) }
            val v = FileInputStream(tmp).use { backups.verifyArchive(it, passphrase) }
            tmp.delete()
            val msg = (if (v.ok) "ok: " else "warn: ${v.photosMissing} photos missing · ") + "${newest.name} · ${v.summary}"
            prefs.setBackupVerified(System.currentTimeMillis(), msg)
            prefs.setBackupStats(newest.size, v.photosPresent)
            Outcome.Success(msg)
        } catch (e: BackupManager.PassphraseRequired) {
            prefs.setBackupVerified(System.currentTimeMillis(), "error: ${e.message}")
            Outcome.Failure(e.message ?: "passphrase required")
        } catch (e: Exception) {
            Log.w(TAG, "verify failed", e)
            val msg = e.message ?: e.javaClass.simpleName
            prefs.setBackupVerified(System.currentTimeMillis(), "error: $msg")
            Outcome.Failure(msg)
        }
    }

    /**
     * Onboarding "link this install": keep all local data, just remember the Drive account and
     * backup folder. Restore and merge are separate choices on the boot screen.
     */
    suspend fun linkAccount(accessToken: String? = null): Outcome {
        val token = accessToken ?: when (val a = auth.authorizeDrive()) {
            is GoogleAuth.AuthzResult.Granted -> a.accessToken
            is GoogleAuth.AuthzResult.NeedsResolution -> return Outcome.NeedsConsent(a.intent)
            is GoogleAuth.AuthzResult.Error -> return fail(a.message)
        }
        return try {
            val folderId = DriveClient(token).ensureFolder()
            val current = prefs.current()
            prefs.setDriveAccount(current.driveAccountEmail, folderId)
            Outcome.Success("linked drive folder $folderId (local data kept)")
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Onboarding "merge an independent install": download a backup and merge it into the data
     * already on this device instead of replacing it. See [BackupManager.mergeArchive].
     */
    suspend fun linkAndMerge(fileId: String, accessToken: String? = null, passphrase: String? = null): Outcome {
        val token = accessToken ?: when (val a = auth.authorizeDrive()) {
            is GoogleAuth.AuthzResult.Granted -> a.accessToken
            is GoogleAuth.AuthzResult.NeedsResolution -> return Outcome.NeedsConsent(a.intent)
            is GoogleAuth.AuthzResult.Error -> return fail(a.message)
        }
        return try {
            val tmp = File(context.cacheDir, "merge_${System.currentTimeMillis()}.${BackupManager.EXT}")
            FileOutputStream(tmp).use { DriveClient(token).download(fileId, it) }
            val summary = FileInputStream(tmp).use { backups.mergeArchive(it, passphrase) }
            tmp.delete()
            Outcome.Success(summary)
        } catch (e: BackupManager.PassphraseRequired) {
            Outcome.Failure(e.message ?: "passphrase required")
        } catch (e: Exception) {
            Log.w(TAG, "merge failed", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Two-way Markdown: push the local note body as a `.md` file, if note sync is not paused. */
    suspend fun pushNote(noteId: Long, accessToken: String? = null): Outcome {
        val token = accessToken ?: when (val a = auth.authorizeDrive()) {
            is GoogleAuth.AuthzResult.Granted -> a.accessToken
            is GoogleAuth.AuthzResult.NeedsResolution -> return Outcome.NeedsConsent(a.intent)
            is GoogleAuth.AuthzResult.Error -> return fail(a.message)
        }
        val planner = PersonalTerminalApp.get(context).planner
        val note = planner.note(noteId) ?: return Outcome.Failure("note $noteId not found")
        if (note.syncPaused) return Outcome.Failure("note sync paused – resolve the conflict in planner first")
        return try {
            val client = DriveClient(token)
            val folder = prefs.current().driveFolderId.ifBlank { client.ensureFolder() }
            val name = NoteMarkdownSync.fileName(note)
            val existing = note.driveFileId.takeIf { it.isNotBlank() } ?: client.findMarkdown(folder, name)?.id
            val remote = client.uploadText(folder, name, NoteMarkdownSync.toMarkdown(note), existing)
            planner.markNoteSynced(noteId, remote.id, note.body)
            Outcome.Success("pushed $name (two-way editable Markdown)")
        } catch (e: Exception) {
            Log.w(TAG, "note push failed", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Two-way Markdown: pull Drive edits. When both sides changed, note sync PAUSES and both
     * versions stay on the row until the user chooses local / Drive / both.
     */
    suspend fun pullNote(noteId: Long, accessToken: String? = null): Outcome {
        val token = accessToken ?: when (val a = auth.authorizeDrive()) {
            is GoogleAuth.AuthzResult.Granted -> a.accessToken
            is GoogleAuth.AuthzResult.NeedsResolution -> return Outcome.NeedsConsent(a.intent)
            is GoogleAuth.AuthzResult.Error -> return fail(a.message)
        }
        val planner = PersonalTerminalApp.get(context).planner
        val note = planner.note(noteId) ?: return Outcome.Failure("note $noteId not found")
        return try {
            val client = DriveClient(token)
            val folder = prefs.current().driveFolderId.ifBlank { client.ensureFolder() }
            val remote = note.driveFileId.takeIf { it.isNotBlank() }?.let { id ->
                runCatching { client.downloadText(id) }.getOrNull()?.let { text -> id to text }
            } ?: (client.findMarkdown(folder, NoteMarkdownSync.fileName(note))?.let { f -> f.id to client.downloadText(f.id) }
                ?: return Outcome.Failure("no Drive Markdown file for this note yet"))
            val body = NoteMarkdownSync.bodyFromMarkdown(remote.second)
            val at = System.currentTimeMillis()
            when (val r = planner.pullNoteMarkdown(noteId, body, at, remote.first)) {
                is NoteSyncResolution.Conflict -> Outcome.Success("conflict: sync paused, both versions retained – choose in planner")
                is NoteSyncResolution.ApplyRemote -> Outcome.Success("applied Drive Markdown edits")
                is NoteSyncResolution.PushLocal -> Outcome.Success("local copy is newer – push it to Drive")
                is NoteSyncResolution.Unchanged -> Outcome.Success("note already in sync")
            }
        } catch (e: Exception) {
            Log.w(TAG, "note pull failed", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private suspend fun fail(msg: String): Outcome.Failure {
        prefs.setBackupStatus("error: $msg")
        return Outcome.Failure(msg)
    }

    class ConsentRequired(val intent: android.content.Intent) : Exception("consent required")

    companion object {
        private const val TAG = "DriveSync"
        const val PERIODIC_WORK = "drive_backup_periodic"
        const val ONE_TIME_WORK = "drive_backup_once"

        fun schedule(context: Context, enabled: Boolean, intervalHours: Int) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) { wm.cancelUniqueWork(PERIODIC_WORK); return }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            val req = PeriodicWorkRequestBuilder<DriveBackupWorker>(intervalHours.coerceAtLeast(1).toLong(), TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** Debounced "data changed → back up soon" trigger. */
        fun requestSoon(context: Context) {
            val req = OneTimeWorkRequestBuilder<DriveBackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(10, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME_WORK, androidx.work.ExistingWorkPolicy.REPLACE, req)
        }
    }
}

class DriveBackupWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PersonalTerminalApp
        val settings = app.prefs.current()
        if (!settings.autoBackup || settings.driveAccountEmail.isBlank()) return Result.success()
        return when (app.driveSync.backupNow()) {
            is DriveSync.Outcome.Success -> Result.success()
            is DriveSync.Outcome.NeedsConsent -> Result.failure() // user must re-consent in-app
            is DriveSync.Outcome.Failure -> if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
