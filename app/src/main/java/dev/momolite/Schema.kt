package dev.momolite

/** Database schema and upgrade steps as plain SQL, so migrations are testable on a JVM with real SQLite. */
object Schema {
    const val VERSION = 3

    val CREATE_V3 = listOf(
        "CREATE TABLE conversations(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, updated INTEGER NOT NULL)",
        "CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT, conv_id INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL, ts INTEGER NOT NULL)",
        "CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, at INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'pending')",
        "CREATE INDEX idx_messages_conv ON messages(conv_id)",
        "CREATE INDEX idx_conversations_updated ON conversations(updated)"
    )

    /** Statements that move a database from [from] to the current [VERSION]. Data is kept. */
    fun upgrade(from: Int): List<String> {
        val out = ArrayList<String>()
        if (from < 2) out.add("ALTER TABLE reminders ADD COLUMN state TEXT NOT NULL DEFAULT 'pending'")
        if (from < 3) {
            out.add("CREATE INDEX IF NOT EXISTS idx_messages_conv ON messages(conv_id)")
            out.add("CREATE INDEX IF NOT EXISTS idx_conversations_updated ON conversations(updated)")
        }
        return out
    }
}
