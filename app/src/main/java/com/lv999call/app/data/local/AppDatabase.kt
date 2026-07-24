package com.lv999call.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lv999call.app.data.local.dao.MessageDao
import com.lv999call.app.data.local.dao.PresetDao
import com.lv999call.app.data.local.dao.SessionDao
import com.lv999call.app.data.local.entity.MessageEntity
import com.lv999call.app.data.local.entity.PresetEntity
import com.lv999call.app.data.local.entity.SessionEntity

@Database(
    entities = [SessionEntity::class, MessageEntity::class, PresetEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun presetDao(): PresetDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS presets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        prompt TEXT NOT NULL DEFAULT '',
                        refAudioBase64 TEXT NOT NULL DEFAULT '',
                        refAudioMime TEXT NOT NULL DEFAULT 'audio/wav',
                        avatarUri TEXT NOT NULL DEFAULT '',
                        backgroundUri TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lv999call_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
