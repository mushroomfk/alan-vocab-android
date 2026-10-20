package com.alanbeidanci.app.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.alanbeidanci.app.AlanVocabApplication
import com.alanbeidanci.app.notifications.RetentionTrainingScheduler
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RetentionPersonalizationIntegrationTest {
    private lateinit var db: AlanDatabase
    private lateinit var app: AlanVocabApplication
    private lateinit var repository: LearningRepository
    private lateinit var backup: BackupManager
    private lateinit var personal: RetentionPersonalization
    private val word = WordEntity(1, "test", "cancel", "", "", "取消", "v.", "", "", 1, 1)

    @Before fun setup() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AlanDatabase::class.java).build()
        repository = LearningRepository(db, app, preferences = app.container.preferences)
        backup = BackupManager(repository, app.container.preferences)
        personal = RetentionPersonalization(db)
        db.wordBookDao().insert(WordBookEntity("test", "测试", "", "", "", 0, 0))
        db.wordDao().insert(word)
        db.progressDao().upsert(WordProgressEntity(1, attempts = 8, isFavorite = true,
            stabilityDays = 3.0, difficulty = 5.0, category = "mastered", isMastered = true,
            lastReviewedAt = System.currentTimeMillis() - 7 * RetentionOptimizer.DAY,
            dueAt = System.currentTimeMillis() - RetentionOptimizer.DAY))
    }

    @After fun teardown() { db.close() }

    @Test fun personalScheduleAndCompleteAuditEventAreWrittenTogether() = runBlocking {
        val initial = db.progressDao().getByWordId(1)!!
        db.retentionProfileDao().save(RetentionProfile(gain = 0.9, lapse = 1.0, revision = 2))
        repository.recordRetention(1, RetentionGrade.GOOD, practiceForm = "RECALL")
        val updated = db.progressDao().getByWordId(1)!!
        val event = db.reviewLogDao().getAll().single()
        assertEquals(updated.lastReviewedAt, event.timestamp)
        assertEquals(initial.lastReviewedAt, event.memoryPreviousReviewAt)
        assertEquals(3.0, event.memoryBeforeStability, 0.0)
        assertEquals(2, event.memoryModelRevision)
        assertTrue(event.memoryIndependent)
        assertFalse(event.countsAsAnswer())
        val expected = MainlineScheduler.applyRetention(initial, RetentionGrade.GOOD, event.timestamp,
            parameters = RetentionParameters(0.9, 1.0))
        assertEquals(expected.dueAt, updated.dueAt)
        assertTrue(updated.isFavorite)
    }

    @Test fun backupRoundTripIncludesModelPreviousVersionAndTrainingMetadata() = runBlocking {
        val profile = RetentionProfile(generation = "before", gain = 1.1, lapse = 1.3, revision = 4,
            previousGain = 1.7, previousLapse = 2.3, status = "PERSONALIZED", autoEnabled = false)
        db.retentionProfileDao().save(profile)
        repository.recordRetention(1, RetentionGrade.LAPSE, practiceForm = "RECALL")
        val originalLog = db.reviewLogDao().getAll().single()
        val json = backup.exportJson()
        assertEquals(5, JSONObject(json).getInt("formatVersion"))
        repository.restore(emptyList(), emptyList())
        backup.restoreJson(json)
        val restored = db.retentionProfileDao().get()!!
        assertEquals(profile.copy(generation = restored.generation), restored)
        assertNotEquals("before", restored.generation)
        assertEquals(originalLog.copy(id = 0), db.reviewLogDao().getAll().single().copy(id = 0))
        assertTrue(db.progressDao().getByWordId(1)!!.isFavorite)
    }

    @Test fun oldBackupUsesDefaultsAndBadModelCannotReplaceLearningData() = runBlocking {
        val original = backup.exportJson()
        val invalid = JSONObject(original).apply { getJSONObject("retentionProfile").put("algorithm", "fsrs6") }
        try { backup.restoreJson(invalid.toString()); fail("invalid model should reject the whole import") }
        catch (_: IllegalArgumentException) { }
        assertEquals(8, db.progressDao().getByWordId(1)!!.attempts)
        db.retentionProfileDao().save(RetentionProfile(gain = 2.2, lapse = 3.0, revision = 1))
        val legacy = JSONObject(original).apply { put("formatVersion", 2); remove("retentionProfile") }
        backup.restoreJson(legacy.toString())
        assertEquals(RetentionParameters(), db.retentionProfileDao().get()!!.parameters())
        assertTrue(db.progressDao().getByWordId(1)!!.isFavorite)
    }

    @Test fun rollbackDisablesAutomaticUpdatesAndLeavesDueDatesAlone() = runBlocking {
        val original = db.progressDao().getByWordId(1)!!
        db.retentionProfileDao().save(RetentionProfile(generation = "old", gain = 2.2, lapse = 3.0, revision = 1))
        personal.restorePrevious()
        assertFalse(db.retentionProfileDao().get()!!.autoEnabled)
        assertEquals(RetentionParameters(), db.retentionProfileDao().get()!!.parameters())
        assertEquals(original, db.progressDao().getByWordId(1))
        assertFalse(personal.check())
        personal.setEnabled(true)
        assertFalse(personal.check())
        assertEquals(original, db.progressDao().getByWordId(1))
    }

    @Test fun oneChargingIdleJobIsRegisteredWithoutNetworkRequirement() {
        RetentionTrainingScheduler.schedule(app)
        RetentionTrainingScheduler.schedule(app)
        val jobs = WorkManager.getInstance(app).getWorkInfosForUniqueWork(RetentionTrainingScheduler.WORK_NAME).get()
        assertEquals(1, jobs.size)
        assertTrue(jobs.single().constraints.requiresCharging())
        assertTrue(jobs.single().constraints.requiresDeviceIdle())
        assertEquals(androidx.work.NetworkType.NOT_REQUIRED, jobs.single().constraints.requiredNetworkType)
    }

    @Test fun backgroundCheckAdoptsParametersAndSchedulingUsesThemWithoutBulkRescheduling() = runBlocking {
        val random = java.util.Random(712)
        val now = System.currentTimeMillis()
        val day = RetentionOptimizer.DAY
        val start = now - 10_000 * day
        val target = RetentionParameters(0.9, 1.0)
        val logs = buildList {
            repeat(400) { wordId ->
                repeat(24) { episode ->
                    var memory = RetentionMath.Memory(3.0 + random.nextDouble() * 4.0, 5.0)
                    var previous = start + episode * 200 * day + wordId * 60_000L
                    repeat(2) { index ->
                        val elapsed = if (index == 0) (30 + random.nextInt(90)).toDouble() else (3 + random.nextInt(12)).toDouble()
                        val time = previous + (elapsed * day).toLong()
                        val good = random.nextDouble() < RetentionMath.recallProbability(memory.stability, elapsed)
                        add(ReviewLogEntity(wordId = wordId.toLong(), timestamp = time,
                            correct = good, questionType = "SPELLING", eventKind = "schedule",
                            scheduleGrade = if (good) "GOOD" else "LAPSE",
                            memoryAlgorithm = RetentionParameters.ALGORITHM, memoryIndependent = true,
                            memoryBeforeStability = memory.stability, memoryBeforeDifficulty = memory.difficulty,
                            memoryPreviousReviewAt = previous))
                        memory = if (good) RetentionMath.reviewGood(memory, elapsed, target)
                            else RetentionMath.reviewAgain(memory, elapsed, target)
                        previous = time
                    }
                }
            }
        }
        val original = db.progressDao().getByWordId(1)!!
        db.reviewLogDao().insertAll(logs)
        val began = System.nanoTime()
        assertTrue(personal.check(now))
        println("Personalization fixture: 19200 events, elapsedMs=${(System.nanoTime() - began) / 1_000_000}")
        val profile = db.retentionProfileDao().get()!!
        assertEquals("PERSONALIZED", profile.status)
        assertEquals(1, profile.revision)
        assertEquals(original, db.progressDao().getByWordId(1))
        repository.recordRetention(1, RetentionGrade.GOOD, practiceForm = "RECALL")
        val updated = db.progressDao().getByWordId(1)!!
        val expected = MainlineScheduler.applyRetention(original, RetentionGrade.GOOD, updated.lastReviewedAt,
            parameters = profile.parameters())
        assertEquals(expected.dueAt, updated.dueAt)
    }

    @Test fun version12MigrationPreservesExistingRowsAndDoesNotInventTrainingEvidence() = runBlocking {
        val name = "personalization-migration-test.db"
        app.deleteDatabase(name)
        val schema = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("com.alanbeidanci.app.data.AlanDatabase/12.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("database")
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(12) {
                override fun onCreate(database: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        database.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) database.execSQL(indices.getJSONObject(j).getString("createSql").replace("\u0024{TABLE_NAME}", table))
                        if (table in setOf("word_books", "words", "word_progress", "review_logs")) {
                            val values = android.content.ContentValues()
                            val fields = entity.getJSONArray("fields")
                            for (j in 0 until fields.length()) {
                                val field = fields.getJSONObject(j)
                                val key = field.getString("columnName")
                                when (field.getString("affinity")) {
                                    "TEXT" -> values.put(key, if (key == "id" || key == "bookId") "test" else "")
                                    "REAL" -> values.put(key, 0.0)
                                    else -> values.put(key, when (key) { "id", "wordId", "isFavorite" -> 1L; "attempts" -> 7L; else -> 0L })
                                }
                            }
                            database.insert(table, 0, values)
                        }
                    }
                    val queries = schema.getJSONArray("setupQueries")
                    for (i in 0 until queries.length()) database.execSQL(queries.getString(i))
                }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        helper.writableDatabase
        helper.close()
        val migrated = Room.databaseBuilder(app, AlanDatabase::class.java, name)
            .addMigrations(AlanDatabase.MIGRATION_12_13, AlanDatabase.MIGRATION_13_14, AlanDatabase.MIGRATION_14_15).build()
        try {
            val row = migrated.progressDao().getByWordId(1)!!
            assertEquals(7, row.attempts)
            assertTrue(row.isFavorite)
            assertNull(migrated.retentionProfileDao().get())
            assertFalse(migrated.reviewLogDao().getAll().single().memoryIndependent)
            assertEquals("", migrated.reviewLogDao().getAll().single().memoryAlgorithm)
        } finally { migrated.close(); app.deleteDatabase(name) }
    }
}
