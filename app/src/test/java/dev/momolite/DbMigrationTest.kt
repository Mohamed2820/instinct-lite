package dev.momolite

import org.junit.Assert.*
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** Real SQLite (sqlite-jdbc) runs the exact SQL the app uses for create and upgrade. */
class DbMigrationTest {
    private fun conn(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private val v1 = listOf(
        "CREATE TABLE conversations(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, updated INTEGER NOT NULL)",
        "CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, conv_id INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, ts INTEGER NOT NULL)",
        "CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, at INTEGER NOT NULL)")

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }
    private fun Connection.scalar(sql: String): String? = createStatement().use { s -> s.executeQuery(sql).use { if (it.next()) it.getString(1) else null } }
    private fun Connection.names(type: String): Set<String> = createStatement().use { s ->
        s.executeQuery("SELECT name FROM sqlite_master WHERE type='$type'").use { r -> generateSequence { if (r.next()) r.getString(1) else null }.toSet() } }

    @Test fun freshInstallCreatesTablesAndIndexes() {
        conn().use { c ->
            Schema.CREATE_V3.forEach { c.exec(it) }
            assertTrue(c.names("table").containsAll(listOf("conversations", "messages", "reminders")))
            assertTrue(c.names("index").containsAll(listOf("idx_messages_conv", "idx_conversations_updated")))
            c.exec("INSERT INTO reminders(text, at) VALUES('x', 5)")
            assertEquals("pending", c.scalar("SELECT state FROM reminders"))
        }
    }

    @Test fun upgradeFromV1KeepsDataAndAddsStateColumnAndIndexes() {
        conn().use { c ->
            v1.forEach { c.exec(it) }
            c.exec("INSERT INTO conversations(title, updated) VALUES('محادثة', 10)")
            c.exec("INSERT INTO messages(conv_id, role, text, ts) VALUES(1, 'user', 'مرحبا', 11)")
            c.exec("INSERT INTO reminders(text, at) VALUES('دواء', 99)")
            Schema.upgrade(1).forEach { c.exec(it) }
            assertEquals("محادثة", c.scalar("SELECT title FROM conversations"))
            assertEquals("مرحبا", c.scalar("SELECT text FROM messages"))
            assertEquals("دواء", c.scalar("SELECT text FROM reminders"))
            assertEquals("pending", c.scalar("SELECT state FROM reminders"))
            assertTrue(c.names("index").contains("idx_messages_conv"))
        }
    }

    @Test fun upgradeFromV2OnlyAddsIndexes() {
        conn().use { c ->
            v1.forEach { c.exec(it) }
            c.exec("ALTER TABLE reminders ADD COLUMN state TEXT NOT NULL DEFAULT 'pending'")
            c.exec("INSERT INTO reminders(text, at, state) VALUES('x', 1, 'delivered')")
            assertEquals(2, Schema.upgrade(2).size)
            Schema.upgrade(2).forEach { c.exec(it) }
            assertEquals("delivered", c.scalar("SELECT state FROM reminders"))
            assertTrue(c.names("index").contains("idx_conversations_updated"))
        }
    }

    @Test fun upgradeIsIdempotentOnTheIndexSteps() {
        conn().use { c ->
            Schema.CREATE_V3.forEach { c.exec(it) }
            Schema.upgrade(2).forEach { c.exec(it) } // IF NOT EXISTS: must not fail on an already-current schema
        }
    }

    @Test fun noUpgradeStepsAtCurrentVersion() = assertTrue(Schema.upgrade(Schema.VERSION).isEmpty())

    @Test fun deleteConversationRemovesItsMessagesOnly() {
        conn().use { c ->
            Schema.CREATE_V3.forEach { c.exec(it) }
            c.exec("INSERT INTO conversations(title, updated) VALUES('a',1),('b',2)")
            c.exec("INSERT INTO messages(conv_id, role, text, ts) VALUES(1,'user','m1',1),(2,'user','m2',2)")
            // same statements Db.deleteConversation runs
            c.exec("DELETE FROM messages WHERE conv_id=1"); c.exec("DELETE FROM conversations WHERE id=1")
            assertEquals("1", c.scalar("SELECT count(*) FROM messages"))
            assertEquals("m2", c.scalar("SELECT text FROM messages"))
        }
    }

    @Test fun deleteAllLeavesEmptyTables() {
        conn().use { c ->
            Schema.CREATE_V3.forEach { c.exec(it) }
            c.exec("INSERT INTO conversations(title, updated) VALUES('a',1)")
            c.exec("INSERT INTO messages(conv_id, role, text, ts) VALUES(1,'user','m',1)")
            c.exec("INSERT INTO reminders(text, at) VALUES('r', 1)")
            listOf("messages", "conversations", "reminders").forEach { c.exec("DELETE FROM $it") }
            listOf("messages", "conversations", "reminders").forEach { assertEquals("0", c.scalar("SELECT count(*) FROM $it")) }
        }
    }
}
