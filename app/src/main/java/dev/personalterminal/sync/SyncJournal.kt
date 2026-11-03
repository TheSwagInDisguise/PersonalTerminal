package dev.personalterminal.sync

import androidx.room.withTransaction
import dev.personalterminal.data.db.AppDatabase
import dev.personalterminal.data.db.SyncEvent
import dev.personalterminal.data.prefs.UserPrefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** LWW payload for one settings key riding the journal as a `prefs:*` row. */
@Serializable
data class SettingsValue(val key: String, val value: String)

/**
 * 7.3 — append-only sync journal GROUNDWORK. Live row writes call [upsert]/[delete]; the
 * journal stamps the next per-device sequence and stores the row's JSON for a future peer to
 * replay with last-writer-wins on `(ts, device)`. Event transport/replay is NOT implemented in
 * 0.5.0: DriveSync still moves whole backup archives. Bulk paths (restore, merge, seed, import)
 * deliberately do not journal — archives move whole state.
 *
 * Injection: repositories expose `var journal: SyncJournal?` (null in tests) and the app
 * wires it once in `onCreate`.
 */
class SyncJournal(private val db: AppDatabase, private val prefs: UserPrefs) {

    /** Shared JSON dialect for payloads (encodeDefaults so Room defaults survive the trip). */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cachedDevice: String? = null

    suspend fun deviceId(): String {
        cachedDevice?.let { return it }
        val id = prefs.syncDeviceId()
        cachedDevice = id
        return id
    }

    /** Journal one row write. Sequence allocation is transactional so local order is total. */
    suspend fun emit(entity: String, rowId: String, op: String, payload: String) {
        val device = deviceId()
        db.withTransaction {
            val seq = db.syncEventDao().maxSeq(device) + 1
            db.syncEventDao().insert(
                SyncEvent(
                    device = device,
                    seq = seq,
                    ts = System.currentTimeMillis(),
                    entity = entity,
                    rowId = rowId,
                    op = op,
                    payload = payload,
                ),
            )
        }
    }

    suspend inline fun <reified T : Any> upsert(entity: String, rowId: Long, row: T): Unit =
        upsert(entity, rowId.toString(), row)

    /** Composite keys (`habit_logs` = `habitId:day`) ride as text row ids. */
    suspend inline fun <reified T : Any> upsert(entity: String, rowId: String, row: T) {
        emit(entity, rowId, "u", json.encodeToString(row))
    }

    /** Settings keys are strings and ride the same journal as `prefs:<key>` rows. */
    suspend fun upsertKey(key: String, value: String): Unit =
        emit("prefs", "prefs:$key", "u", json.encodeToString(SettingsValue(key, value)))

    suspend fun delete(entity: String, rowId: Long): Unit = deleteRow(entity, rowId.toString())

    suspend fun deleteRow(entity: String, rowId: String): Unit = emit(entity, rowId, "d", "")
}

/** Conflict record carried by the same append-only journal (no transport/replay in 0.5.0). */
@Serializable
data class NoteConflictEvent(
    val noteId: Long,
    val driveFileId: String,
    val localBody: String,
    val remoteBody: String,
    val at: Long,
)

/** Both versions of a conflicting Markdown note are journaled before note sync pauses. */
suspend fun SyncJournal.noteConflict(note: dev.personalterminal.data.db.PlannerNote, remoteBody: String) {
    emit(
        "note_conflict",
        "planner_notes:${note.id}",
        "c",
        json.encodeToString(
            NoteConflictEvent(
                noteId = note.id,
                driveFileId = note.driveFileId,
                localBody = note.body,
                remoteBody = remoteBody,
                at = System.currentTimeMillis(),
            ),
        ),
    )
}

/** The user's conflict choice is appended too, so neither version is silently dropped. */
suspend fun SyncJournal.noteResolved(note: dev.personalterminal.data.db.PlannerNote, choice: String) {
    emit("note_resolved", "planner_notes:${note.id}", "r", json.encodeToString(SettingsValue("choice", choice)))
}
