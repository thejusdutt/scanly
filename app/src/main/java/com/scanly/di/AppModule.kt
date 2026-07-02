package com.scanly.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.scanly.data.db.ScanlyDao
import com.scanly.data.db.ScanlyDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Core app bindings shared by both flavors. Flavor-specific bindings (detector, OCR,
 * tip jar) live in FossModule / GplayModule.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ScanlyDatabase =
        // To enable at-rest encryption, pass SupportFactory(passphrase) via
        // .openHelperFactory(...) once the user sets a vault passphrase (Phase 6).
        Room.databaseBuilder(context, ScanlyDatabase::class.java, "scanly.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun dao(db: ScanlyDatabase): ScanlyDao = db.dao()

    @Provides
    @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
