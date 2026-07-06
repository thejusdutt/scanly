package com.scanly.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.scanly.common.Filter

class Converters {
    @TypeConverter fun filterToString(f: Filter): String = f.name
    @TypeConverter fun stringToFilter(s: String): Filter = Filter.valueOf(s)
    @TypeConverter fun ocrToString(s: OcrStatus): String = s.name
    @TypeConverter fun stringToOcr(s: String): OcrStatus = OcrStatus.valueOf(s)
}

@Database(
    entities = [DocumentEntity::class, PageEntity::class, PageFts::class],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ScanlyDatabase : RoomDatabase() {
    abstract fun dao(): ScanlyDao
}
