package dev.momolite

import android.Manifest
import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

class MainActivity : Activity() {
    private lateinit var db: Db
    private var convId = 0L
    private val shown = ArrayList<Msg>()
    private lateinit var msgAdapter: BaseAdapter
    private val pick: Calendar = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        db = Db(this)

        msgAdapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(p: Int) = shown[p]
            override fun getItemId(p: Int) = shown[p].id
            override fun getView(p: Int, v: View?, parent: ViewGroup): View {
                val row = v ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_message, parent, false)
                Bubbles.apply(row.findViewById(R.id.bubble), shown[p])
                return row
            }
        }
        findViewById<ListView>(R.id.messages).adapter = msgAdapter

        // Resume last conversation or start the first one.
        val last = db.conversations().firstOrNull()
        openConversation(last?.id ?: db.newConversation(Titles.FALLBACK))

        findViewById<Button>(R.id.send).setOnClickListener { send() }
        findViewById<Button>(R.id.new_chat).setOnClickListener {
            openConversation(db.newConversation(Titles.FALLBACK)); show(R.id.page_chat)
        }
        findViewById<TextView>(R.id.tab_chat).setOnClickListener { show(R.id.page_chat) }
        findViewById<TextView>(R.id.tab_history).setOnClickListener { refreshHistory(); show(R.id.page_history) }
        findViewById<TextView>(R.id.tab_reminders).setOnClickListener { refreshReminders(); show(R.id.page_reminders) }
        findViewById<TextView>(R.id.tab_settings).setOnClickListener { refreshSettings(); show(R.id.page_settings) }

        setupReminders()
        setupSettings()
        // Opening the app also recovers reminders: re-arms lost alarms and delivers
        // anything missed while the phone was off (kept pending if notifications are blocked).
        val recovery = Reminders.rescheduleAll(this)
        if (recovery.scheduleFailed > 0) {
            Toast.makeText(this, getString(R.string.reminder_reschedule_failed, recovery.scheduleFailed), Toast.LENGTH_LONG).show()
        }
        show(R.id.page_chat)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS && grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.reminder_notifications_blocked, Toast.LENGTH_LONG).show()
        }
    }

    private fun show(page: Int) {
        listOf(R.id.page_chat, R.id.page_history, R.id.page_reminders, R.id.page_settings).forEach {
            findViewById<View>(it).visibility = if (it == page) View.VISIBLE else View.GONE
        }
        val tabs = mapOf(R.id.page_chat to R.id.tab_chat, R.id.page_history to R.id.tab_history, R.id.page_reminders to R.id.tab_reminders, R.id.page_settings to R.id.tab_settings)
        tabs.forEach { (p, t) ->
            val tv = findViewById<TextView>(t)
            tv.setTextColor(if (p == page) Color.parseColor("#1F4E79") else Color.GRAY)
            tv.setTypeface(null, if (p == page) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    private fun openConversation(id: Long) {
        convId = id
        shown.clear(); shown.addAll(db.messages(id)); msgAdapter.notifyDataSetChanged()
    }

    private fun send() {
        val input = findViewById<EditText>(R.id.input)
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        shown.add(db.addMessage(convId, "user", text))
        if (shown.count { it.role == "user" } == 1) db.renameConversation(convId, Titles.derive(text))
        msgAdapter.notifyDataSetChanged()
        val sentConv = convId
        val enabled = ApiSettings.isEnabled(this)
        val token = ApiSettings.token(this)
        val account = ApiSettings.accountId(this)
        if (!ProviderChoice.useCloudflare(enabled, !token.isNullOrBlank(), !account.isNullOrBlank())) {
            // Honest non-provider states: turned off, or turned on without saved credentials.
            shown.add(db.addMessage(convId, "system", getString(if (enabled) R.string.chat_provider_no_key else R.string.no_provider)))
            msgAdapter.notifyDataSetChanged()
            return
        }
        CloudflareWorkersAi(account!!, token!!).replyDetailed(ArrayList(shown)) { res ->
            runOnUiThread {
                // A late reply after the conversation was switched or all data was deleted is dropped.
                if (sentConv != convId) return@runOnUiThread
                when (res) {
                    is ProviderResult.Reply -> shown.add(db.addMessage(convId, "assistant", res.text))
                    is ProviderResult.Failed -> shown.add(db.addMessage(convId, "system", getString(when (res.kind) {
                        ProviderError.NETWORK -> R.string.provider_error_network
                        ProviderError.AUTH -> R.string.provider_error_auth
                        ProviderError.RATE_LIMIT -> R.string.provider_error_rate
                        ProviderError.SERVER -> R.string.provider_error_server
                        ProviderError.BAD_RESPONSE -> R.string.provider_error_badreply
                    })))
                }
                msgAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun refreshHistory() {
        val list = db.conversations()
        findViewById<View>(R.id.history_empty).visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val lv = findViewById<ListView>(R.id.history_list)
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        lv.adapter = rowAdapter(list.size,
            { i -> list[i].title to fmt.format(Date(list[i].updated)) },
            { i ->
                val c = list[i]
                confirmDelete(c.title) {
                    db.deleteConversation(c.id)
                    if (c.id == convId) openConversation(db.conversations().firstOrNull()?.id ?: db.newConversation(Titles.FALLBACK))
                    refreshHistory()
                } })
        lv.setOnItemClickListener { _, _, i, _ -> openConversation(list[i].id); show(R.id.page_chat) }
    }

    private fun refreshReminders() {
        val list = db.reminders()
        findViewById<View>(R.id.reminders_empty).visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val now = System.currentTimeMillis()
        val canNotify = Reminders.notificationsEnabled(this)
        findViewById<ListView>(R.id.reminders_list).adapter = rowAdapter(list.size,
            { i ->
                val r = list[i]
                val sub = when {
                    r.at > now -> fmt.format(Date(r.at))
                    canNotify -> getString(R.string.reminder_missed_retry)
                    else -> getString(R.string.reminder_missed_blocked)
                }
                r.text to sub
            },
            { i -> Reminders.cancel(this, list[i].id); db.deleteReminder(list[i].id); refreshReminders() })
    }

    private fun confirmDelete(title: String, onConfirm: () -> Unit) {
        android.app.AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_conversation_confirm, title))
            .setPositiveButton(R.string.delete) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun rowAdapter(n: Int, content: (Int) -> Pair<String, String>, onDelete: (Int) -> Unit) = object : BaseAdapter() {
        override fun getCount() = n
        override fun getItem(p: Int) = p
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, v: View?, parent: ViewGroup): View {
            val row = v ?: LayoutInflater.from(this@MainActivity).inflate(R.layout.item_row, parent, false)
            val (t, sub) = content(p)
            row.findViewById<TextView>(R.id.row_title).text = t
            row.findViewById<TextView>(R.id.row_sub).text = sub
            row.findViewById<Button>(R.id.row_delete).setOnClickListener { onDelete(p) }
            return row
        }
    }

    private fun setupReminders() {
        val dateBtn = findViewById<Button>(R.id.pick_date)
        val timeBtn = findViewById<Button>(R.id.pick_time)
        fun labels() {
            dateBtn.text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(pick.time)
            timeBtn.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(pick.time)
        }
        labels()
        dateBtn.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d -> pick.set(y, m, d); labels() },
                pick.get(Calendar.YEAR), pick.get(Calendar.MONTH), pick.get(Calendar.DAY_OF_MONTH)).show()
        }
        timeBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, mi -> pick.set(Calendar.HOUR_OF_DAY, h); pick.set(Calendar.MINUTE, mi); pick.set(Calendar.SECOND, 0); labels() },
                pick.get(Calendar.HOUR_OF_DAY), pick.get(Calendar.MINUTE), false).show()
        }
        findViewById<Button>(R.id.add_reminder).setOnClickListener {
            val et = findViewById<EditText>(R.id.reminder_text)
            val text = et.text.toString().trim()
            when (ReminderRules.validate(text, pick.timeInMillis, System.currentTimeMillis())) {
                ReminderError.EMPTY_TEXT -> { Toast.makeText(this, R.string.reminder_empty, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                ReminderError.TIME_IN_PAST -> { Toast.makeText(this, R.string.reminder_in_past, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                null -> Unit
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            }
            val id = db.addReminder(text, pick.timeInMillis)
            if (!Reminders.schedule(this, Reminder(id, text, pick.timeInMillis))) {
                Toast.makeText(this, R.string.reminder_schedule_failed, Toast.LENGTH_LONG).show()
            }
            et.setText(""); refreshReminders()
        }
    }

    private fun setupSettings() {
        findViewById<Button>(R.id.save_settings).setOnClickListener {
            val token = findViewById<EditText>(R.id.settings_token).text.toString().trim()
            val account = findViewById<EditText>(R.id.settings_account).text.toString().trim()
            ApiSettings.saveCredentials(this, token, account)
            findViewById<EditText>(R.id.settings_token).setText("")
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            refreshSettings()
        }
        findViewById<CheckBox>(R.id.enable_provider).setOnCheckedChangeListener { _, on ->
            ApiSettings.setEnabled(this, on); refreshSettings()
        }
        findViewById<Button>(R.id.delete_all_data).setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setMessage(getString(R.string.delete_all_confirm))
                .setPositiveButton(R.string.delete) { _, _ -> deleteAllData() }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun refreshSettings() {
        val enabled = ApiSettings.isEnabled(this)
        val hasKey = ApiSettings.hasCredentials(this)
        findViewById<CheckBox>(R.id.enable_provider).isChecked = enabled
        findViewById<TextView>(R.id.settings_status).text = getString(when {
            !enabled -> R.string.settings_status_off
            !hasKey -> R.string.settings_status_no_key
            else -> R.string.settings_status_on
        })
    }

    private fun deleteAllData() {
        db.reminders().forEach { Reminders.cancel(this, it.id) }
        db.clearAll()
        ApiSettings.clear(this)
        openConversation(db.newConversation(Titles.FALLBACK))
        refreshSettings()
        Toast.makeText(this, R.string.delete_all_done, Toast.LENGTH_LONG).show()
    }

    companion object { private const val REQ_NOTIFICATIONS = 1 }
}
