package com.alanbeidanci.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/** Regression cases from the 2026-10-17 full audit. */
@RunWith(AndroidJUnit4::class)
class FullAuditRepairRegressionTest {
    private lateinit var db: AlanDatabase
    private lateinit var repository: LearningRepository
    private lateinit var backup: BackupManager
    private val apple = WordEntity(101L, "audit", "apple", "", "", "苹果", "n.", "", "", 1, 1)
    private val banana = apple.copy(id = 102L, word = "banana", translation = "香蕉")

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AlanDatabase::class.java).build()
        val preferences = PreferencesStore(context)
        repository = LearningRepository(db, context, preferences = preferences)
        backup = BackupManager(repository, preferences)
        db.wordBookDao().insert(WordBookEntity("audit", "核查", "", "", "", 0, 0))
        db.wordDao().insert(apple)
        db.wordDao().insert(banana)
        db.progressDao().upsert(WordProgressEntity(apple.id, attempts = 3, isFavorite = true))
        db.reviewLogDao().insert(ReviewLogEntity(wordId = apple.id, timestamp = System.currentTimeMillis(), correct = true, questionType = "SPELLING"))
    }

    @After fun cleanup() { db.close() }

    @Test fun restoreMustCheckSpellingWhenAnOldIdNowBelongsToAnotherWord() = runBlocking {
        val content = JSONObject(backup.exportJson())
        content.getJSONArray("progress").getJSONObject(0).put("wordId", banana.id)
        backup.restoreJson(content.toString())
        assertNotNull("apple 的收藏不得恢复到 banana", repository.progressFor(apple.id))
        assertNull(repository.progressFor(banana.id))
    }

    @Test fun fallbackWordMappingMustPreserveAnswerHistory() = runBlocking {
        val content = JSONObject(backup.exportJson()).also { it.remove("wordIdentities") }
        content.getJSONArray("progress").getJSONObject(0).put("wordId", 9999L)
        content.getJSONArray("logs").getJSONObject(0).put("wordId", 9999L)
        backup.restoreJson(content.toString())
        assertNotNull(repository.progressFor(apple.id))
        assertEquals("进度通过拼写找回后，对应作答历史也必须保留", 1, repository.allLogs().size)
    }

    @Test fun historyWithoutProgressUsesExportedWordIdentities() = runBlocking {
        val content = JSONObject(backup.exportJson()).put("progress", JSONArray())
        content.getJSONArray("wordIdentities").getJSONObject(0).put("wordId", 9999L)
        content.getJSONArray("logs").getJSONObject(0).put("wordId", 9999L)
        backup.restoreJson(content.toString())
        assertEquals(apple.id, repository.allLogs().single().wordId)
    }

    @Test fun remappingKeepsTheDualRoundAnswersAndUnconfirmedInput() = runBlocking {
        val content = JSONObject(backup.exportJson())
        content.getJSONArray("progress").getJSONObject(0).put("wordId", 9999L)
        content.getJSONArray("logs").getJSONObject(0).put("wordId", 9999L)
        val identities = JSONArray().put(JSONObject().put("wordId", 9999L).put("bookId", "audit").put("spelling", "apple"))
            .put(JSONObject().put("wordId", 8888L).put("bookId", "audit").put("spelling", "banana"))
        val cp = DualTestCheckpoint("NEW_WORDS:audit", MainlineTestKind.NEW_WORDS, listOf(9999L, 8888L),
            round = 1, index = 1, listening = mapOf(9999L to true, 8888L to false), spelling = mapOf(9999L to false),
            input = "banana", holdResult = true, holdCorrect = true,
            preparedAt = System.currentTimeMillis()-20, answeredAt = System.currentTimeMillis())
        content.put("wordIdentities", identities).put("dualTests", JSONArray().put(JSONObject()
            .put("key", cp.key).put("kind", cp.kind.name).put("checkpoint", JSONObject(cp.entity().payload))))
        backup.restoreJson(content.toString())
        val restored = db.dualTestSessionDao().getAll().single().checkpoint()
        assertEquals(listOf(apple.id, banana.id), restored.wordIds)
        assertEquals(mapOf(apple.id to true, banana.id to false), restored.listening)
        assertEquals(mapOf(apple.id to false), restored.spelling)
        assertEquals(1, restored.round)
        assertEquals(1, restored.index)
        assertEquals("banana", restored.input)
        assertTrue(restored.holdResult && restored.holdCorrect)
        assertTrue(repository.progressFor(apple.id)!!.isFavorite)
    }

    @Test fun unresolvedOrAmbiguousWordsAreRejectedWithoutChangingCurrentData() = runBlocking {
        val original = backup.exportJson()
        db.wordDao().insert(apple.copy(id = 103L))
        val before = repository.backupSnapshot()
        val ambiguous = JSONObject(original)
        ambiguous.getJSONArray("progress").getJSONObject(0).put("wordId", 9999L)
        assertTrue(runCatching { backup.restoreJson(ambiguous.toString()) }.isFailure)
        assertEquals(before, repository.backupSnapshot())
        val missing = JSONObject(original).also { it.remove("wordIdentities") }
        missing.getJSONArray("progress").getJSONObject(0).put("spelling", "not-in-this-book")
        assertTrue(runCatching { backup.restoreJson(missing.toString()) }.isFailure)
        assertEquals(before, repository.backupSnapshot())
    }

    @Test fun versionFiveRequiresSettingsAndSessionArrayTypesBeforeAnyWrite() = runBlocking {
        val original = backup.exportJson()
        val before = repository.backupSnapshot()
        for ((name, value) in listOf("sessions" to JSONObject.NULL, "sessions" to "not-an-array", "settings" to JSONArray())) {
            val invalid = JSONObject(original).put(name, value)
            assertTrue(runCatching { backup.restoreJson(invalid.toString()) }.isFailure)
            assertEquals(before, repository.backupSnapshot())
        }
    }

    @Test fun versionFiveMissingSessionsMustBeRejectedBeforeReplacingData() = runBlocking {
        repository.saveSession("LEARN", 1, 1, 60000, 5, 5, System.currentTimeMillis()-60000, System.currentTimeMillis())
        val content = JSONObject(backup.exportJson()).also { it.remove("sessions") }
        val rejected = runCatching { backup.restoreJson(content.toString()) }.isFailure
        assertTrue("第 5 版缺少会话字段不应当成第 1 版并静默清空时长", rejected)
        assertEquals(1, repository.allSessions().size)
    }

    @Test fun futureAnswersMustNotBeCountedAsTodaysAnswers() = runBlocking {
        db.reviewLogDao().clear()
        val tomorrow = LocalDate.now().plusDays(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        db.reviewLogDao().insert(ReviewLogEntity(wordId = apple.id, timestamp = tomorrow, correct = true, questionType = "SPELLING"))
        val report = repository.observeStats().first()
        assertEquals("调回系统日期或恢复未来备份后，未来记录不得算入当天", 0, report.todayAnswers)
        assertEquals(report.todayAnswers, report.days.last().answers)
    }

    @Test fun futureSessionDurationDoesNotEnterTodayOrTodaysDetail() = runBlocking {
        val tomorrow = LocalDate.now().plusDays(1).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        repository.saveSession("LEARN", 1, 1, 60000, 5, 5, tomorrow, tomorrow + 60000)
        val report = repository.observeStats().first()
        assertEquals(0L, report.todayDurationMs)
        assertEquals(0L, report.days.last().durationMs)
    }

    @Test fun activeDailySummaryMustRefreshWhenTheLocalDateChanges() = runBlocking {
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("GMT-12:00"))
            val oldDay = LocalDate.now().toString()
            db.progressDao().upsert(WordProgressEntity(apple.id, attempts = 3, mainlineLearnedDate = oldDay))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val daily = repository.observeHome().stateIn(scope, SharingStarted.Eagerly, HomeSummary())
                withTimeout(5000) { daily.first { it.todayLearned == 1 } }
                TimeZone.setDefault(TimeZone.getTimeZone("GMT+14:00"))
                assertNotEquals(oldDay, LocalDate.now().toString())
                assertEquals(0, repository.observeHome().first().todayLearned)
                val changed = withTimeoutOrNull(1800) { daily.first { it.todayLearned == 0 } }
                assertNotNull("页面保持订阅时日期改变，也应刷新当日统计", changed)
            } finally { scope.cancel() }
        } finally { TimeZone.setDefault(previousZone) }
    }
}
