package dev.momolite

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build

object Reminders {
    const val CHANNEL = "reminders"

    /** Returns false when the device refuses the alarm; the reminder stays saved in the list. */
    fun schedule(ctx: Context, r: Reminder): Boolean = try {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Inexact-while-idle alarm: no special permission, may fire a few minutes late.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pending(ctx, r.id, r.text))
        true
    } catch (e: SecurityException) {
        false
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(ctx, id, ""))
    }

    /** True when the app can actually show a notification right now. */
    fun notificationsEnabled(ctx: Context): Boolean {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    /** Counts of what recovery did, so a failure is visible in logs instead of silent. */
    data class BootReport(val scheduled: Int, val scheduleFailed: Int, val delivered: Int, val keptPending: Int, val cleared: Int)

    /**
     * Re-arms future alarms and recovers past-due reminders after boot or app open.
     * Every decision comes from ReminderPlan.decide: delivered rows are cleared without
     * re-firing, blocked notifications keep their row pending, and failed scheduling
     * is counted and logged, never swallowed.
     */
    fun rescheduleAll(ctx: Context): BootReport {
        val db = Db(ctx)
        val now = System.currentTimeMillis()
        val enabled = notificationsEnabled(ctx)
        var scheduled = 0; var failed = 0; var delivered = 0; var kept = 0; var cleared = 0
        db.reminders().forEach { r ->
            when (ReminderPlan.decide(r, now, enabled)) {
                ReminderAction.SCHEDULE -> if (schedule(ctx, r)) scheduled++ else failed++
                ReminderAction.DELIVER_NOW -> {
                    notify(ctx, r.id, r.text)
                    db.markReminderDelivered(r.id)
                    db.deleteReminder(r.id)
                    delivered++
                }
                ReminderAction.KEEP_PENDING -> kept++
                ReminderAction.CLEAR_DELIVERED -> { cancel(ctx, r.id); db.deleteReminder(r.id); cleared++ }
            }
        }
        // Persist the failure count so the app can show it, not just log it.
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_SCHEDULE_FAILED, failed).apply()
        return BootReport(scheduled, failed, delivered, kept, cleared)
    }

    private const val PREFS = "reminder_recovery"
    const val KEY_SCHEDULE_FAILED = "schedule_failed"

    private fun pending(ctx: Context, id: Long, text: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", text)
        return PendingIntent.getBroadcast(ctx, id.toInt(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun notify(ctx: Context, id: Long, text: String) {
        try {
            post(ctx, id, text)
        } catch (e: SecurityException) {
            // Notifications blocked at the system level; nothing else we can do from a receiver.
        }
    }

    private fun post(ctx: Context, id: Long, text: String) {
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
        if (ReminderPlan.onFire(Reminders.notificationsEnabled(ctx)) == ReminderAction.KEEP_PENDING) {
            // Stay pending: the row remains visible as missed and recovery retries later.
            Log.w(TAG, "reminder $id fired but notifications are blocked; keeping it pending")
            return
        }
        val db = Db(ctx)
        Reminders.notify(ctx, id, text)
        // Mark AFTER posting returns, BEFORE deleting. A crash in this small window can
        // re-post at recovery, but the stable notification id makes that repost replace
        // the same notification instead of stacking a second one.
        db.markReminderDelivered(id)
        db.deleteReminder(id)
    }

    private companion object { const val TAG = "ReminderReceiver" }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val report = Reminders.rescheduleAll(ctx)
            Log.i(TAG, "reminder recovery: $report")
        }
    }

    private companion object { const val TAG = "BootReceiver" }
}
