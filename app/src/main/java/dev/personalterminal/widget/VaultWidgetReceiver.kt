package dev.personalterminal.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.PendingIntent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.RemoteViews
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.R
import dev.personalterminal.data.db.displayName
import dev.personalterminal.domain.AppClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * A15 — vault widget: today's watch photo (the piece on the wrist, else the suggestion) plus a
 * `next: …` line, so the collection is one glance away. Plain RemoteViews: the photo is a
 * down-scaled bitmap, no Coil, no Glance dependency on the photo path.
 */
class VaultWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { id -> render(context, appWidgetManager, id) }
    }

    companion object {
        /** Cheap refresh after wear logs, app start, or the 30-minute provider period. */
        fun refreshAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(context, VaultWidgetReceiver::class.java))
            ids.forEach { render(context, mgr, it) }
        }

        private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
            val app = PersonalTerminalApp.get(context)
            val today = AppClock.today()
            val worn = runCatching {
                runBlocking { app.watches.observeWearForDay(today).first() }
            }.getOrDefault(emptyList()).firstOrNull()
            val sugg = runCatching { runBlocking { app.watches.suggestNext(today) } }.getOrNull()

            val watch = worn?.watch ?: sugg?.first
            val name = watch?.displayName ?: "no watch yet"
            val nextLine = when {
                worn != null && sugg != null -> "worn today · next: ${sugg.first.displayName} (${sugg.second})"
                worn != null -> "worn today"
                sugg != null -> "next: ${sugg.first.displayName} (${sugg.second})"
                else -> "add a watch · vault"
            }

            val rv = RemoteViews(context.packageName, R.layout.vault_widget).apply {
                setTextViewText(R.id.vault_name, name)
                setTextViewText(R.id.vault_next, nextLine)
                watch?.photoPath?.let { path ->
                    photoBitmap(app, path)?.let { bmp -> setImageViewBitmap(R.id.vault_photo, bmp) }
                }
                val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launch?.let {
                    val pi = PendingIntent.getActivity(
                        context, id, it,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    setOnClickPendingIntent(R.id.vault_root, pi)
                }
            }
            mgr.updateAppWidget(id, rv)
        }

        /** Down-scale the watch photo to widget size (long edge ≈ 256 px). */
        private fun photoBitmap(app: PersonalTerminalApp, relative: String): Bitmap? = runCatching {
            val f = app.watches.photoFile(relative)
            if (!f.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 512 || bounds.outHeight / sample > 512) sample *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }.getOrNull()
    }
}
