package com.lv999call.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.lv999call.app.data.local.dao.MemoryDao
import com.lv999call.app.data.local.dao.MessageDao
import com.lv999call.app.data.local.dao.PresetDao
import com.lv999call.app.data.local.dao.SessionDao
import com.lv999call.app.data.local.entity.MemoryEntity
import com.lv999call.app.data.local.entity.MessageEntity
import com.lv999call.app.data.local.entity.PresetEntity
import com.lv999call.app.data.local.entity.SessionEntity

@Database(
    entities = [SessionEntity::class, MessageEntity::class, PresetEntity::class, MemoryEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao
    abstract fun presetDao(): PresetDao
    abstract fun memoryDao(): MemoryDao

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE presets ADD COLUMN ttsPrompt TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * 3 → 4：长期记忆落地（plan4 §2.4）。
         *
         * ⚠️ 本迁移是全项目**唯一「写错就启动即崩」**的地方：
         * `exportSchema = false` 不比对 schema 文件，但 Room 首次打开数据库时会拿实体推导出的
         * 期望 schema 校验真实库结构 —— 表、列、**索引名**、外键逐项比对。所以下面的列顺序 /
         * 类型必须与 [MemoryEntity] 一致，索引名必须是 Room 的推导格式
         * （`index_<表>_<列1>_<列2>`），外键必须**手写进 CREATE TABLE**
         * （`MIGRATION_1_2` 那条没有任何外键，照抄它的风格会出事）。
         *
         * 而升级路径已经**没有破坏性兜底**（见 [getInstance]），一次失败就是用户启动即崩。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // (1) 记忆表：与 MemoryEntity 的字段、外键、三个索引逐字对齐
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS memories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        characterId TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        sessionId TEXT NOT NULL,
                        content TEXT NOT NULL,
                        contentHash TEXT NOT NULL,
                        category TEXT NOT NULL,
                        importance INTEGER NOT NULL,
                        lastUsedAt INTEGER NOT NULL,
                        sourceFromTs INTEGER NOT NULL,
                        sourceFromId INTEGER NOT NULL,
                        sourceToTs INTEGER NOT NULL,
                        sourceToId INTEGER NOT NULL,
                        FOREIGN KEY(sessionId) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_sessionId ON memories(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_memories_characterId ON memories(characterId)")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_memories_characterId_contentHash " +
                        "ON memories(characterId, contentHash)"
                )

                // (2) 会话加角色键与两个游标列。
                //     用 ALTER 而不是重建表：必须保留存量会话与消息（去掉破坏性兜底的初衷就是不清历史）。
                //     `NOT NULL DEFAULT` 不能省 —— 存量行要立刻拿到非空值，否则 Room 校验 NOT NULL 失败。
                //     characterKey 只能填 'default'：历史数据里推不出角色，而老会话本来也没有记忆。
                db.execSQL("ALTER TABLE sessions ADD COLUMN characterKey TEXT NOT NULL DEFAULT 'default'")
                db.execSQL("ALTER TABLE sessions ADD COLUMN savedMemoryUpToTs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN savedMemoryUpToId INTEGER NOT NULL DEFAULT 0")

                // (3) 存量会话的游标初始化到「它自己的最后一条消息」。
                //     这是产品决定，不是技术便利：老对话在升级前没人指望它被记住，就当已总结过。
                //     若留 0，补总结会把全部历史会话逐个重跑一遍 LLM（每次 3 个的上限只拖延、不阻止，
                //     多开几次电话就全跑完了）—— plan4 P8。
                //     用相关子查询取末条，排序口径与 MessageDao 的 (timestamp, id) 一致。
                db.execSQL("""
                    UPDATE sessions SET
                        savedMemoryUpToTs = COALESCE((
                            SELECT m.timestamp FROM messages m WHERE m.sessionId = sessions.id
                            ORDER BY m.timestamp DESC, m.id DESC LIMIT 1), 0),
                        savedMemoryUpToId = COALESCE((
                            SELECT m.id FROM messages m WHERE m.sessionId = sessions.id
                            ORDER BY m.timestamp DESC, m.id DESC LIMIT 1), 0)
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    // 只对「降级」清空。原来是 fallbackToDestructiveMigration()：
                    // 有了记忆表之后它意味着**用户升级 APK 就清空整部聊天历史**（一个以"记住你"
                    // 为卖点的功能，第一次更新就把东西全删了），所以升级路径必须靠上面的迁移。
                    // Room 2.6.1 里 fallbackToDestructiveMigrationOnDowngrade() 存在且无参版本
                    // 就是「只清降级」（带 dropAllTables 的新签名是 2.7 才有的，这里没有）。
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
