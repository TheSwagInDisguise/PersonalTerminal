package dev.personalterminal.sync

import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.FileContent
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Thin wrapper over the Drive v3 REST client.
 * Backups live in a dedicated folder `Personal Terminal Backups/` in the user's My Drive.
 */
class DriveClient(private val accessToken: String) {

    private val drive: Drive by lazy {
        val initializer = HttpRequestInitializer { req ->
            req.headers.authorization = "Bearer $accessToken"
            req.connectTimeout = 30_000
            req.readTimeout = 60_000
        }
        Drive.Builder(NetHttpTransport(), GsonFactory.getDefaultInstance(), initializer)
            .setApplicationName("Personal Terminal")
            .build()
    }

    data class RemoteBackup(val id: String, val name: String, val size: Long, val modifiedAt: Long)

    /** Finds or creates the backup folder and returns its id. */
    suspend fun ensureFolder(): String = withContext(Dispatchers.IO) {
        val q = "mimeType='$FOLDER_MIME' and name='$FOLDER_NAME' and trashed=false and 'root' in parents"
        val existing = drive.files().list().setQ(q).setSpaces("drive").setFields("files(id,name)").execute().files
        if (!existing.isNullOrEmpty()) return@withContext existing.first().id
        val meta = DriveFile().apply {
            name = FOLDER_NAME
            mimeType = FOLDER_MIME
            description = "Automatic backups created by the Personal Terminal Android app"
        }
        drive.files().create(meta).setFields("id").execute().id
    }

    suspend fun upload(folderId: String, file: File, mime: String): RemoteBackup = withContext(Dispatchers.IO) {
        val meta = DriveFile().apply {
            name = file.name
            parents = listOf(folderId)
        }
        val created = drive.files().create(meta, FileContent(mime, file))
            .setFields("id,name,size,modifiedTime")
            .execute()
        created.toRemote()
    }

    /** Only full-archive backups; two-way Markdown notes live in the same folder but are not backups. */
    suspend fun list(folderId: String): List<RemoteBackup> = withContext(Dispatchers.IO) {
        val q = "'$folderId' in parents and trashed=false"
        drive.files().list().setQ(q).setSpaces("drive")
            .setOrderBy("modifiedTime desc")
            .setFields("files(id,name,size,modifiedTime)")
            .setPageSize(50)
            .execute().files.orEmpty().filter { isBackup(it.name ?: "") }.map { it.toRemote() }
    }

    /** Two-way editable Markdown mirror of a planner note (0.5.0). */
    suspend fun listMarkdown(folderId: String): List<RemoteBackup> = withContext(Dispatchers.IO) {
        val q = "'$folderId' in parents and mimeType='$MARKDOWN_MIME' and trashed=false"
        drive.files().list().setQ(q).setSpaces("drive")
            .setOrderBy("modifiedTime desc")
            .setFields("files(id,name,size,modifiedTime)")
            .setPageSize(200)
            .execute().files.orEmpty().map { it.toRemote() }
    }

    suspend fun findMarkdown(folderId: String, name: String): RemoteBackup? = withContext(Dispatchers.IO) {
        val q = "'$folderId' in parents and name='${name.replace("'", "\\'")}' and trashed=false"
        drive.files().list().setQ(q).setSpaces("drive")
            .setFields("files(id,name,size,modifiedTime)")
            .setPageSize(1)
            .execute().files.orEmpty().firstOrNull()?.toRemote()
    }

    /** Create or update a plain-text Markdown file; returns its Drive id for the note row. */
    suspend fun uploadText(folderId: String, name: String, text: String, existingId: String? = null): RemoteBackup = withContext(Dispatchers.IO) {
        val meta = DriveFile().apply {
            this.name = name
            mimeType = MARKDOWN_MIME
            if (existingId == null) parents = listOf(folderId)
        }
        val content = ByteArrayContent(MARKDOWN_MIME, text.toByteArray(Charsets.UTF_8))
        val req = if (existingId == null) drive.files().create(meta, content) else drive.files().update(existingId, meta, content)
        req.setFields("id,name,size,modifiedTime").execute().toRemote()
    }

    suspend fun downloadText(fileId: String): String = withContext(Dispatchers.IO) {
        ByteArrayOutputStream().use { out ->
            download(fileId, out)
            out.toString("UTF-8")
        }
    }

    suspend fun download(fileId: String, out: OutputStream) = withContext(Dispatchers.IO) {
        drive.files().get(fileId).executeMediaAndDownloadTo(out)
    }

    suspend fun delete(fileId: String) = withContext(Dispatchers.IO) {
        drive.files().delete(fileId).execute()
    }

    /** Keeps only the newest [keep] backups. */
    suspend fun prune(folderId: String, keep: Int) {
        val all = list(folderId)
        all.drop(keep).forEach { runCatching { delete(it.id) } }
    }

    private fun DriveFile.toRemote() = RemoteBackup(
        id = id,
        name = name ?: "",
        size = getSize() ?: 0L,
        modifiedAt = modifiedTime?.value ?: 0L,
    )

    companion object {
        const val FOLDER_NAME = "Personal Terminal Backups"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val KEEP_BACKUPS = 10
        const val MARKDOWN_MIME = "text/markdown"
        fun isBackup(name: String): Boolean = name.endsWith(".ptbak") || name.endsWith(".ptbakx")
    }
}
