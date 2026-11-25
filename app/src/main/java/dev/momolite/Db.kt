package dev.momolite

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Msg(val id: Long, val role: String, val text: String, val ts: Long)
data class Conv(val id: Long, val title: String, val updated: Long)
const val REMINDER_PENDING = "pending"
const val REMINDER_DELIVERED = "delivered"

data class Reminder(val id: Long, val text: String, val at: Long, val state: String = REMINDER_PENDING)

/** Local SQLite storage. Nothing leaves the device. */
class Db(context: Context) : SQLiteOpenHelper(context, "momolite.db", null, Schema.VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        Schema.CREATE_V3.forEach { db.execSQL(it) }
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        Schema.upgrade(o).forEach { db.execSQL(it) }
    }

    fun newConversation(title: String): Long {
        val v = ContentValues().apply { put("title", title); put("updated", System.currentTimeMillis()) }
        return writableDatabase.insert("conversations", null, v)
    }
    fun renameConversation(id: Long, title: String) {
        val v = ContentValues().apply { put("title", title) }
        writableDatabase.update("conversations", v, "id=?", arrayOf(id.toString()))
    }
    fun conversations(): List<Conv> {
        val out = ArrayList<Conv>()
        readableDatabase.rawQuery("SELECT id,title,updated FROM conversations ORDER BY updated DESC", null).use {
            while (it.moveToNext()) out.add(Conv(it.getLong(0), it.getString(1), it.getLong(2)))
        }
        return out
    }
    fun deleteConversation(id: Long) {
        val d = writableDatabase
        d.beginTransaction()
        try {
            d.delete("messages", "conv_id=?", arrayOf(id.toString()))
            d.delete("conversations", "id=?", arrayOf(id.toString()))
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
    }
    fun addMessage(convId: Long, role: String, text: String): Msg {
        val now = System.currentTimeMillis()
        val v = ContentValues().apply { put("conv_id", convId); put("role", role); put("text", text); put("ts", now) }
        val id = writableDatabase.insert("messages", null, v)
        val u = ContentValues().apply { put("updated", now) }
        writableDatabase.update("conversations", u, "id=?", arrayOf(convId.toString()))
        return Msg(id, role, text, now)
    }
    fun messages(convId: Long): List<Msg> {
        val out = ArrayList<Msg>()
        readableDatabase.rawQuery("SELECT id,role,text,ts FROM messages WHERE conv_id=? ORDER BY id", arrayOf(convId.toString())).use {
            while (it.moveToNext()) out.add(Msg(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3)))
        }
        return out
    }
    fun addReminder(text: String, at: Long): Long {
        val v = ContentValues().apply { put("text", text); put("at", at) }
        return writableDatabase.insert("reminders", null, v)
    }
    fun reminders(): List<Reminder> {
        val out = ArrayList<Reminder>()
        readableDatabase.rawQuery("SELECT id,text,at,state FROM reminders ORDER BY at", null).use {
            while (it.moveToNext()) out.add(Reminder(it.getLong(0), it.getString(1), it.getLong(2), it.getString(3)))
        }
        return out
    }

    /** Marks a reminder as delivered before deleting it: a crash between the two never causes a replay. */
    fun markReminderDelivered(id: Long) {
        val v = ContentValues().apply { put("state", REMINDER_DELIVERED) }
        writableDatabase.update("reminders", v, "id=?", arrayOf(id.toString()))
    }
    fun deleteReminder(id: Long) { writableDatabase.delete("reminders", "id=?", arrayOf(id.toString())) }

    /** Wipes every locally stored row: conversations, messages and reminders. */
    fun clearAll() {
        val d = writableDatabase
        d.beginTransaction()
        try {
            d.delete("messages", null, null)
            d.delete("conversations", null, null)
            d.delete("reminders", null, null)
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
        d.execSQL("VACUUM") // physically drop deleted rows from the file
    }
}
