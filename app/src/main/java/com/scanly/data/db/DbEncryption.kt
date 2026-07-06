package com.scanly.data.db

import android.content.Context
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * One-time in-place encryption of a legacy PLAINTEXT database. Users upgrading from a
 * pre-encryption build must keep their documents: sqlcipher_export copies the schema and
 * data into an encrypted file, which then replaces the original.
 *
 * Four hard-won rules (each cost a failed upgrade test):
 *  1. The legacy app ran Room in WAL mode, so at process death the ENTIRE content can
 *     sit in `name-wal` while the main file is an empty 4 KB shell. The WAL must be
 *     checkpointed into the main file — by the framework SQLite that wrote it — before
 *     the export, or the "conversion" silently produces an empty database.
 *  2. The export must run on a connection whose MAIN database is the keyed (encrypted)
 *     one, with the plaintext file ATTACHed KEY ''. The reverse direction — plaintext
 *     main, `ATTACH … KEY x` — silently exports nothing in sqlcipher-android, because
 *     the codec is inactive on an empty-key connection.
 *  3. sqlcipher_export does not copy PRAGMA user_version; it must be restored on the
 *     copy or Room mistakes the migrated data for a fresh database and rejects it.
 *  4. The plaintext original is only replaced after verifying the encrypted copy
 *     actually contains the schema. A failed conversion throws and leaves the user's
 *     data untouched for a retry on next launch; it must never delete-first.
 */
object DbEncryption {

    /** SQLite plaintext files start with this exact 16-byte header (NUL-terminated). */
    private val PLAINTEXT_HEADER =
        "SQLite format 3".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

    fun encryptLegacyPlaintext(context: Context, name: String, passphrase: ByteArray) {
        val dbFile = context.getDatabasePath(name)
        if (!dbFile.exists() || !isPlaintext(dbFile)) return

        // Fold any WAL into the main file using the SAME engine that produced it, and
        // record what the encrypted copy must end up with: the schema entry count and
        // the Room schema version (sqlcipher_export does NOT copy user_version — without
        // restoring it Room treats the copy as brand new and rejects its "old" schema).
        val sourceEntries: Long
        val userVersion: Long
        android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        ).use { framework ->
            framework.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            sourceEntries = framework.rawQuery("SELECT COUNT(*) FROM sqlite_master", null)
                .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            userVersion = framework.rawQuery("PRAGMA user_version", null)
                .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        }

        val encrypted = File(dbFile.parentFile, "$name.enc")
        encrypted.delete()
        val target = SQLiteDatabase.openDatabase(
            encrypted.absolutePath, passphrase, null, SQLiteDatabase.CREATE_IF_NECESSARY, null,
        )
        try {
            target.rawExecSQL("ATTACH DATABASE '${dbFile.absolutePath}' AS plaintext KEY ''")
            target.rawExecSQL("SELECT sqlcipher_export('main', 'plaintext')")
            target.rawExecSQL("DETACH DATABASE plaintext")
            target.rawExecSQL("PRAGMA user_version = $userVersion")
        } finally {
            target.close()
        }

        // Verify BEFORE the destructive swap: the copy must hold the source's schema.
        val copy = SQLiteDatabase.openDatabase(
            encrypted.absolutePath, passphrase, null, SQLiteDatabase.OPEN_READONLY, null,
        )
        val copiedEntries = try {
            copy.rawQuery("SELECT COUNT(*) FROM sqlite_master", null)
                .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
        } finally {
            copy.close()
        }
        check(copiedEntries >= sourceEntries) {
            "Encrypted export incomplete ($copiedEntries/$sourceEntries schema entries) — keeping plaintext"
        }

        // Swap in the encrypted file; stale WAL/SHM belong to the plaintext file.
        File(dbFile.parentFile, "$name-wal").delete()
        File(dbFile.parentFile, "$name-shm").delete()
        File(dbFile.parentFile, "$name-journal").delete()
        dbFile.delete()
        encrypted.renameTo(dbFile)
    }

    private fun isPlaintext(file: File): Boolean = runCatching {
        val header = ByteArray(PLAINTEXT_HEADER.size)
        file.inputStream().use { it.read(header) }
        header.contentEquals(PLAINTEXT_HEADER)
    }.getOrDefault(false)
}
