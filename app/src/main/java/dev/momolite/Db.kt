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
class Db(context: Context) : SQLiteOpenHelper(context, "momolite.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conversations(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, conv_id INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, ts INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, at INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'pending')")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) db.execSQL("ALTER TABLE reminders ADD COLUMN state TEXT NOT NULL DEFAULT 'pending'")
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
        writableDatabase.delete("messages", "conv_id=?", arrayOf(id.toString()))
        writableDatabase.delete("conversations", "id=?", arrayOf(id.toString()))
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
}
