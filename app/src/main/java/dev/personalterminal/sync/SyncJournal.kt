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
 * 7.3 — append-only sync journal. Live row writes call [upsert]/[delete]; the journal stamps
 * the next per-device sequence and stores the row's JSON so a peer can replay it with
 * last-writer-wins on `(ts, device)`. Bulk paths (restore, seed, import) deliberately do not
 * journal — archives move whole state, the journal moves live edits.
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

    suspend inline fun <reified T : Any> upsert(entity: String, rowId: Long, row: T) {
        emit(entity, rowId.toString(), "u", json.encodeToString(row))
    }

    /** Settings keys are strings and ride the same journal as `prefs:<key>` rows. */
    suspend fun upsertKey(key: String, value: String): Unit =
        emit("prefs", "prefs:$key", "u", json.encodeToString(SettingsValue(key, value)))

    suspend fun delete(entity: String, rowId: Long): Unit = emit(entity, rowId.toString(), "d", "")
}
