package com.alanbeidanci.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class MainlineSchedulerTest {
    private val now = LocalDateTime.of(2026, 8, 10, 10, 0)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val settings = UserSettings(
        currentBookId = "book",
        selectedBookIds = setOf("book"),
        dailyGoal = 2,
    )

    @Test
    fun mainlineKeepsFixedPriorityAndDefersTodaysErrors() {
        val reviewWord = word(1, "right")
        val consolidateWord = word(2, "write")
        val freshWord = word(3, "fresh")
        val yesterday = MainlineScheduler.dateKey(now - 24 * 60 * 60 * 1000L)
        val today = MainlineScheduler.dateKey(now)
        val review = progress(1).copy(category = "error", mainlineClassifiedDate = yesterday, wrongCount = 2)
        val consolidate = progress(2).copy(
            category = "mastered",
            isMastered = true,
            mainlineClassifiedDate = yesterday,
            dueAt = MainlineScheduler.startOfToday(now) - 1,
        )

        val first = MainlineScheduler.plan(listOf(reviewWord, consolidateWord, freshWord), listOf(review, consolidate), settings, now = now)
        assertEquals(MainlineStep.REVIEW, first.step)

        val reviewed = review.copy(mainlineRemedyReviewDate = today)
        val second = MainlineScheduler.plan(listOf(reviewWord, consolidateWord, freshWord), listOf(reviewed, consolidate), settings, now = now)
        assertEquals(MainlineStep.REMEDY_TEST, second.step)

        val deferred = reviewed.copy(mainlineClassifiedDate = today, mainlineRemedyTestDate = today)
        val third = MainlineScheduler.plan(listOf(reviewWord, consolidateWord, freshWord), listOf(deferred, consolidate), settings, now = now)
        assertEquals(MainlineStep.CONSOLIDATION_TEST, third.step)
        assertEquals(1, third.deferredRemedyCount)
    }

    @Test
    fun interruptedNewWordBatchContinuesLearningBeforeDualTest() {
        val words = (1L..12L).map { word(it, "word$it") }
        val today = MainlineScheduler.dateKey(now)
        val firstCompleted = progress(1).copy(
            mainlineLearnedDate = today,
            mainlineDualPending = true,
        )

        val resumed = MainlineScheduler.plan(
            words = words,
            progressItems = listOf(firstCompleted),
            settings = settings.copy(dailyGoal = 10, dailyMinutes = 80),
            now = now,
        )

        assertEquals(MainlineStep.LEARN, resumed.step)
        assertEquals(9, resumed.learnWords.size)
        assertEquals((2L..10L).toList(), resumed.learnWords.map { it.id })
        assertEquals(listOf(1L), resumed.pendingTestWords.map { it.id })
    }

    @Test
    fun newWordDualTestStartsOnlyAfterDailyBatchIsComplete() {
        val words = (1L..12L).map { word(it, "word$it") }
        val today = MainlineScheduler.dateKey(now)
        val completed = (1L..10L).map { id ->
            progress(id).copy(mainlineLearnedDate = today, mainlineDualPending = true)
        }

        val plan = MainlineScheduler.plan(
            words = words,
            progressItems = completed,
            settings = settings.copy(dailyGoal = 10),
            now = now,
        )

        assertEquals(MainlineStep.NEW_WORD_TEST, plan.step)
        assertTrue(plan.learnWords.isEmpty())
        assertEquals((1L..10L).toList(), plan.pendingTestWords.map { it.id })
    }

    @Test
    fun selectedUnitScopeExcludesReviewWordsFromOtherUnits() {
        val selected = word(1, "selected").copy(unitId = "unit-a")
        val excluded = word(2, "excluded").copy(unitId = "unit-b")
        val scopedSettings = settings.copy(selectedUnitKeys = setOf("book::unit-a"))

        assertEquals(listOf(selected), MainlineScheduler.scopedWords(listOf(selected, excluded), scopedSettings))
    }

    @Test
    fun reviewRoundsFollowRecentFailureNotLifetimeErrors() {
        val words = listOf(word(1, "one"), word(2, "two"), word(3, "three"))
        val progress = listOf(
            progress(1).copy(wrongCount = 11, category = "weak"),
            progress(2).copy(wrongCount = 1, category = "error"),
            progress(3).copy(wrongCount = 0, category = "weak"),
        )

        assertEquals(1, MainlineScheduler.reviewCycleCount(progress[0]))
        assertEquals(2, MainlineScheduler.reviewCycleCount(progress[1]))
        assertEquals(1, MainlineScheduler.reviewCycleCount(null))
        assertEquals(
            listOf(1L, 2L, 3L, 2L),
            MainlineScheduler.expandReviewRounds(words, progress).map { it.id },
        )
    }

    @Test
    fun weakWordNeedsTwoCompleteDualPassesToReturnToMastered() {
        val weak = progress(1).copy(category = "weak", passStreak = 0)
        val once = MainlineScheduler.classifyDual(weak, listenPass = true, chinesePass = true, now = now)
        assertEquals("weak", once.category)
        assertEquals(1, once.passStreak)
        assertFalse(once.isMastered)

        val twice = MainlineScheduler.classifyDual(once, listenPass = true, chinesePass = true, now = now + 1000)
        assertEquals("mastered", twice.category)
        assertEquals(2, twice.passStreak)
        assertTrue(twice.isMastered)
    }

    @Test
    fun fifthMasteryPassBecomesFixed() {
        val mastered = progress(1).copy(category = "mastered", isMastered = true, masteryStreak = 4)
        val fixed = MainlineScheduler.classifyDual(mastered, listenPass = true, chinesePass = true, now = now)
        assertEquals("fixed", fixed.category)
        assertEquals(5, fixed.masteryStreak)
        assertTrue(fixed.fixedAt > 0)
    }

    @Test
    fun masteryReviewsUseDayThreeSevenFifteenAndThirtyMilestones() {
        val entered = MainlineScheduler.classifyDual(
            progress(1).copy(category = "learning", isMastered = false),
            listenPass = true,
            chinesePass = true,
            now = now,
        )
        assertEquals(now + 3 * 24 * 60 * 60 * 1000L, entered.dueAt)

        val shaky = MainlineScheduler.classifyDual(
            progress(1).copy(category = "learning", isMastered = false, level = 4),
            listenPass = true,
            chinesePass = true,
            now = now,
        )
        assertEquals(MainlineScheduler.dueAfterDays(now, 1), shaky.dueAt)

        val clean = MainlineScheduler.classifyDual(
            progress(1).copy(category = "learning", isMastered = false, level = 5),
            listenPass = true,
            chinesePass = true,
            now = now,
        )
        assertEquals(MainlineScheduler.dueAfterDays(now, 1), clean.dueAt)

        val dayThree = MainlineScheduler.classifyDual(
            entered.copy(lastReviewedAt = now, dueAt = entered.dueAt),
            listenPass = true,
            chinesePass = true,
            now = entered.dueAt,
        )
        assertEquals(now + 7 * 24 * 60 * 60 * 1000L, dayThree.dueAt)

        val daySeven = MainlineScheduler.classifyDual(dayThree, true, true, dayThree.dueAt)
        assertEquals(now + 15 * 24 * 60 * 60 * 1000L, daySeven.dueAt)

        val dayFifteen = MainlineScheduler.classifyDual(daySeven, true, true, daySeven.dueAt)
        assertEquals(now + 30 * 24 * 60 * 60 * 1000L, dayFifteen.dueAt)
    }

    @Test
    fun firstSuccessGrowsOneStepAndALapseComesBackTomorrow() {
        val onTime = progress(1).copy(
            category = "mastered",
            isMastered = true,
            masteryStreak = 1,
            stabilityDays = 1.0,
            difficulty = RetentionMath.initialGood().difficulty,
            lastReviewedAt = now - 86_400_000L,
        )
        val grown = MainlineScheduler.applyRetention(onTime, RetentionGrade.GOOD, now)
        val grownDays = RetentionMath.intervalDays(grown.stabilityDays)
        assertTrue(grown.stabilityDays > onTime.stabilityDays)
        assertTrue(grownDays in 2..6)
        assertTrue(grown.isMastered)
        assertEquals("mastered", grown.category)

        val week = onTime.copy(masteryStreak = 3, stabilityDays = 7.0, lastReviewedAt = now - 7 * 86_400_000L)
        val weekGrown = MainlineScheduler.applyRetention(week, RetentionGrade.GOOD, now)
        assertTrue(RetentionMath.intervalDays(weekGrown.stabilityDays) in 8..29)

        val fixed = onTime.copy(
            category = "fixed",
            masteryStreak = 5,
            stabilityDays = 30.0,
            lastReviewedAt = now - 30 * 86_400_000L,
            fixedAt = now - 40 * 86_400_000L,
        )
        val lapsed = MainlineScheduler.applyRetention(fixed, RetentionGrade.LAPSE, now)
        assertFalse(lapsed.isMastered)
        assertEquals("relearn", lapsed.category)
        assertEquals(MainlineScheduler.dueAfterDays(now, 1), lapsed.dueAt)
        assertTrue(lapsed.stabilityDays < fixed.stabilityDays)

        val rechecked = MainlineScheduler.applyRetention(week, RetentionGrade.RECHECK, now)
        assertEquals(week.stabilityDays, rechecked.stabilityDays, 0.001)
        assertTrue(rechecked.isMastered)
        assertEquals("mastered", rechecked.category)
        assertEquals(MainlineScheduler.dueAfterDays(now, 1), rechecked.dueAt)
    }

    @Test
    fun dueWordsReduceTodaysNewWords() {
        val words = (1L..30L).map { word(it, "word$it") }
        val due = (1L..15L).map {
            progress(it).copy(
                category = "mastered",
                isMastered = true,
                mainlineClassifiedDate = "2026-08-01",
                dueAt = now - 1000,
            )
        }
        val crowded = MainlineScheduler.plan(
            words,
            due,
            settings.copy(dailyGoal = 20, dailyMinutes = 20),
            now = now,
        )
        assertEquals(1, crowded.learnWords.size)
        assertEquals(1, crowded.newWordCap)

        val open = MainlineScheduler.plan(
            words,
            emptyList(),
            settings.copy(dailyGoal = 20, dailyMinutes = 20),
            now = now,
        )
        assertEquals(5, open.learnWords.size)

        val forgotten = progress(1).copy(
            category = "relearn",
            isMastered = false,
            mainlineClassifiedDate = "2026-08-01",
            dueAt = now - 1000,
        )
        val kept = progress(2).copy(
            category = "fixed",
            isMastered = true,
            mainlineClassifiedDate = "2026-08-01",
            dueAt = now - 500,
        )
        val order = MainlineScheduler.plan(
            listOf(word(1, "forgot"), word(2, "kept")),
            listOf(kept, forgotten),
            settings,
            now = now,
        )
        assertEquals(listOf(1L, 2L), order.consolidationWords.map { it.id })
    }

    @Test
    fun englishHomophonesReceiveHintsButDifferentPronunciationsDoNot() {
        val right = word(1, "right", phonetic = "raɪt")
        val write = word(2, "write", phonetic = "/raɪt/")
        val read = word(3, "read", phonetic = "riːd")
        val words = listOf(right, write, read)

        assertTrue(MainlineScheduler.hasEnglishHomophoneConflict(right, words, "us"))
        assertTrue(MainlineScheduler.hasEnglishHomophoneConflict(write, words, "us"))
        assertFalse(MainlineScheduler.hasEnglishHomophoneConflict(read, words, "us"))
        assertEquals("I am ____.", MainlineScheduler.blankExample("I am right.", "right"))
    }

    @Test
    fun freshStudyMarksEmptyStepsAsNotNeededToday() {
        val plan = MainlineScheduler.plan(listOf(word(3, "fresh")), emptyList(), settings, now = now)
        assertEquals(MainlineStep.LEARN, plan.step)
        assertEquals("今天不用做", MainlineScheduler.stepStatus(plan, MainlineStep.REVIEW))
        assertEquals("今天不用做", MainlineScheduler.stepStatus(plan, MainlineStep.REMEDY_TEST))
        assertEquals("今天不用做", MainlineScheduler.stepStatus(plan, MainlineStep.CONSOLIDATION_TEST))
        assertEquals("正在做", MainlineScheduler.stepStatus(plan, MainlineStep.LEARN))
        assertEquals("还没到", MainlineScheduler.stepStatus(plan, MainlineStep.NEW_WORD_TEST))
    }

    @Test
    fun finishedReviewShowsDoneInsteadOfSkipped() {
        val today = MainlineScheduler.dateKey(now)
        val reviewed = progress(1).copy(
            category = "error",
            mainlineClassifiedDate = MainlineScheduler.dateKey(now - 24 * 60 * 60 * 1000L),
            mainlineRemedyReviewDate = today,
            wrongCount = 1,
        )
        val plan = MainlineScheduler.plan(listOf(word(1, "right")), listOf(reviewed), settings, now = now)
        assertEquals(MainlineStep.REMEDY_TEST, plan.step)
        assertEquals(1, plan.reviewDoneToday)
        assertEquals("做完了", MainlineScheduler.stepStatus(plan, MainlineStep.REVIEW))
        assertEquals("正在做", MainlineScheduler.stepStatus(plan, MainlineStep.REMEDY_TEST))
    }

    @Test
    fun newlyMasteredWordIsDueOnTheNextCalendarDay() {
        val day = LocalDate.of(2026, 10, 12)
        val zone = ZoneId.systemDefault()
        val classifiedAt = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val progress = MainlineScheduler.classifyDual(
            WordProgressEntity(wordId = 1, attempts = 1, level = 5, mainlineLearnedDate = day.toString()),
            listenPass = true,
            chinesePass = true,
            now = classifiedAt,
        ).copy(mainlineClassifiedDate = day.toString())
        val tomorrowEvening = day.plusDays(1).atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        val plan = MainlineScheduler.plan(listOf(word(1, "cancel")), listOf(progress), settings, now = tomorrowEvening)
        assertEquals(listOf(1L), plan.consolidationWords.map { it.id })
    }

    @Test
    fun dueDateUsesCalendarDaysAcrossDaylightSaving() {
        val zone = ZoneId.of("America/New_York")
        val now = LocalDate.of(2026, 11, 1).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val due = MainlineScheduler.dueAfterDays(now, 1, zone)
        assertEquals(LocalDate.of(2026, 11, 2).atStartOfDay(zone).toInstant().toEpochMilli(), due)
        assertFalse(MainlineScheduler.isDueOnOrBefore(due, now, zone))
        val evening = LocalDate.of(2026, 11, 2).atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        assertTrue(MainlineScheduler.isDueOnOrBefore(due, evening, zone))
    }

    @Test
    fun unitFilterKeepsOtherBooksWhenOnlyOneBookHasUnits() {
        val first = word(1, "cancel").copy(bookId = "demo-basics", unitId = "unit-a")
        val other = word(2, "apple").copy(bookId = "cet6", unitId = "unit-b")
        val scoped = UserSettings(selectedBookIds = setOf("demo-basics", "cet6"), selectedUnitKeys = setOf("cet4::unit-a"))
        val plan = MainlineScheduler.plan(listOf(first, other), emptyList(), scoped)
        assertEquals(2, plan.totalWords)
    }

    @Test
    fun consolidationDoneTodayUsesTheCompletionDate() {
        val today = MainlineScheduler.dateKey(now)
        val done = progress(1).copy(
            category = "mastered",
            isMastered = true,
            mainlineClassifiedDate = "2026-08-01",
            mainlineConsolidationDate = today,
        )
        val plan = MainlineScheduler.plan(listOf(word(1, "cancel")), listOf(done), settings, now = now)
        assertEquals(1, plan.consolidationDoneToday)
    }

    @Test
    fun failedDualTestComesBackTomorrow() {
        val learning = progress(1).copy(category = "learning", level = 5, dueAt = now + 30L * 86_400_000)
        val missed = MainlineScheduler.classifyDual(learning, listenPass = false, chinesePass = false, now = now)
        assertEquals("error", missed.category)
        assertFalse(missed.isMastered)
        assertEquals(MainlineScheduler.dueAfterDays(now, 1), missed.dueAt)

        val half = MainlineScheduler.classifyDual(learning, listenPass = true, chinesePass = false, now = now)
        assertEquals("weak", half.category)
        assertEquals(MainlineScheduler.dueAfterDays(now, 1), half.dueAt)
    }

    @Test
    fun recentStudyTimeReplacesTheFourMinuteGuess() {
        val fast = MainlineScheduler.paceFromSessions(
            listOf(session(mode = "LEARN", completed = 3, durationMs = 6 * 60_000L)),
            now,
        )
        assertEquals(2 * 60_000L, fast.millisPerNewWord)
        val words = (1L..30L).map { word(it, "word$it") }
        val plan = MainlineScheduler.plan(
            words,
            emptyList(),
            settings.copy(dailyGoal = 20, dailyMinutes = 20),
            now = now,
            pace = fast,
        )
        assertEquals(10, plan.learnWords.size)

        val tooFew = MainlineScheduler.paceFromSessions(
            listOf(session(mode = "LEARN", completed = 2, durationMs = 60_000L)),
            now,
        )
        assertEquals(4 * 60_000L, tooFew.millisPerNewWord)
    }

    private fun session(mode: String, completed: Int, durationMs: Long) = StudySessionEntity(
        mode = mode,
        wordCount = completed,
        completed = completed,
        durationMs = durationMs,
        startedAt = now - durationMs,
        finishedAt = now,
    )

    private fun progress(id: Long) = WordProgressEntity(wordId = id, attempts = 1)

    private fun word(id: Long, spelling: String, phonetic: String = spelling) = WordEntity(
        id = id,
        bookId = "book",
        word = spelling,
        phoneticUs = phonetic,
        phoneticUk = phonetic,
        translation = "释义",
        partOfSpeech = "n.",
        example = "I am $spelling.",
        exampleTranslation = "",
        chapter = 1,
        orderIndex = id.toInt(),
    )
}
