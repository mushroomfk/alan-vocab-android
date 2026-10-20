package com.alanbeidanci.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alanbeidanci.app.AlanVocabApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** 核查 F01–F08 的常规回归。断言修复后用户应得到的结果。 */
@RunWith(AndroidJUnit4::class)
class FullAuditRegressionTest {
    private lateinit var context: Context
    private lateinit var db: AlanDatabase
    private lateinit var repository: LearningRepository
    private lateinit var preferences: PreferencesStore
    private lateinit var backup: BackupManager
    private val word = WordEntity(1000001, "demo-basics", "cancel", "", "", "取消", "v.", "", "", 1, 1)

    @Before
    fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AlanVocabApplication>()
        app.container.repository.ensureSeeded()
        context = app
        db = Room.inMemoryDatabaseBuilder(context, AlanDatabase::class.java).build()
        preferences = app.container.preferences
        preferences.resetForTesting()
        repository = LearningRepository(db, context, preferences = preferences)
        backup = BackupManager(repository, preferences)
        db.wordBookDao().insert(WordBookEntity("demo-basics", "审计词书", "", "", "", 0, 0))
        db.wordDao().insert(word)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("alan_account_scope", Context.MODE_PRIVATE)
            .edit().putLong("bound_user_id", 1).commit()
        db.close()
    }

    private suspend fun session() {
        val now = System.currentTimeMillis()
        repository.saveSession("LEARN", 1, 1, 60000, 6, 6, now - 60000, now)
    }

    @Test
    fun offlineUpgradeMustKeepExistingAccountProgress() = runBlocking {
        db.progressDao().upsert(WordProgressEntity(word.id, attempts = 3, isFavorite = true))
        context.getSharedPreferences("alan_account_scope", Context.MODE_PRIVATE)
            .edit().putLong("bound_user_id", 42).commit()
        repository.prepareForUser(1L)
        assertNotNull("联网账号升级到本机模式后，学习进度不应消失", repository.progressFor(word.id))
    }

    @Test
    fun jsonBackupMustRestoreStudySessions() = runBlocking {
        repository.recordTypingCompletion(word.id, TypingPurpose.MAINLINE_LEARN)
        session()
        val json = backup.exportJson()
        db.studySessionDao().clear()
        repository.restore(emptyList(), emptyList())
        backup.restoreJson(json)
        assertEquals("换机恢复应保留学习会话和时长", 1, db.studySessionDao().getAll().size)
    }

    @Test
    fun offlineDailyAnswerCountMustMatchTodayTotal() = runBlocking {
        repository.recordTypingCompletion(word.id, TypingPurpose.MAINLINE_LEARN)
        session()
        val report = repository.observeStats().first()
        assertEquals(1, report.todayAnswers)
        assertEquals("近七天明细不能把已有作答显示成零", 1, report.days.last().answers)
    }

    @Test
    fun newlyMasteredWordMustBeReviewedOnNextCalendarDay() {
        val day = LocalDate.of(2026, 10, 12)
        val now = day.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val progress = MainlineScheduler.classifyDual(
            WordProgressEntity(word.id, attempts = 1, level = 5, mainlineLearnedDate = day.toString()),
            true, true, now,
        ).copy(mainlineClassifiedDate = day.toString())
        val tomorrow = day.plusDays(1).atTime(20, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val plan = MainlineScheduler.plan(listOf(word), listOf(progress), UserSettings(), now = tomorrow)
        assertEquals("已到第二天晚上，仍然应该排入次日巩固", listOf(word.id), plan.consolidationWords.map { it.id })
    }

    @Test
    fun deselectLastBookMustProduceEmptySelection() = runBlocking {
        preferences.setCurrentBook("demo-basics")
        preferences.toggleBookSelection("demo-basics")
        assertTrue("取消唯一词书不应产生空字符串词书 ID", preferences.snapshot().selectedBookIds.isEmpty())
    }

    @Test
    fun completedAdaptiveReviewMustBeShownAsDoneToday() = runBlocking {
        val now = System.currentTimeMillis()
        db.progressDao().upsert(WordProgressEntity(
            word.id, attempts = 3, category = "mastered", isMastered = true,
            masteryStreak = 1, dueAt = now - 86400000,
            mainlineClassifiedDate = LocalDate.now().minusDays(2).toString(),
        ))
        repository.recordAdaptiveReview(word.id, ReviewOutcome.CLEAN, 5)
        val plan = repository.currentMainlinePlan()
        assertEquals("完成巩固后首页应该显示已完成", 1, plan.consolidationDoneToday)
    }

    @Test
    fun selectedBookWithoutUnitFilterMustKeepItsWholeBook() {
        val other = word.copy(id = 2000001, bookId = "cet6", unitId = "unit-b")
        val first = word.copy(unitId = "unit-a")
        val settings = UserSettings(selectedBookIds = setOf("demo-basics", "cet6"), selectedUnitKeys = setOf("cet4::unit-a"))
        val plan = MainlineScheduler.plan(listOf(first, other), emptyList(), settings)
        assertEquals("另一套已选词书未选单元，应按界面说明保留整本", 2, plan.totalWords)
    }

    @Test
    fun dictionaryVersionUpdateMustKeepSitcomProgress() = runBlocking {
        val app = context as AlanVocabApplication
        val source = app.container.repository.getBook("demo-daily")!!
        val sitcomWord = app.container.repository.getWords(setOf("demo-daily")).first()
        db.wordBookDao().insert(source)
        db.wordDao().insert(sitcomWord)
        db.progressDao().upsert(WordProgressEntity(sitcomWord.id, attempts = 3, isFavorite = true))
        val metadata = context.getSharedPreferences("alan_dictionary_assets", Context.MODE_PRIVATE)
        val previous = metadata.getInt("version", 0)
        try {
            metadata.edit().putInt("version", DictionaryAssetLoader(context).version - 1).commit()
            repository.ensureSeeded()
            assertNotNull("词库版本更新不应删除仍存在单词的学习和收藏记录", repository.progressFor(sitcomWord.id))
        } finally {
            metadata.edit().putInt("version", previous).commit()
        }
    }
}
