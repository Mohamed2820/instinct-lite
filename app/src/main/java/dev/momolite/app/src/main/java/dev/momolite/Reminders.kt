package dev.momolite

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

object Reminders {
    const val CHANNEL = "reminders"

    fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Inexact-while-idle alarm: no special permission, may fire a few minutes late.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pending(ctx, r.id, r.text))
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, id, ""))
    }

    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        Db(ctx).reminders().filter { it.at > now }.forEach { schedule(ctx, it) }
    }

    private fun pending(ctx: Context, id: Long, text: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text)
        return PendingIntent.getBroadcast(ctx, id.toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun notify(ctx: Context, id: Long, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_HIGH))
        }
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL) else Notification.Builder(ctx)
        val n = b.setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(ctx.getString(R.string.reminder_title))
            .setContentText(text).setContentIntent(open).setAutoCancel(true).build()
        nm.notify(id.toInt(), n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0L)
        val text = intent.getStringExtra("text") ?: return
        Reminders.notify(ctx, id, text)
        Db(ctx).deleteReminder(id)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Reminders.rescheduleAll(ctx)
    }
}
