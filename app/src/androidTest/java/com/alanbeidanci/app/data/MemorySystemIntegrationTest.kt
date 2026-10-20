package com.alanbeidanci.app.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alanbeidanci.app.AlanVocabApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemorySystemIntegrationTest {
    private lateinit var db: AlanDatabase
    private lateinit var repository: LearningRepository
    private lateinit var app: AlanVocabApplication
    private lateinit var backup: BackupManager
    private val now get() = System.currentTimeMillis()
    private val settings = UserSettings(currentBookId = "audit", selectedBookIds = setOf("audit"))

    @Before fun setup() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AlanDatabase::class.java).build()
        repository = LearningRepository(db, app, preferences = app.container.preferences)
        backup = BackupManager(repository, app.container.preferences)
        app.container.preferences.selectOnlyBook("audit")
        db.wordBookDao().insert(WordBookEntity("audit", "审查", "", "", "", 0, 0))
        db.wordDao().insert(WordEntity(1, "audit", "cancel", "", "", "取消", "v.", "", "", 1, 1))
        val old = now - 3 * SkillMemory.DAY
        val s = SkillState(10.0, 5.0, old, now - SkillMemory.DAY, 2, 0, "GOOD")
        db.progressDao().upsert(WordProgressEntity(1, attempts = 5, isFavorite = true, category = "mastered",
            isMastered = true, mainlineClassifiedDate = MainlineScheduler.dateKey(old), dueAt = now - SkillMemory.DAY,
            skillMemory = SkillMemory.encode(mapOf(MemorySkill.MEANING to s, MemorySkill.LISTENING to s, MemorySkill.SPELLING to s))))
    }

    @After fun cleanup() { db.close() }

    @Test fun chineseDualIsSpellingAndRealMeaningSelectionIsCounted() = runBlocking {
        repository.recordDualAnswer(1, true, QuestionType.MEANING, true, true, MainlineTestKind.REMEDY, "exact")
        var stats = repository.observeStats().first()
        assertEquals(0L, stats.meaningMastery.total)
        assertEquals(1L, stats.spellingStability.total)
        repository.logRetrieval(1, true, QuestionType.MEANING, "PICK_MEANING", true, false, "answer", true)
        stats = repository.observeStats().first()
        assertEquals(1L, stats.meaningMastery.total)
        assertEquals(0L, stats.independentRecall.getValue(MemorySkill.MEANING).total) // exposed practice
        val old = ReviewLogEntity(wordId = 1, timestamp = now, correct = true, questionType = "MEANING", isDual = true, isMainline = true)
        db.reviewLogDao().insert(old)
        assertEquals(1L, repository.observeStats().first().meaningMastery.total)
        assertEquals(2L, repository.observeStats().first().spellingStability.total)
    }

    @Test fun crossSkillAnswerExposureAndBackupCannotCreateLongTermEvidence() = runBlocking {
        val access = repository.prepareRetrieval(1, "PICK_MEANING")
        assertFalse(access.exposed)
        repository.logRetrieval(1, true, QuestionType.MEANING, "PICK_MEANING", true, false, "answer", true,
            exposedBeforeQuestion = access.exposed)
        val before = SkillMemory.decode(repository.progressFor(1)!!.skillMemory).getValue(MemorySkill.SPELLING)
        val json = backup.exportJson()
        backup.restoreJson(json)
        val next = repository.prepareRetrieval(1, "RECALL")
        assertTrue(next.exposed)
        repository.logRetrieval(1, true, QuestionType.SPELLING, "RECALL", true, false, "answer", true,
            exposedBeforeQuestion = next.exposed)
        val event = db.reviewLogDao().getAll().single { it.eventKind == "schedule" && it.memorySkill == "SPELLING" }
        assertFalse(event.memoryIndependent)
        assertTrue(event.memoryExposed)
        assertEquals(0.0, event.memoryWeight, 0.0)
        assertEquals(before, SkillMemory.decode(repository.progressFor(1)!!.skillMemory).getValue(MemorySkill.SPELLING))
        assertEquals(1L, repository.observeStats().first().independentRecall.getValue(MemorySkill.MEANING).total)
    }

    @Test fun overnightRepairCompletionLeavesTheQueueButKeepsFailureAndFutureDates() = runBlocking {
        val today = MainlineScheduler.startOfToday()
        val healthy = SkillState(20.0, 3.0, today - 3 * SkillMemory.DAY, today + 10 * SkillMemory.DAY, 4, 0, "GOOD")
        val failed = SkillState(2.0, 7.0, today - SkillMemory.DAY + 1000, today, 4, 1, "LAPSE")
        db.progressDao().upsert(repository.progressFor(1)!!.copy(dueAt = today,
            skillMemory = SkillMemory.encode(mapOf(MemorySkill.MEANING to healthy, MemorySkill.LISTENING to healthy, MemorySkill.SPELLING to failed)),
            practiceForm = "RECALL", firstResult = "WRONG",
            practicePlan = SkillPracticePlan(listOf(SkillPracticeTarget(MemorySkill.SPELLING, 2, 1, 2)), true).encode()))
        repository.logRetrieval(1, true, QuestionType.SPELLING, "RECALL", false, false, "answer", true)
        repository.recordRetention(1, RetentionGrade.LAPSE)
        val row = repository.progressFor(1)!!
        assertTrue(MainlineScheduler.plan(db.wordDao().getAll(), db.progressDao().getAll(), settings).consolidationWords.isEmpty())
        assertEquals(MainlineScheduler.dueAfterDays(now, 1), row.dueAt)
        val states = SkillMemory.decode(row.skillMemory)
        assertEquals(healthy, states[MemorySkill.MEANING])
        assertEquals(failed.copy(dueAt = row.dueAt), states[MemorySkill.SPELLING])
        assertTrue(row.isFavorite)
        assertEquals("", row.practicePlan)
        assertTrue(repository.nextSkillReviewDescription(1).contains("隔1天"))
    }

    @Test fun invalidRawExampleNeverGetsAContextScoreAndHistoricalStateIsRetained() = runBlocking {
        val word = db.wordDao().getById(1)!!
        db.wordDao().updateAll(listOf(word.copy(word = "run out of", example = "We ran out of coffee again.")))
        val oldContext = SkillState(5.0, 8.0, now - 4 * SkillMemory.DAY, now, 2, 1, "LAPSE")
        val row = repository.progressFor(1)!!
        db.progressDao().upsert(row.copy(skillMemory = SkillMemory.encode(SkillMemory.decode(row.skillMemory) + (MemorySkill.CONTEXT to oldContext))))
        assertNull(LearningContentPresentation.clozeFor(app, db.wordDao().getById(1)!!))
        repository.logRetrieval(1, true, QuestionType.SPELLING, "CLOZE", true, false, "answer", true)
        assertTrue(db.reviewLogDao().getAll().none { it.memorySkill == "CONTEXT" })
        repository.recordRetention(1, RetentionGrade.GOOD)
        assertEquals(oldContext, SkillMemory.decode(repository.progressFor(1)!!.skillMemory)[MemorySkill.CONTEXT])
    }

    private suspend fun pendingBatch(): DualTestBatch {
        db.progressDao().upsert(repository.progressFor(1)!!.copy(mainlineDualPending = true, mainlineLearnedDate = MainlineScheduler.dateKey()))
        return repository.dualTests.load(settings, MainlineTestKind.NEW_WORDS)!!
    }

    private fun correctResult(cp: DualTestCheckpoint) = cp.copy(revision = cp.revision + 1, input = "cancel",
        holdResult = true, holdCorrect = true, answeredAt = now, typedChars = cp.typedChars + 6, correctChars = cp.correctChars + 6)

    @Test fun dualRoundRestoresThroughBackupAndAnswersCommitExactlyOnce() = runBlocking {
        val cp = repository.dualTests.prepare(pendingBatch().checkpoint)
        val answered = correctResult(cp)
        repository.dualTests.save(answered)
        val first = repository.dualTests.answer(answered)
        assertTrue(first.accepted)
        assertEquals(1, first.next!!.round)
        assertFalse(repository.dualTests.answer(answered).accepted)
        val answerCount = db.reviewLogDao().getAll().count { it.countsAsAnswer() }
        assertEquals(1, answerCount)
        val snapshot = backup.exportJson()
        assertEquals(5, JSONObject(snapshot).getInt("formatVersion"))
        backup.restoreJson(snapshot)
        val restored = repository.dualTests.load(settings, MainlineTestKind.NEW_WORDS)!!.checkpoint
        assertEquals(first.next!!.listening, restored.listening)
        assertEquals(1, restored.round)
        assertNotEquals(cp.sessionId, restored.sessionId)
        assertFalse(repository.dualTests.answer(answered).accepted)
        val second = correctResult(repository.dualTests.prepare(restored))
        assertTrue(second.exposed)
        assertTrue(repository.dualTests.answer(second).accepted)
        repository.dualTests.save(second) // completion cannot be resurrected by an old close callback
        assertTrue(db.dualTestSessionDao().getAll().isEmpty())
        assertEquals(2, db.reviewLogDao().getAll().count { it.countsAsAnswer() })
        assertFalse(repository.progressFor(1)!!.mainlineDualPending)
    }

    @Test fun aPendingWrongResultSurvivesBackupWithoutPrematurelyRecordingAnAnswer() = runBlocking {
        val cp = repository.dualTests.prepare(pendingBatch().checkpoint).copy(input = "xxxxxx", holdResult = true,
            holdCorrect = false, answeredAt = now, currentWrong = 1, totalWrong = 1, typedChars = 6, revision = 3)
        repository.dualTests.save(cp)
        assertTrue(db.reviewLogDao().getAll().isEmpty())
        backup.restoreJson(backup.exportJson())
        val restored = repository.dualTests.load(settings, MainlineTestKind.NEW_WORDS)!!.checkpoint
        assertTrue(restored.holdResult)
        assertFalse(restored.holdCorrect)
        assertEquals(cp.answeredAt, restored.answeredAt)
        val result = repository.dualTests.answer(restored)
        assertTrue(result.accepted)
        assertEquals(false, result.next!!.listening[1L])
        assertEquals(cp.answeredAt, db.reviewLogDao().getAll().single { it.countsAsAnswer() }.timestamp)
    }

    @Test fun invalidCheckpointIsRejectedBeforeCurrentLearningDataChanges() = runBlocking {
        repository.dualTests.prepare(pendingBatch().checkpoint)
        val before = repository.backupSnapshot()
        val json = JSONObject(backup.exportJson())
        json.getJSONArray("dualTests").getJSONObject(0).getJSONObject("checkpoint").put("index", 999)
        assertTrue(runCatching { backup.restoreJson(json.toString()) }.isFailure)
        assertEquals(before, repository.backupSnapshot())
    }

    @Test fun backupsOneThroughFourPreserveDataAndDoNotReinterpretLegacyModels() = runBlocking {
        val json = JSONObject(backup.exportJson())
        for (version in 1..4) {
            val legacy = JSONObject(json.toString()).put("formatVersion", version)
            legacy.remove("dualTests")
            legacy.getJSONArray("progress").getJSONObject(0).remove("answerExposedAt")
            if (version >= 3) legacy.getJSONObject("retentionProfile").put("algorithm", RetentionParameters.LEGACY_ALGORITHM)
            else legacy.remove("retentionProfile")
            backup.restoreJson(legacy.toString())
            assertTrue(repository.progressFor(1)!!.isFavorite)
            assertEquals(json.getJSONArray("progress").getJSONObject(0).getLong("dueAt"), repository.progressFor(1)!!.dueAt)
            assertEquals(RetentionParameters.ALGORITHM, repository.retentionProfile().algorithm)
            assertTrue(repository.retentionProfile().isValid())
            assertTrue(db.dualTestSessionDao().getAll().isEmpty())
        }
    }

    @Test fun actualVersion14MigrationPreservesDatesFavoriteAndPersonalParameters() = runBlocking {
        val name = "memory-v14-migration.db"
        app.deleteDatabase(name)
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.alanbeidanci.app.data.AlanDatabase/14.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val due = now + 30 * SkillMemory.DAY
        val helper = FrameworkSQLiteOpenHelperFactory().create(SupportSQLiteOpenHelper.Configuration.builder(app)
            .name(name).callback(object : SupportSQLiteOpenHelper.Callback(14) {
                override fun onCreate(database: SupportSQLiteDatabase) {
                    val entities = schema.getJSONArray("entities")
                    for (i in 0 until entities.length()) {
                        val entity = entities.getJSONObject(i)
                        val table = entity.getString("tableName")
                        database.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}", table))
                        val indexes = entity.optJSONArray("indices") ?: org.json.JSONArray()
                        for (j in 0 until indexes.length()) database.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\u0024{TABLE_NAME}", table))
                        if (table in setOf("word_books", "words", "word_progress", "retention_profile")) {
                            val values = android.content.ContentValues()
                            val fields = entity.getJSONArray("fields")
                            for (j in 0 until fields.length()) {
                                val field = fields.getJSONObject(j); val key = field.getString("columnName")
                                when (field.getString("affinity")) {
                                    "TEXT" -> values.put(key, when (key) { "algorithm" -> RetentionParameters.LEGACY_ALGORITHM; "generation" -> "old-profile"; "status" -> "PERSONALIZED"; "id", "bookId" -> "audit"; else -> "" })
                                    "REAL" -> values.put(key, when (key) { "gain" -> 1.1; "lapse" -> 1.3; "previousGain" -> 1.7; "previousLapse" -> 2.3; else -> 0.0 })
                                    else -> values.put(key, when (key) { "id", "wordId", "isFavorite" -> 1L; "dueAt" -> due; "attempts" -> 7L; "sampleCount" -> 500L; else -> 0L })
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
        helper.writableDatabase; helper.close()
        val migrated = Room.databaseBuilder(app, AlanDatabase::class.java, name).addMigrations(AlanDatabase.MIGRATION_14_15).build()
        try {
            val row = migrated.progressDao().getByWordId(1)!!
            assertEquals(due, row.dueAt); assertTrue(row.isFavorite); assertEquals(7, row.attempts)
            val profile = migrated.retentionProfileDao().get()!!
            assertEquals(RetentionParameters(1.1, 1.3), profile.parameters())
            assertEquals(RetentionParameters(1.7, 2.3), profile.previousParameters())
            assertFalse(profile.autoEnabled); assertEquals(0, profile.sampleCount); assertTrue(profile.isValid())
            assertTrue(migrated.dualTestSessionDao().getAll().isEmpty())
        } finally { migrated.close(); app.deleteDatabase(name) }
    }
}
