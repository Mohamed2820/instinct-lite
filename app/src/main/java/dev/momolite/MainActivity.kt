package dev.momolite

import android.Manifest
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var db: Db
    private var convId = 0L
    private val shown = ArrayList<Msg>()
    private lateinit var msgAdapter: MessageAdapter
    private val pick: Calendar = Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.SECOND, 0) }
    private val worker = Executors.newSingleThreadExecutor()
    private var pending: CancelHandle? = null
    private var historyCache: List<Conv> = emptyList()
    private var reminderCache: List<Reminder> = emptyList()
    private var currentPage = R.id.page_chat

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val ui = getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (!ui.getBoolean("welcomed", false)) {
            setContentView(R.layout.activity_welcome)
            findViewById<View>(R.id.welcome_start).setOnClickListener {
                ui.edit().putBoolean("welcomed", true).apply()
                startMain()
            }
            return
        }
        startMain()
    }

    private fun startMain() {
        setContentView(R.layout.activity_main)
        db = Db(this)

        msgAdapter = MessageAdapter(shown)
        findViewById<RecyclerView>(R.id.messages).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply { stackFromEnd = true }
            adapter = msgAdapter
        }
        findViewById<RecyclerView>(R.id.history_list).layoutManager = LinearLayoutManager(this)
        findViewById<RecyclerView>(R.id.reminders_list).layoutManager = LinearLayoutManager(this)

        val last = db.conversations().firstOrNull()
        openConversation(last?.id ?: db.newConversation(Titles.FALLBACK))

        findViewById<FloatingActionButton>(R.id.send).setOnClickListener { if (pending != null) cancelRequest() else send() }
        findViewById<View>(R.id.new_chat).setOnClickListener {
            cancelRequest(); openConversation(db.newConversation(Titles.FALLBACK)); show(R.id.page_chat)
        }
        findViewById<BottomNavigationView>(R.id.bottom_nav).setOnItemSelectedListener {
            when (it.itemId) {
                R.id.nav_chat -> show(R.id.page_chat)
                R.id.nav_history -> show(R.id.page_history)
                R.id.nav_reminders -> show(R.id.page_reminders)
                R.id.nav_settings -> show(R.id.page_settings)
            }
            true
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentPage) {
                    R.id.page_privacy -> show(R.id.page_settings)
                    R.id.page_chat -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                    else -> findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_chat
                }
            }
        })

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

    override fun onDestroy() {
        pending?.cancel()
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATIONS && grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.reminder_notifications_blocked, Toast.LENGTH_LONG).show()
        }
    }

    private fun show(page: Int) {
        currentPage = page
        listOf(R.id.page_chat, R.id.page_history, R.id.page_reminders, R.id.page_settings, R.id.page_privacy).forEach {
            val v = findViewById<View>(it)
            if (it == page) {
                if (v.visibility != View.VISIBLE) { v.alpha = 0f; v.visibility = View.VISIBLE; v.animate().alpha(1f).setDuration(180).start() }
            } else v.visibility = View.GONE
        }
        when (page) {
            R.id.page_history -> refreshHistory()
            R.id.page_reminders -> refreshReminders()
            R.id.page_settings -> refreshSettings()
        }
        // The key field can be on screen only in Settings: block screenshots/recents thumbnails there.
        if (page == R.id.page_settings) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private fun openConversation(id: Long) {
        convId = id
        shown.clear(); shown.addAll(db.messages(id)); msgAdapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.chat_title).text = db.conversations().firstOrNull { it.id == id }?.title ?: getString(R.string.app_name)
        syncChatUi()
    }

    private fun syncChatUi() {
        findViewById<View>(R.id.chat_empty).visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.progress).visibility = if (pending != null) View.VISIBLE else View.GONE
        val fab = findViewById<FloatingActionButton>(R.id.send)
        fab.setImageResource(if (pending != null) R.drawable.ic_stop else R.drawable.ic_send)
        fab.contentDescription = getString(if (pending != null) R.string.stop else R.string.send)
        if (shown.isNotEmpty()) findViewById<RecyclerView>(R.id.messages).scrollToPosition(shown.size - 1)
    }

    private fun addShown(role: String, text: String) {
        shown.add(db.addMessage(convId, role, text))
        msgAdapter.notifyItemInserted(shown.size - 1)
        syncChatUi()
    }

    private fun cancelRequest() { pending?.cancel(); pending = null; syncChatUi() }

    private fun send() {
        val input = findViewById<TextInputEditText>(R.id.input)
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        addShown("user", text)
        if (shown.count { it.role == "user" } == 1) {
            db.renameConversation(convId, Titles.derive(text))
            findViewById<TextView>(R.id.chat_title).text = Titles.derive(text)
        }
        val config = ApiSettings.config(this)
        when (ProviderGate.state(ApiSettings.isEnabled(this), config)) {
            ProviderState.OFF -> { addShown("system", getString(R.string.no_provider)); return }
            ProviderState.INCOMPLETE -> { addShown("system", getString(R.string.chat_provider_incomplete)); return }
            ProviderState.READY -> Unit
        }
        val sentConv = convId
        val handle = CancelHandle()
        pending = handle
        syncChatUi()
        worker.execute {
            val res = ProviderClient().send(config!!, text, handle)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (pending === handle) pending = null
                // A reply that arrives after the conversation was switched, deleted or cancelled is dropped.
                if (sentConv != convId || handle.cancelled && res is ProviderResult.Reply) { syncChatUi(); return@runOnUiThread }
                when (res) {
                    is ProviderResult.Reply -> addShown("assistant", res.text)
                    is ProviderResult.Failed -> addShown("system", getString(errorText(res.kind)))
                }
                syncChatUi()
            }
        }
    }

    private fun errorText(k: ProviderError) = when (k) {
        ProviderError.NETWORK -> R.string.provider_error_network
        ProviderError.TIMEOUT -> R.string.provider_error_timeout
        ProviderError.AUTH -> R.string.provider_error_auth
        ProviderError.RATE_LIMIT -> R.string.provider_error_rate
        ProviderError.QUOTA -> R.string.provider_error_quota
        ProviderError.SERVER -> R.string.provider_error_server
        ProviderError.BAD_RESPONSE -> R.string.provider_error_badreply
        ProviderError.EMPTY -> R.string.provider_error_empty
        ProviderError.CANCELLED -> R.string.provider_error_cancelled
        ProviderError.BAD_CONFIG -> R.string.provider_error_config
    }

    // ---- history ----
    private fun refreshHistory() {
        historyCache = db.conversations()
        findViewById<View>(R.id.history_empty).visibility = if (historyCache.isEmpty()) View.VISIBLE else View.GONE
        val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val rv = findViewById<RecyclerView>(R.id.history_list)
        rv.adapter = RowAdapter({ historyCache.map { Row(it.title, fmt.format(Date(it.updated))) } },
            { i -> historyCache.getOrNull(i)?.let { cancelRequest(); openConversation(it.id); findViewById<BottomNavigationView>(R.id.bottom_nav).selectedItemId = R.id.nav_chat } },
            { i ->
                val c = historyCache.getOrNull(i) ?: return@RowAdapter
                MaterialAlertDialogBuilder(this)
                    .setMessage(getString(R.string.delete_conversation_confirm, c.title))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        if (c.id == convId) cancelRequest()
                        db.deleteConversation(c.id)
                        if (c.id == convId) openConversation(db.conversations().firstOrNull()?.id ?: db.newConversation(Titles.FALLBACK))
                        refreshHistory()
                    }
                    .setNegativeButton(R.string.cancel, null).show()
            })
    }

    // ---- reminders ----
    private fun refreshReminders() {
        reminderCache = db.reminders()
        findViewById<View>(R.id.reminders_empty).visibility = if (reminderCache.isEmpty()) View.VISIBLE else View.GONE
        val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val now = System.currentTimeMillis()
        val canNotify = Reminders.notificationsEnabled(this)
        findViewById<RecyclerView>(R.id.reminders_list).adapter = RowAdapter({
            reminderCache.map { r ->
                Row(r.text, when {
                    r.at > now -> fmt.format(Date(r.at))
                    canNotify -> getString(R.string.reminder_missed_retry)
                    else -> getString(R.string.reminder_missed_blocked)
                })
            }
        }, null) { i ->
            val r = reminderCache.getOrNull(i) ?: return@RowAdapter
            MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.delete_reminder_confirm, r.text))
                .setPositiveButton(R.string.delete) { _, _ -> Reminders.cancel(this, r.id); db.deleteReminder(r.id); refreshReminders() }
                .setNegativeButton(R.string.cancel, null).show()
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
            val et = findViewById<TextInputEditText>(R.id.reminder_text)
            val text = ReminderRules.clean(et.text.toString())
            when (ReminderRules.validate(text, pick.timeInMillis, System.currentTimeMillis())) {
                ReminderError.EMPTY_TEXT -> { Toast.makeText(this, R.string.reminder_empty, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                ReminderError.TIME_IN_PAST -> { Toast.makeText(this, R.string.reminder_in_past, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                null -> Unit
            }
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
            } else if (!Reminders.notificationsEnabled(this)) {
                Toast.makeText(this, R.string.reminder_notifications_blocked, Toast.LENGTH_LONG).show()
            }
            val id = db.addReminder(text, pick.timeInMillis)
            if (!Reminders.schedule(this, Reminder(id, text, pick.timeInMillis))) {
                Toast.makeText(this, R.string.reminder_schedule_failed, Toast.LENGTH_LONG).show()
            } else Toast.makeText(this, R.string.reminder_saved, Toast.LENGTH_SHORT).show()
            et.setText(""); refreshReminders()
        }
    }

    // ---- settings ----
    private fun selectedType(): ProviderType = when (findViewById<RadioGroup>(R.id.provider_type).checkedRadioButtonId) {
        R.id.type_cloudflare -> ProviderType.CLOUDFLARE
        R.id.type_custom -> ProviderType.CUSTOM
        else -> ProviderType.OPENAI_COMPATIBLE
    }

    private fun text(id: Int) = findViewById<TextInputEditText>(id).text.toString().trim()

    private fun applyTypeUi() {
        val t = selectedType()
        findViewById<View>(R.id.layout_account).visibility = if (t == ProviderType.CLOUDFLARE) View.VISIBLE else View.GONE
        findViewById<View>(R.id.layout_address).visibility = if (t == ProviderType.CLOUDFLARE) View.GONE else View.VISIBLE
        findViewById<TextInputLayout>(R.id.layout_address).hint = getString(if (t == ProviderType.CUSTOM) R.string.settings_custom_hint else R.string.settings_base_hint)
    }

    private fun setupSettings() {
        findViewById<RadioGroup>(R.id.provider_type).setOnCheckedChangeListener { _, _ ->
            applyTypeUi()
            val m = findViewById<TextInputEditText>(R.id.settings_model)
            if (m.text.isNullOrBlank()) m.setText(ProviderChoice.defaultModel(selectedType()))
            val a = findViewById<TextInputEditText>(R.id.settings_address)
            if (a.text.isNullOrBlank() && selectedType() == ProviderType.OPENAI_COMPATIBLE) a.setText(ProviderChoice.DEFAULT_OPENAI_BASE)
        }
        findViewById<View>(R.id.save_settings).setOnClickListener {
            val key = text(R.id.settings_key)
            val ok = ApiSettings.save(this, selectedType(), key, text(R.id.settings_model), text(R.id.settings_account), text(R.id.settings_address))
            findViewById<TextInputEditText>(R.id.settings_key).setText("")
            Toast.makeText(this, if (ok) R.string.settings_saved else R.string.settings_save_failed, Toast.LENGTH_SHORT).show()
            refreshSettings()
        }
        findViewById<MaterialSwitch>(R.id.enable_provider).setOnCheckedChangeListener { b, on ->
            if (b.isPressed) { ApiSettings.setEnabled(this, on); refreshSettings() }
        }
        findViewById<View>(R.id.test_connection).setOnClickListener { testConnection() }
        findViewById<View>(R.id.open_privacy).setOnClickListener { show(R.id.page_privacy) }
        findViewById<View>(R.id.privacy_back).setOnClickListener { show(R.id.page_settings) }
        findViewById<View>(R.id.delete_all_data).setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(getString(R.string.delete_all_confirm))
                .setPositiveButton(R.string.delete) { _, _ -> deleteAllData() }
                .setNegativeButton(R.string.cancel, null).show()
        }
    }

    private fun testConnection() {
        val stored = ApiSettings.config(this)
        val typed = text(R.id.settings_key)
        val cfg = ProviderConfig(selectedType(), typed.ifBlank { stored?.apiKey.orEmpty() }, text(R.id.settings_model),
            text(R.id.settings_account), text(R.id.settings_address))
        val result = findViewById<TextView>(R.id.test_result)
        result.visibility = View.VISIBLE
        val missing = ProviderChoice.missing(cfg)
        if (missing.isNotEmpty()) { result.text = getString(R.string.settings_status_incomplete, missingText(missing)); return }
        result.setText(R.string.settings_testing)
        worker.execute {
            val res = ProviderClient().send(cfg, ProviderClient.TEST_PROMPT)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.text = if (res is ProviderResult.Reply) getString(R.string.settings_test_ok)
                else getString(errorText((res as ProviderResult.Failed).kind))
            }
        }
    }

    private fun missingText(m: List<MissingField>) = m.joinToString("، ") {
        getString(when (it) {
            MissingField.KEY -> R.string.missing_key
            MissingField.MODEL -> R.string.missing_model
            MissingField.ACCOUNT_ID -> R.string.missing_account
            MissingField.ADDRESS -> R.string.missing_address
            MissingField.ADDRESS_NOT_HTTPS -> R.string.missing_address_https
        })
    }

    private fun refreshSettings() {
        val cfg = ApiSettings.config(this)
        val type = cfg?.type ?: ProviderType.OPENAI_COMPATIBLE
        findViewById<RadioGroup>(R.id.provider_type).check(when (type) {
            ProviderType.CLOUDFLARE -> R.id.type_cloudflare
            ProviderType.CUSTOM -> R.id.type_custom
            else -> R.id.type_openai
        })
        applyTypeUi()
        findViewById<TextInputEditText>(R.id.settings_model).setText(cfg?.model?.ifBlank { null } ?: ProviderChoice.defaultModel(type))
        findViewById<TextInputEditText>(R.id.settings_account).setText(cfg?.accountId.orEmpty())
        findViewById<TextInputEditText>(R.id.settings_address).setText(
            cfg?.address?.ifBlank { null } ?: if (type == ProviderType.OPENAI_COMPATIBLE) ProviderChoice.DEFAULT_OPENAI_BASE else "")
        findViewById<TextInputLayout>(R.id.layout_key).hint = getString(if (ApiSettings.hasKey(this)) R.string.settings_key_saved_hint else R.string.settings_key_hint)
        val enabled = ApiSettings.isEnabled(this)
        findViewById<MaterialSwitch>(R.id.enable_provider).isChecked = enabled
        findViewById<TextView>(R.id.settings_status).text = when (ProviderGate.state(enabled, cfg)) {
            ProviderState.OFF -> getString(R.string.settings_status_off)
            ProviderState.INCOMPLETE -> getString(R.string.settings_status_incomplete, missingText(ProviderChoice.missing(cfg ?: ProviderConfig(type, "", ""))))
            ProviderState.READY -> getString(R.string.settings_status_on)
        }
    }

    private fun deleteAllData() {
        cancelRequest()
        db.reminders().forEach { Reminders.cancel(this, it.id) }
        db.clearAll()
        ApiSettings.clear(this)
        openConversation(db.newConversation(Titles.FALLBACK))
        refreshSettings()
        Toast.makeText(this, R.string.delete_all_done, Toast.LENGTH_LONG).show()
    }

    companion object { private const val REQ_NOTIFICATIONS = 1 }
}
