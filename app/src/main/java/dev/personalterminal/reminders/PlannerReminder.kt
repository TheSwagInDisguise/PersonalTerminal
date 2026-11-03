package dev.personalterminal.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.personalterminal.MainActivity
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.R
import dev.personalterminal.data.db.PlannerTask
import dev.personalterminal.ui.navigation.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Exact alarms for one-off task reminders (distinct from habit reminders). */
object PlannerReminder {
    const val ACTION = "dev.personalterminal.action.PLANNER_REMINDER"
    const val EXTRA_TASK_ID = "planner_task_id"
    private const val NOTIF_BASE = 9000

    fun schedule(context: Context, task: PlannerTask) {
        val at = task.reminderAt ?: return cancel(context, task.id)
        if (task.completed || at <= System.currentTimeMillis()) return cancel(context, task.id)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pending(context, task.id)
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(context: Context, taskId: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(pending(context, taskId)) }
    }

    private fun pending(context: Context, taskId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        taskId.toInt(),
        Intent(context, PlannerReminderReceiver::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_TASK_ID, taskId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

class PlannerReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PlannerReminder.ACTION) return
        val id = intent.getLongExtra(PlannerReminder.EXTRA_TASK_ID, -1L)
        if (id < 0L) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = PersonalTerminalApp.get(context)
                val task = app.planner.task(id) ?: return@launch
                if (task.completed) return@launch
                if (android.os.Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) return@launch
                val open = PendingIntent.getActivity(
                    context, id.toInt(),
                    Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, Routes.PLANNER),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val n = NotificationCompat.Builder(context, PersonalTerminalApp.CHANNEL_PLANNER)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("□ ${task.title}")
                    .setContentText("one-off task is due · ${task.priority.lowercase()} priority")
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .build()
                runCatching { NotificationManagerCompat.from(context).notify(9000 + id.toInt(), n) }
            } finally {
                result.finish()
            }
        }
    }
}
