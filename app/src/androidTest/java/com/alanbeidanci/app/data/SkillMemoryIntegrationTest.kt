package com.alanbeidanci.app.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alanbeidanci.app.AlanVocabApplication
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkillMemoryIntegrationTest {
    private lateinit var db: AlanDatabase
    private lateinit var app: AlanVocabApplication
    private lateinit var repository: LearningRepository
    private lateinit var backup: BackupManager
    private val word = WordEntity(1, "test", "cancel", "", "", "取消", "v.", "", "", 1, 1)
    private val now get() = System.currentTimeMillis()
    private fun healthy() = SkillState(20.0, 3.0, now - 10 * SkillMemory.DAY,
        MainlineScheduler.dueAfterDays(now, 10), 4, 0, "GOOD")

    @Before fun setup() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AlanDatabase::class.java).build()
        repository = LearningRepository(db, app, preferences = app.container.preferences)
        backup = BackupManager(repository, app.container.preferences)
        db.wordBookDao().insert(WordBookEntity("test", "测试", "", "", "", 0, 0))
        db.wordDao().insert(word)
        val states = mapOf(MemorySkill.MEANING to healthy(), MemorySkill.SPELLING to healthy(),
            MemorySkill.LISTENING to healthy().copy(stability = 3.0, difficulty = 7.0,
                dueAt = now - SkillMemory.DAY, outcome = "LAPSE"))
        db.progressDao().upsert(WordProgressEntity(1, attempts = 8, isFavorite = true,
            category = "mastered", isMastered = true, mainlineClassifiedDate = "2026-10-01",
            dueAt = now - SkillMemory.DAY, skillMemory = SkillMemory.encode(states)))
    }

    @After fun cleanup() { db.close() }

    @Test fun listeningFailureAffectsOnlyListeningAndSummaryUsesItsDate() = runBlocking {
        val before = SkillMemory.decode(db.progressDao().getByWordId(1)!!.skillMemory)
        repository.logRetrieval(1, false, QuestionType.LISTENING, "LISTEN", true, false, "answer", true)
        val during = db.progressDao().getByWordId(1)!!
        assertTrue(MainlineScheduler.isDueOnOrBefore(during.dueAt, now)) // interruption remains reachable
        val failed = SkillMemory.decode(during.skillMemory)
        assertEquals(before[MemorySkill.MEANING], failed[MemorySkill.MEANING])
        assertEquals(before[MemorySkill.SPELLING], failed[MemorySkill.SPELLING])
        assertTrue(failed[MemorySkill.LISTENING]!!.difficulty > before[MemorySkill.LISTENING]!!.difficulty)
        repository.logRetrieval(1, true, QuestionType.LISTENING, "LISTEN", false, false, "answer", true)
        assertEquals(failed, SkillMemory.decode(db.progressDao().getByWordId(1)!!.skillMemory))
        repository.recordRetention(1, RetentionGrade.LAPSE, practiceForm = "LISTEN")
        val after = db.progressDao().getByWordId(1)!!
        assertEquals(1, MainlineScheduler.daysUntil(now, after.dueAt))
        assertEquals("relearn", after.category)
        assertTrue(after.isFavorite)
        assertTrue(repository.nextSkillReviewDescription(1).contains("听写"))
        assertTrue(repository.nextSkillReviewDescription(1).contains("隔1天"))
        assertEquals(2, db.reviewLogDao().getAll().count { it.countsAsAnswer() })
        val event = db.reviewLogDao().getAll().first { it.memoryAlgorithm == SkillMemory.ALGORITHM && it.memoryIndependent }
        assertEquals(before[MemorySkill.LISTENING]!!.lastAt, event.memoryPreviousReviewAt)
        assertEquals("LISTENING", event.memorySkill)
        assertTrue(event.memoryIndependent)
    }

    @Test fun planAndDimensionHistorySurviveBackupAndInterruptedPractice() = runBlocking {
        val plan = SkillPracticePlan(listOf(SkillPracticeTarget(MemorySkill.LISTENING, 3, 1, 2)), true).encode()
        val request = now
        repository.savePracticeSession(PracticeSession(1, "LISTEN", 1, 1, false, "WRONG", 2, 1, 2, -1, plan), request)
        val snapshot = repository.practiceSnapshots(setOf(1)).getValue(1)
        assertEquals(plan, snapshot.plan)
        val original = db.progressDao().getByWordId(1)!!
        val json = backup.exportJson()
        assertEquals(5, JSONObject(json).getInt("formatVersion"))
        repository.restore(emptyList(), emptyList(), emptyList())
        backup.restoreJson(json)
        assertEquals(original, db.progressDao().getByWordId(1))
        repository.recordRetention(1, RetentionGrade.LAPSE)
        repository.savePracticeSession(PracticeSession(1, "LISTEN", 0, 0, false, "NONE", 0, 0, 0, 0, plan), request)
        assertEquals("", db.progressDao().getByWordId(1)!!.practicePlan) // stale save cannot resurrect a completed session
    }

    @Test fun invalidVersionedSkillBackupIsRejectedWithoutChangingCurrentRows() = runBlocking {
        val before = db.progressDao().getByWordId(1)!!
        val json = JSONObject(backup.exportJson())
        json.getJSONArray("progress").getJSONObject(0).put("skillMemory", "v99|LISTENING")
        assertTrue(runCatching { backup.restoreJson(json.toString()) }.isFailure)
        assertEquals(before, db.progressDao().getByWordId(1))
    }

    @Test fun dualChineseDictationMeasuresSpellingRatherThanPretendingToMeasureMeaning() = runBlocking {
        val initial = SkillMemory.decode(db.progressDao().getByWordId(1)!!.skillMemory)
        repository.recordDualAnswer(1, false, QuestionType.MEANING, true, false,
            MainlineTestKind.REMEDY, "unknown")
        val states = SkillMemory.decode(db.progressDao().getByWordId(1)!!.skillMemory)
        assertEquals(initial[MemorySkill.MEANING], states[MemorySkill.MEANING])
        assertTrue(states[MemorySkill.SPELLING]!!.difficulty > initial[MemorySkill.SPELLING]!!.difficulty)
        val event = db.reviewLogDao().getAll().first { it.memoryAlgorithm == SkillMemory.ALGORITHM }
        assertEquals("SPELLING", event.memorySkill)
        assertEquals("TEST", event.memorySource)
        assertEquals(0.9, event.memoryWeight, 0.0)
    }

    @Test fun hintsChangeThePlanButDoNotBecomeTrainingSuccess() = runBlocking {
        val old = SkillMemory.decode(db.progressDao().getByWordId(1)!!.skillMemory).getValue(MemorySkill.SPELLING)
        repository.logRetrieval(1, true, QuestionType.SPELLING, "RECALL", true, true, "answer", true)
        val after = SkillMemory.decode(db.progressDao().getByWordId(1)!!.skillMemory).getValue(MemorySkill.SPELLING)
        assertEquals(old.stability, after.stability, 0.0)
        assertEquals(old.difficulty, after.difficulty, 0.0)
        assertEquals("HINT", after.outcome)
        val event = db.reviewLogDao().getAll().first { it.memoryAlgorithm == SkillMemory.ALGORITHM }
        assertFalse(event.memoryIndependent)
        val plan = SkillPracticePlan.create(SkillMemory.decode(db.progressDao().getByWordId(1)!!.skillMemory), true, now)
        assertEquals(2, plan.targets.first { it.skill == MemorySkill.SPELLING }.required)
    }
    @Test fun version13MigrationPreservesProgressAndPersonalParameters() = runBlocking {
        val name = "skills-migration-test.db"
        app.deleteDatabase(name)
        val schema = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets.open("com.alanbeidanci.app.data.AlanDatabase/13.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("database")
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(13) {
                override fun onCreate(database: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        database.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indices.length()) database.execSQL(indices.getJSONObject(j).getString("createSql").replace("\u0024{TABLE_NAME}", table))
                        if (table in setOf("word_books", "words", "word_progress", "review_logs", "retention_profile")) {
                            val values = android.content.ContentValues()
                            val fields = entity.getJSONArray("fields")
                            for (j in 0 until fields.length()) {
                                val field = fields.getJSONObject(j)
                                val key = field.getString("columnName")
                                when (field.getString("affinity")) {
                                    "TEXT" -> values.put(key, when (key) { "algorithm" -> RetentionParameters.ALGORITHM; "generation" -> "before-upgrade"; "status" -> "PERSONALIZED"; "id", "bookId" -> "test"; else -> "" })
                                    "REAL" -> values.put(key, when (key) { "gain" -> 1.1; "lapse" -> 1.3; "previousGain" -> 1.54575; "previousLapse" -> 1.9395; else -> 0.0 })
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
            .addMigrations(AlanDatabase.MIGRATION_13_14, AlanDatabase.MIGRATION_14_15).build()
        try {
            val row = migrated.progressDao().getByWordId(1)!!
            assertEquals(7, row.attempts)
            assertTrue(row.isFavorite)
            assertEquals("", row.skillMemory)
            assertEquals("", row.practicePlan)
            assertEquals(1.1, migrated.retentionProfileDao().get()!!.gain, 0.0)
            assertTrue(migrated.retentionProfileDao().get()!!.isValid())
            assertFalse(migrated.reviewLogDao().getAll().single().memoryIndependent)
            assertEquals("", migrated.reviewLogDao().getAll().single().memoryAlgorithm)
        } finally { migrated.close(); app.deleteDatabase(name) }
    }
}
