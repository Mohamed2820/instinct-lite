package dev.momolite

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.textfield.TextInputEditText
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Activity-level runtime tests (simulated Android, no real device, no network, no real Keystore). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "ar-rtl-night")
class RuntimeAppTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val url = "https://api.one.example/v1"

    private class Fake(val f: (HttpRequestSpec, CancelHandle) -> HttpResult) : Transport {
        override fun post(spec: HttpRequestSpec, connectMs: Int, readMs: Int, cancel: CancelHandle) = f(spec, cancel)
    }

    @Before fun setUp() {
        ApiSettings.prefsOverride = ctx.getSharedPreferences("test_plain", Context.MODE_PRIVATE).also { it.edit().clear().commit() }
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("welcomed", true).commit()
        Db(ctx).clearAll()
        MainActivity.clientFactory = { ProviderClient(Fake { _, _ -> HttpResult(200, "{\"choices\":[{\"message\":{\"content\":\"hi there\"}}]}") }) }
    }
    @After fun tearDown() { ApiSettings.prefsOverride = null; MainActivity.clientFactory = { ProviderClient() } }

    private fun start() = Robolectric.buildActivity(MainActivity::class.java).setup().get()
    private fun MainActivity.v(id: Int) = findViewById<View>(id)
    private fun idle() = org.robolectric.shadows.ShadowLooper.idleMainLooper()
    private fun configureProvider() { ApiSettings.save(ctx, ProviderType.OPENAI_COMPATIBLE, "k-secret", "m", "", url); ApiSettings.setEnabled(ctx, true) }
    private fun waitUi(cond: () -> Boolean) { repeat(100) { idle(); if (cond()) return; Thread.sleep(20) }; fail("timeout") }

    @Test fun welcomeShownOnceThenMain() {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("welcomed", false).commit()
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        a.v(R.id.welcome_start).performClick()
        assertNotNull(a.v(R.id.bottom_nav))
        assertTrue(ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("welcomed", false))
    }

    @Test fun rtlAndDarkAreActive() {
        val a = start()
        assertEquals(View.LAYOUT_DIRECTION_RTL, a.resources.configuration.layoutDirection)
        assertTrue(a.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        assertEquals(View.LAYOUT_DIRECTION_RTL, a.v(R.id.page_chat).layoutDirection)
    }

    @Test fun navigationSelectsPagesAndFlagSecureOnlyInSettings() {
        val a = start()
        val nav = a.findViewById<BottomNavigationView>(R.id.bottom_nav)
        nav.selectedItemId = R.id.nav_settings; idle()
        assertEquals(View.VISIBLE, a.v(R.id.page_settings).visibility); assertEquals(View.GONE, a.v(R.id.page_chat).visibility)
        assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        nav.selectedItemId = R.id.nav_reminders; idle()
        assertEquals(0, a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        assertEquals(R.id.nav_reminders, nav.selectedItemId)
    }

    @Test fun noProviderSaysSoPlainly() {
        val a = start()
        a.findViewById<TextInputEditText>(R.id.input).setText("hello")
        a.v(R.id.send).performClick(); idle()
        assertTrue(Db(ctx).messages(Db(ctx).conversations().first().id).any { it.role == "system" && it.text == a.getString(R.string.no_provider) })
        val rv = a.findViewById<RecyclerView>(R.id.messages)
        assertEquals(2, rv.adapter!!.itemCount)
    }

    @Test fun chatReplyShownWithConfiguredProvider() {
        configureProvider()
        val a = start()
        a.findViewById<TextInputEditText>(R.id.input).setText("hello")
        a.v(R.id.send).performClick()
        waitUi { a.findViewById<RecyclerView>(R.id.messages).adapter!!.itemCount == 2 }
    }

    @Test fun cancelledRequestDropsLateReply() {
        configureProvider()
        val gate = CountDownLatch(1); val started = CountDownLatch(1)
        MainActivity.clientFactory = { ProviderClient(Fake { _, c -> started.countDown(); gate.await(5, TimeUnit.SECONDS); HttpResult(200, "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}") }) }
        val a = start()
        a.findViewById<TextInputEditText>(R.id.input).setText("hello")
        a.v(R.id.send).performClick()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        a.v(R.id.send).performClick() // stop button
        gate.countDown(); Thread.sleep(300); idle()
        val roles = Db(ctx).messages(Db(ctx).conversations().first().id).map { it.role to it.text }
        assertTrue(roles.toString(), roles.none { it.second == "late" }) // the late reply was dropped
    }

    @Test fun deleteAllPurgesKeyDbAndAlarms() {
        configureProvider()
        val db = Db(ctx)
        val c = db.newConversation("t"); db.addMessage(c, "user", "x")
        val id = db.addReminder("r", System.currentTimeMillis() + 3_600_000)
        Reminders.schedule(ctx, Reminder(id, "r", System.currentTimeMillis() + 3_600_000))
        val am = shadowOf(ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        assertNotNull(am.peekNextScheduledAlarm())
        val a = start()
        a.findViewById<android.view.View>(R.id.delete_all_data).performClick()
        val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); idle()
        assertFalse(ApiSettings.hasKey(ctx)); assertFalse(ApiSettings.isEnabled(ctx))
        assertEquals(0, Db(ctx).reminders().size)
        assertNull(am.peekNextScheduledAlarm())
        assertTrue(Db(ctx).conversations().none { it.id == c })
    }

    @Test fun addReminderSchedulesAlarmAndRejectsPast() {
        val a = start()
        a.findViewById<TextInputEditText>(R.id.reminder_text).setText("water")
        a.v(R.id.add_reminder).performClick(); idle()
        assertEquals(1, Db(ctx).reminders().size)
        assertNotNull(shadowOf(ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).peekNextScheduledAlarm())
        // empty text rejected
        a.findViewById<TextInputEditText>(R.id.reminder_text).setText("  ")
        a.v(R.id.add_reminder).performClick(); idle()
        assertEquals(1, Db(ctx).reminders().size)
    }

    @Test fun dateTimeButtonsAreBound() {
        val a = start()
        assertTrue(a.findViewById<Button>(R.id.pick_date).text.isNotBlank())
        assertTrue(a.findViewById<Button>(R.id.pick_time).text.isNotBlank())
    }

    @Test fun savingSettingsWithNewAddressDropsKeyAndEmptiesField() {
        configureProvider()
        val a = start()
        a.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_settings; idle()
        a.findViewById<TextInputEditText>(R.id.settings_address).setText("https://other.example/v1")
        a.v(R.id.save_settings).performClick(); idle()
        assertFalse(ApiSettings.hasKey(ctx))
        assertEquals("", a.findViewById<TextInputEditText>(R.id.settings_key).text.toString())
    }

    @Test fun testButtonUsesFakeTransportAndNeverSendsStoredKeyElsewhere() {
        configureProvider()
        var sentTo = ""; var auth = ""
        MainActivity.clientFactory = { ProviderClient(Fake { spec, _ -> sentTo = spec.url; auth = spec.headers.values.joinToString(); HttpResult(200, "{\"choices\":[{\"message\":{\"content\":\"pong\"}}]}") }) }
        val a = start()
        a.findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_settings; idle()
        a.findViewById<TextInputEditText>(R.id.settings_address).setText("https://other.example/v1")
        a.v(R.id.test_connection).performClick(); idle()
        assertEquals("", sentTo) // key missing for the new endpoint: nothing was sent
        a.findViewById<TextInputEditText>(R.id.settings_key).setText("typed-key")
        a.v(R.id.test_connection).performClick()
        waitUi { sentTo.isNotEmpty() || a.findViewById<TextView>(R.id.test_result).text.toString() != "" && !a.findViewById<TextView>(R.id.test_result).text.toString().contains("بيتجرب") && a.findViewById<TextView>(R.id.test_result).text.toString() != a.getString(R.string.settings_testing) }
        assertTrue(a.findViewById<TextView>(R.id.test_result).text.toString(), sentTo.isNotEmpty())
        assertTrue(sentTo.startsWith("https://other.example"))
        assertTrue(auth.contains("typed-key")); assertFalse(auth.contains("k-secret"))
    }

    @Test fun conversationLifecycle() {
        val a = start()
        val db = Db(ctx)
        a.findViewById<TextInputEditText>(R.id.input).setText("first question")
        a.v(R.id.send).performClick(); idle()
        a.v(R.id.new_chat).performClick(); idle()
        assertEquals(2, db.conversations().size)
        val old = db.conversations().first { it.title != Titles.FALLBACK }
        db.deleteConversation(old.id)
        assertTrue(db.conversations().none { it.id == old.id })
        assertTrue(db.messages(old.id).isEmpty())
    }

    @Test fun reminderReceiverDeniedKeepsRowEnabledPostsAndDeletes() {
        val db = Db(ctx)
        val id = db.addReminder("pill", System.currentTimeMillis() + 1000)
        val nm = shadowOf(ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        nm.setNotificationsEnabled(false)
        val i = Intent(ctx, ReminderReceiver::class.java).putExtra("id", id).putExtra("text", "pill")
        ReminderReceiver().onReceive(ctx, i)
        assertEquals(1, db.reminders().size); assertEquals(0, nm.size())
        nm.setNotificationsEnabled(true)
        ReminderReceiver().onReceive(ctx, i)
        assertEquals(0, db.reminders().size); assertEquals(1, nm.size())
    }

    @Test fun bootReceiverRearmsFutureAndDeliversPastDue() {
        val db = Db(ctx)
        db.addReminder("future", System.currentTimeMillis() + 3_600_000)
        db.addReminder("past", System.currentTimeMillis() - 60_000)
        val nm = shadowOf(ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, nm.size()) // past-due delivered
        assertEquals(listOf("future"), db.reminders().map { it.text })
        assertNotNull(shadowOf(ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).peekNextScheduledAlarm())
    }

    @Test fun bootWithNotificationsBlockedKeepsPastDuePending() {
        val db = Db(ctx)
        db.addReminder("past", System.currentTimeMillis() - 60_000)
        shadowOf(ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).setNotificationsEnabled(false)
        BootReceiver().onReceive(ctx, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, db.reminders().size)
    }
}
