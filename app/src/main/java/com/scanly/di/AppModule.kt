package com.scanly.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.work.WorkManager
import com.scanly.data.db.DbEncryption
import com.scanly.data.db.ScanlyDao
import com.scanly.data.db.ScanlyDatabase
import com.scanly.data.prefs.SecurityPrefs
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** v1 → v2: user tags + per-document lock flag. (Top-level: Dagger's validator
 *  rejects anonymous-class fields inside @Module objects.) */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE documents ADD COLUMN tags TEXT")
        db.execSQL("ALTER TABLE documents ADD COLUMN locked INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Core app bindings shared by both flavors. Flavor-specific bindings (detector, OCR,
 * tip jar) live in FossModule / GplayModule.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
        securityPrefs: SecurityPrefs,
    ): ScanlyDatabase {
        // Encryption at rest: SQLCipher with a random Keystore-protected passphrase.
        // OCR text and document metadata are unreadable without unlocking the device.
        System.loadLibrary("sqlcipher")
        val passphrase = securityPrefs.dbPassphrase()
        DbEncryption.encryptLegacyPlaintext(context, "scanly.db", passphrase)
        // Real migrations only — a scanner app must never silently drop user documents
        // on upgrade, so there is deliberately NO destructive fallback.
        return Room.databaseBuilder(context, ScanlyDatabase::class.java, "scanly.db")
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(MIGRATION_1_2)
            .build()
    }

    @Provides
    fun dao(db: ScanlyDatabase): ScanlyDao = db.dao()

    @Provides
    @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
