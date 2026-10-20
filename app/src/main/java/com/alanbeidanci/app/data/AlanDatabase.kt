package com.alanbeidanci.app.data

import android.content.Context
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WordBookEntity::class,
        WordEntity::class,
        WordProgressEntity::class,
        ReviewLogEntity::class,
        StudySessionEntity::class,
        PendingSyncEventEntity::class,
        ServerStatsEntity::class,
        RetentionProfile::class,
        DualTestSessionEntity::class,
    ],
    version = 15,
    exportSchema = true,
)
abstract class AlanDatabase : RoomDatabase() {
    abstract fun retentionProfileDao(): RetentionProfileDao
    abstract fun dualTestSessionDao(): DualTestSessionDao
    abstract fun wordBookDao(): WordBookDao
    abstract fun wordDao(): WordDao
    abstract fun progressDao(): ProgressDao
    abstract fun reviewLogDao(): ReviewLogDao
    abstract fun studySessionDao(): StudySessionDao
    abstract fun pendingSyncDao(): PendingSyncDao
    abstract fun serverStatsDao(): ServerStatsDao

    companion object {
        fun create(context: Context): AlanDatabase = Room.databaseBuilder(
            context.applicationContext,
            AlanDatabase::class.java,
            "alan_vocab.db",
        ).addMigrations(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
            MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15,
        ).build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE words ADD COLUMN unitId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE words ADD COLUMN unitName TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS study_sessions (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "mode TEXT NOT NULL, " +
                        "wordCount INTEGER NOT NULL, " +
                        "completed INTEGER NOT NULL, " +
                        "durationMs INTEGER NOT NULL, " +
                        "startedAt INTEGER NOT NULL, " +
                        "finishedAt INTEGER NOT NULL)",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE words ADD COLUMN folderId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE words ADD COLUMN folderName TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE word_progress ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_sync_events (" +
                        "eventId TEXT NOT NULL PRIMARY KEY, " +
                        "deviceId TEXT NOT NULL, " +
                        "wordId INTEGER NOT NULL, " +
                        "eventType TEXT NOT NULL, " +
                        "correct INTEGER, " +
                        "favorite INTEGER, " +
                        "clientTime TEXT NOT NULL, " +
                        "clientSeq INTEGER NOT NULL)",
                )
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE word_progress ADD COLUMN category TEXT NOT NULL DEFAULT 'learning'")
                db.execSQL("UPDATE word_progress SET category = 'mastered' WHERE isMastered = 1")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN level INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN passStreak INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN lastListenPass INTEGER")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN lastChinesePass INTEGER")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN lastSpotCheckAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN masteryStreak INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN fixedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN mainlineDualPending INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN mainlineClassifiedDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN mainlineRemedyReviewDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN mainlineRemedyTestDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN mainlineLearnedDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN mainlineDualDeferredDate TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN isDual INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN isMainline INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN isTest INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_sync_events ADD COLUMN progressStateJson TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE study_sessions ADD COLUMN sessionId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE study_sessions ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS server_stats (" +
                        "key TEXT NOT NULL PRIMARY KEY, " +
                        "payloadJson TEXT NOT NULL)",
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending_sync_events ADD COLUMN isDual INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_sync_events ADD COLUMN isMainline INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_sync_events ADD COLUMN isTest INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE study_sessions ADD COLUMN typedChars INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE study_sessions ADD COLUMN correctChars INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pending_sync_events ADD COLUMN spellingQuality TEXT")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE word_progress ADD COLUMN mainlineConsolidationDate TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE word_progress ADD COLUMN stabilityDays REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN difficulty REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN practiceHits INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN practiceMisses INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN practiceHinted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN practiceForm TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN firstResult TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN abilityMeaning INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN abilityListening INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN abilitySpelling INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN abilityContext INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN firstAttempt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN hinted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN practiceForm TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN eventKind TEXT NOT NULL DEFAULT 'answer'")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN scheduleGrade TEXT NOT NULL DEFAULT ''")
            }
        }
        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE word_progress ADD COLUMN answerExposedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN memoryExposed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE word_progress SET answerExposedAt = MAX(lastReviewedAt, practiceSavedAt, COALESCE((SELECT MAX(timestamp) FROM review_logs WHERE review_logs.wordId = word_progress.wordId), 0))")
                db.execSQL("CREATE TABLE IF NOT EXISTS dual_test_sessions (`key` TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL)")
                // Retain personal/previous parameters and all existing dates. Do not replay old evidence as v2.
                db.execSQL("UPDATE retention_profile SET algorithm = 'alan-fsrs5-calibration-v2', generation = generation || '-v2', lastAttemptAt = 0, lastAttemptThrough = 0, trainedThrough = 0, activatedAt = 0, sampleCount = 0, candidateLoss = -1.0, baselineLoss = -1.0, status = 'COLLECTING' WHERE algorithm = 'alan-fsrs5-calibration-v1'")
            }
        }
        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE word_progress ADD COLUMN skillMemory TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN practicePlan TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE word_progress ADD COLUMN practiceSavedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN memorySkill TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN memoryWeight REAL NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN memorySource TEXT NOT NULL DEFAULT ''")
            }
        }
        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE review_logs ADD COLUMN memoryAlgorithm TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN memoryBeforeStability REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN memoryBeforeDifficulty REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN memoryPreviousReviewAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN memoryIndependent INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE review_logs ADD COLUMN memoryModelRevision INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS retention_profile (id INTEGER NOT NULL PRIMARY KEY, generation TEXT NOT NULL, algorithm TEXT NOT NULL, autoEnabled INTEGER NOT NULL, gain REAL NOT NULL, lapse REAL NOT NULL, previousGain REAL NOT NULL, previousLapse REAL NOT NULL, revision INTEGER NOT NULL, lastAttemptAt INTEGER NOT NULL, lastAttemptThrough INTEGER NOT NULL, trainedThrough INTEGER NOT NULL, activatedAt INTEGER NOT NULL, sampleCount INTEGER NOT NULL, status TEXT NOT NULL, candidateLoss REAL NOT NULL, baselineLoss REAL NOT NULL)")
            }
        }
    }
}
