package com.alanbeidanci.app.data

import com.alanbeidanci.app.ui.*
import org.junit.Assert.*
import org.junit.Test

class MemorySystemRegressionTest {
    private val now = 1_800_000_000_000L

    @Test fun invalidInflectedOrSeparableExamplesAreNotClozeQuestions() {
        assertNull(ClozeQuestion.create("We ran out of coffee again.", "run out of"))
        assertNull(ClozeQuestion.create("Can you pick up bread?", "pick something up"))
        assertEquals("", MainlineScheduler.blankExample("We ran out of coffee again.", "run out of"))
        val real = ClozeQuestion.create("Please CANCEL this order.", "cancel")!!
        assertEquals("Please ____ this order.", real.prompt)
        assertEquals("cancel", real.answer)
    }

    @Test fun seeingAnAnswerAllowsPracticeCreditsButNotLongTermEvidence() {
        val old = SkillState(7.0, 5.0, now - 7 * SkillMemory.DAY, now, 3, 0, "GOOD")
        val result = SkillMemory.update(old, true, true, false, MemorySource.REVIEW, "RECALL", now,
            RetentionParameters(), exposed = true)
        assertFalse(result.independent)
        assertEquals(0.0, result.weight, 0.0)
        assertEquals(old.stability, result.after.stability, 0.0)
        assertEquals(old.difficulty, result.after.difficulty, 0.0)
        val plan = SkillPracticePlan(listOf(SkillPracticeTarget(MemorySkill.MEANING, 1), SkillPracticeTarget(MemorySkill.SPELLING, 1)), true)
        val second = (MemoryPractice.afterCorrect(MemoryState(1, PracticeForm.PICK_MEANING,
            phase = PracticePhase.REVIEW, plan = plan)) as PracticeEffect.Show).state
        assertEquals(PracticeForm.RECALL, second.form)
        assertTrue(second.exposed)
        assertTrue(MemoryPractice.afterCorrect(second) is PracticeEffect.Graduate)
    }

    @Test fun crossDayCompletionMovesExpiredSkillsWithoutErasingTheOriginalFailure() {
        val today = MainlineScheduler.startOfToday(now)
        val failed = SkillState(2.0, 7.0, today - SkillMemory.DAY, today, 3, 1, "LAPSE")
        val healthy = failed.copy(outcome = "GOOD", dueAt = MainlineScheduler.dueAfterDays(now, 10))
        val repeated = SkillMemory.update(failed, true, false, false, MemorySource.REVIEW, "RECALL", now, RetentionParameters()).after
        val finished = SkillMemory.finishPractice(mapOf(MemorySkill.SPELLING to repeated, MemorySkill.LISTENING to healthy), now)
        assertEquals(failed.copy(dueAt = MainlineScheduler.dueAfterDays(now, 1)), finished[MemorySkill.SPELLING])
        assertEquals(healthy, finished[MemorySkill.LISTENING])
        val progress = SkillMemory.aggregate(WordProgressEntity(1), finished, now)
        assertFalse(MainlineScheduler.isDueOnOrBefore(progress.dueAt, now))
        assertEquals("relearn", progress.category)
        assertEquals(0, MainlineScheduler.daysUntil(now, today))
    }

    @Test fun lapseNeverRaisesStabilityAcrossParameterAndTimeBoundaries() {
        for (s in listOf(0.1, 1.0, 10.0, 1000.0)) for (d in listOf(1.0, 5.0, 10.0))
            for (elapsed in listOf(1.0, 30.0, 3650.0)) for (lapse in listOf(1.0, 1.9395, 3.0)) {
                val before = RetentionMath.Memory(s, d)
                val parameters = RetentionParameters(lapse = lapse)
                assertTrue(RetentionMath.reviewAgain(before, elapsed, parameters).stability <= s)
                assertTrue(SkillMemory.transition(before, false, elapsed, 0.65, parameters).stability <= s + 1e-12)
            }
    }

    @Test fun oldFormulaAndExposedRowsNeverEnterTheNewOptimizer() {
        fun row(id: Long, time: Long, previous: Long) = ReviewLogEntity(id = id, wordId = 1, timestamp = time,
            correct = true, questionType = "SPELLING", eventKind = "schedule", scheduleGrade = "GOOD",
            memoryAlgorithm = SkillMemory.ALGORITHM, memorySkill = "SPELLING", memoryWeight = 1.0,
            memoryBeforeStability = 7.0, memoryBeforeDifficulty = 5.0, memoryPreviousReviewAt = previous, memoryIndependent = true)
        val t = now - 5 * SkillMemory.DAY
        val rows = listOf(row(1, t, t - 2 * SkillMemory.DAY), row(2, t + 2 * SkillMemory.DAY, t))
        assertEquals(1, RetentionOptimizer.sequences(rows, now).size)
        assertTrue(RetentionOptimizer.sequences(rows.map { it.copy(memoryAlgorithm = SkillMemory.LEGACY_ALGORITHM) }, now).isEmpty())
        assertTrue(RetentionOptimizer.sequences(rows.map { it.copy(memoryExposed = true) }, now).isEmpty())
        assertTrue(RetentionOptimizer.sequences(rows.map { it.copy(memoryAlgorithm = RetentionParameters.LEGACY_ALGORITHM) }, now).isEmpty())
    }

    @Test fun upgradingAProfilePreservesCurrentAndPreviousParametersAndSwitchState() {
        val old = RetentionProfile(algorithm = RetentionParameters.LEGACY_ALGORITHM, gain = 1.1, lapse = 1.3,
            previousGain = 1.7, previousLapse = 2.3, autoEnabled = false, revision = 4, sampleCount = 500,
            lastAttemptAt = now, lastAttemptThrough = now - 1)
        val upgraded = old.upgradeLegacy()
        assertTrue(upgraded.isValid())
        assertEquals(old.parameters(), upgraded.parameters())
        assertEquals(old.previousParameters(), upgraded.previousParameters())
        assertFalse(upgraded.autoEnabled)
        assertEquals(4, upgraded.revision)
        assertEquals(0, upgraded.sampleCount)
        assertEquals(0L, upgraded.lastAttemptThrough)
    }

    @Test fun dualCheckpointValidatesAnswerOrderAndKeepsTheOriginalWordOrder() {
        val cp = DualTestCheckpoint("NEW_WORDS:test", MainlineTestKind.NEW_WORDS, listOf(3, 1),
            preparedAt = now - 1000, answeredAt = now, holdResult = true, holdCorrect = true, input = "cancel")
        assertTrue(cp.valid())
        val next = cp.afterAnswer(true)!!
        assertEquals(listOf(3L, 1L), next.wordIds)
        assertEquals(mapOf(3L to true), next.listening)
        assertEquals(1, next.index)
        assertFalse(next.copy(listening = mapOf(1L to true)).valid())
        assertFalse(next.copy(round = 1).valid())
    }
}
