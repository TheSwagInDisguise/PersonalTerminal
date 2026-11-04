package dev.personalterminal

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * Launcher long-press shortcuts (`timer`, `wear`, `habit add`, `review`, `planner`). Registered dynamically so
 * the same code works for the `.debug` application id; users can pin any of them to the home screen.
 */
object AppShortcuts {
    fun publish(context: Context) = runCatching {
        fun shortcut(id: String, short: String, long: String, icon: Int, route: String) =
            ShortcutInfoCompat.Builder(context, id)
                .setShortLabel(short)
                .setLongLabel(long)
                .setIcon(IconCompat.createWithResource(context, icon))
                .setIntent(
                    Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                        .putExtra(MainActivity.EXTRA_ROUTE, route),
                )
                .build()
        ShortcutManagerCompat.setDynamicShortcuts(
            context,
            listOf(
                shortcut("timer", context.getString(R.string.shortcut_timer_short), context.getString(R.string.shortcut_timer_long), R.drawable.ic_shortcut_timer, "timer"),
                shortcut("wear", context.getString(R.string.shortcut_wear_short), context.getString(R.string.shortcut_wear_long), R.drawable.ic_shortcut_watch, "wear/log"),
                shortcut("add", context.getString(R.string.shortcut_add_short), context.getString(R.string.shortcut_add_long), R.drawable.ic_shortcut_add, "habit/edit"),
                shortcut("review", context.getString(R.string.shortcut_review_short), context.getString(R.string.shortcut_review_long), R.drawable.ic_shortcut_review, "review"),
                shortcut("planner", context.getString(R.string.shortcut_planner_short), context.getString(R.string.shortcut_planner_long), R.drawable.ic_shortcut_add, "planner"),
            ),
        )
    }
}
