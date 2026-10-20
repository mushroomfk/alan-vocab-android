package com.alanbeidanci.app.data

import com.alanbeidanci.app.ui.*
import org.junit.Assert.*
import org.junit.Test

class SkillMemoryTest {
    private val now = 1_800_000_000_000L
    private val old = SkillState(7.0, 5.0, now - 7 * SkillMemory.DAY, now, 3, 0, "GOOD")

    @Test fun sameResultHasDifferentWeightForLearningReviewAndNewTest() {
        fun answer(source: MemorySource) = SkillMemory.update(old, true, true, false, source, "RECALL", now, RetentionParameters())
        assertTrue(answer(MemorySource.REVIEW).after.stability > answer(MemorySource.NEW_TEST).after.stability)
        assertTrue(answer(MemorySource.NEW_TEST).after.stability > answer(MemorySource.LEARN).after.stability)
        assertTrue(answer(MemorySource.REVIEW).after.difficulty < answer(MemorySource.LEARN).after.difficulty)
    }

    @Test fun choiceIsWeakerEvidenceThanIndependentProduction() {
        val choice = SkillMemory.update(old, true, true, false, MemorySource.REVIEW, "PICK_MEANING", now, RetentionParameters())
        val write = SkillMemory.update(old, true, true, false, MemorySource.REVIEW, "RECALL", now, RetentionParameters())
        assertTrue(choice.after.stability < write.after.stability)
        assertTrue(choice.after.difficulty > write.after.difficulty)
    }

    @Test fun hintsAndRepairRepetitionsDoNotEraseTheFirstFailure() {
        val fail = SkillMemory.update(old, false, true, false, MemorySource.REVIEW, "LISTEN", now, RetentionParameters())
        val repair = SkillMemory.update(fail.after, true, false, false, MemorySource.REVIEW, "LISTEN", now + 10000, RetentionParameters())
        assertEquals(fail.after, repair.after)
        val hint = SkillMemory.update(old, true, true, true, MemorySource.REVIEW, "RECALL", now, RetentionParameters())
        assertEquals(old.stability, hint.after.stability, 0.0)
        assertEquals(old.difficulty, hint.after.difficulty, 0.0)
        assertFalse(hint.independent)
        assertEquals("HINT", hint.after.outcome)
    }

    @Test fun sameDayTestFailureStillRequiresRepairWithoutInflatingStability() {
        val sameDay = old.copy(lastAt = now - 1000)
        val failed = SkillMemory.update(sameDay, false, true, false, MemorySource.TEST, "LISTEN", now, RetentionParameters())
        assertEquals(0.0, failed.weight, 0.0)
        assertEquals("LAPSE", failed.after.outcome)
        assertEquals(sameDay.stability, failed.after.stability, 0.0)
        assertEquals(1, MainlineScheduler.daysUntil(now, failed.after.dueAt))
    }

    @Test fun timeElapsedAffectsStabilityAndUrgency() {
        val earlier = old.copy(lastAt = now - SkillMemory.DAY)
        val a = SkillMemory.update(earlier, true, true, false, MemorySource.REVIEW, "RECALL", now, RetentionParameters())
        val b = SkillMemory.update(old, true, true, false, MemorySource.REVIEW, "RECALL", now, RetentionParameters())
        assertTrue(b.after.stability > a.after.stability)
    }

    @Test fun aggregateUsesEarliestDueAndIncludesWeakestDifficulty() {
        val good = old.copy(stability = 35.0, difficulty = 2.0, dueAt = MainlineScheduler.dueAfterDays(now, 30))
        val weak = old.copy(stability = 1.0, difficulty = 9.0, dueAt = MainlineScheduler.dueAfterDays(now, 1), outcome = "LAPSE")
        val states = mapOf(MemorySkill.SPELLING to good, MemorySkill.MEANING to good, MemorySkill.LISTENING to weak)
        val result = SkillMemory.aggregate(WordProgressEntity(1, isFavorite = true), states, now)
        assertEquals(1, MainlineScheduler.daysUntil(now, result.dueAt))
        assertTrue(result.difficulty > 4.0)
        assertFalse(result.isMastered)
        assertEquals("relearn", result.category)
        assertTrue(result.isFavorite)
        assertEquals(states, SkillMemory.decode(result.skillMemory))
    }

    @Test fun reviewStartsWithWeakListeningAndSkipsStrongFutureSpelling() {
        val healthy = old.copy(dueAt = MainlineScheduler.dueAfterDays(now, 20))
        val weak = old.copy(difficulty = 8.5, outcome = "LAPSE")
        val plan = SkillPracticePlan.create(mapOf(MemorySkill.SPELLING to healthy, MemorySkill.LISTENING to weak), true, now)
        assertEquals(listOf(MemorySkill.LISTENING), plan.targets.map { it.skill })
        assertEquals(3, plan.targets.single().required)
    }

    @Test fun laterFailureCannotFinishAWordWhoseFirstSkillPassed() {
        val plan = SkillPracticePlan(listOf(SkillPracticeTarget(MemorySkill.SPELLING, 1), SkillPracticeTarget(MemorySkill.LISTENING, 1)), true)
        val start = MemoryState(1, PracticeForm.RECALL, phase = PracticePhase.REVIEW, plan = plan)
        val second = MemoryPractice.afterCorrect(start) as PracticeEffect.Show
        assertEquals(PracticeForm.LISTEN, second.state.form)
        val failed = MemoryPractice.afterWrong(second.state)
        assertTrue(failed is PracticeEffect.Show)
        val retry = (failed as PracticeEffect.Show).state
        assertEquals(PracticeForm.LISTEN, retry.form)
        assertEquals(2, retry.plan!!.targets.last().required)
        val once = MemoryPractice.afterCorrect(retry) as PracticeEffect.Show
        assertEquals(1, once.state.plan!!.targets.last().passed)
        val done = MemoryPractice.afterCorrect(once.state) as PracticeEffect.Graduate
        assertEquals(RetentionGrade.LAPSE, done.grade)
    }

    @Test fun savedTargetsPreserveCreditsAndFirstAttemptAcrossRestart() {
        val plan = SkillPracticePlan(listOf(SkillPracticeTarget(MemorySkill.LISTENING, 3, 1, 2)), true)
        assertEquals(plan, SkillPracticePlan.decode(plan.encode()))
        val queue = MemoryPractice.begin(listOf(1), mapOf(1L to SavedPractice(form = PracticeForm.LISTEN,
            hasSession = true, plan = plan)), PracticePhase.REVIEW, mapOf(1L to false))
        assertEquals(plan, queue.showing!!.plan)
        assertFalse(queue.showing!!.plan!!.first(MemorySkill.LISTENING))
        assertEquals(PracticeForm.LISTEN, queue.showing!!.form)
    }

    @Test fun aSingleWordDefersAnotherIdenticalProductionInsteadOfRepeatingImmediately() {
        val plan = SkillPracticePlan(listOf(SkillPracticeTarget(MemorySkill.SPELLING, 2)), false)
        val next = MemoryPractice.afterCorrect(MemoryState(1, PracticeForm.RECALL, plan = plan), queueSize = 0)
        assertTrue(next is PracticeEffect.Defer)
        assertEquals(1, (next as PracticeEffect.Defer).state.plan!!.targets.single().passed)
    }

    @Test fun newWordsPracticeMeaningBeforeCloze() {
        val now = System.currentTimeMillis()
        val plan = SkillPracticePlan.create(SkillMemory.states(WordProgressEntity(1), true), false, now)
        assertEquals(MemorySkill.MEANING, plan.targets.first().skill)
        assertEquals(2, plan.targets.first().required)
        assertEquals(MemorySkill.CONTEXT, plan.targets.last().skill)
    }

    @Test fun familiarWordsShiftTowardRecallAndCloze() {
        val known = SkillState(
            stability = 30.0,
            difficulty = 3.0,
            lastAt = now - 2 * SkillMemory.DAY,
            dueAt = now - 1_000,
            successes = 4,
            outcome = "GOOD",
        )
        val fresh = SkillState(dueAt = now - 1_000)
        val plan = SkillPracticePlan.create(
            mapOf(
                MemorySkill.MEANING to known,
                MemorySkill.LISTENING to known,
                MemorySkill.SPELLING to fresh,
                MemorySkill.CONTEXT to fresh,
            ),
            false,
            now,
        )
        val order = plan.targets.map { it.skill }
        assertTrue(order.indexOf(MemorySkill.SPELLING) < order.indexOf(MemorySkill.MEANING))
        assertTrue(order.indexOf(MemorySkill.CONTEXT) < order.indexOf(MemorySkill.MEANING))
        assertEquals(2, plan.targets.first { it.skill == MemorySkill.SPELLING }.required)
        assertEquals(2, plan.targets.first { it.skill == MemorySkill.CONTEXT }.required)
        assertEquals(1, plan.targets.first { it.skill == MemorySkill.MEANING }.required)
    }

    @Test fun givingUpOrMissingClozeSwitchesToMeaningChoices() {
        val plan = SkillPracticePlan(listOf(
            SkillPracticeTarget(MemorySkill.LISTENING, 1),
            SkillPracticeTarget(MemorySkill.CONTEXT, 1),
        ), false)
        val gaveUp = MemoryPractice.afterWrong(
            MemoryState(1, PracticeForm.LISTEN, plan = plan),
            gaveUp = true,
        ) as PracticeEffect.Show
        assertEquals(PracticeForm.PICK_MEANING, gaveUp.state.form)
        assertEquals(MemorySkill.MEANING, gaveUp.state.plan!!.targets.first().skill)
        assertTrue(gaveUp.state.plan!!.targets.first().required >= 2)
        assertEquals(MemorySkill.CONTEXT, gaveUp.state.plan!!.targets.last().skill)

        val cloze = MemoryPractice.afterWrong(MemoryState(1, PracticeForm.CLOZE, plan = plan)) as PracticeEffect.Show
        assertEquals(PracticeForm.PICK_MEANING, cloze.state.form)
    }

    @Test fun unsupportedExampleIsNeverPlannedAndBadBackupStateIsRejected() {
        val states = SkillMemory.states(WordProgressEntity(1), false)
        assertFalse(states.containsKey(MemorySkill.CONTEXT))
        assertFalse(SkillPracticePlan.create(states, false, now).targets.any { it.skill == MemorySkill.CONTEXT })
        assertTrue(runCatching { SkillMemory.decode("v1|LISTENING,NaN,5,0,0,0,0,GOOD") }.isFailure)
        assertTrue(runCatching { SkillPracticePlan.decode("v9|1|LISTENING,2,0,0") }.isFailure)
    }

    @Test fun optimizerNeverCombinesTwoSkillsOfOneWordIntoOneHistory() {
        fun row(id: Long, skill: String, time: Long, previous: Long) = ReviewLogEntity(id = id,
            wordId = 1, timestamp = time, correct = true, questionType = "SPELLING", eventKind = "schedule",
            scheduleGrade = "GOOD", memoryAlgorithm = SkillMemory.ALGORITHM, memoryBeforeStability = 7.0,
            memoryBeforeDifficulty = 5.0, memoryPreviousReviewAt = previous, memoryIndependent = true,
            memorySkill = skill, memoryWeight = 0.65)
        val t = now - 10 * SkillMemory.DAY
        val logs = listOf(row(1, "MEANING", t, t - 2 * SkillMemory.DAY),
            row(2, "LISTENING", t + SkillMemory.DAY, t),
            row(3, "MEANING", t + 2 * SkillMemory.DAY, t),
            row(4, "LISTENING", t + 3 * SkillMemory.DAY, t + SkillMemory.DAY))
        val sequences = RetentionOptimizer.sequences(logs, now)
        assertEquals(2, sequences.size)
        assertTrue(sequences.all { it.skillModel && it.reviews.size == 2 })
        val predicted = RetentionOptimizer.predict(sequences, RetentionParameters())
        assertEquals(2, predicted.size)
        val transitioned = SkillMemory.transition(RetentionMath.Memory(7.0, 5.0), true, 2.0, 0.65, RetentionParameters())
        assertEquals(RetentionMath.recallProbability(transitioned.stability, 2.0), predicted.first().probability, 1e-12)
    }
    @Test fun laterPracticeFailureShortensTheDateWithoutPretendingItIsAnotherIndependentReview() {
        val failed = SkillMemory.update(old, false, false, false, MemorySource.REVIEW, "RECALL", now, RetentionParameters())
        assertEquals(0.0, failed.weight, 0.0)
        assertEquals(old.difficulty, failed.after.difficulty, 0.0)
        assertEquals("LAPSE", failed.after.outcome)
        assertEquals(1, MainlineScheduler.daysUntil(now, failed.after.dueAt))
    }

    @Test fun removalOfAnExampleRemovesItsUnfinishedTargetOnResume() {
        val plan = SkillPracticePlan(listOf(SkillPracticeTarget(MemorySkill.CONTEXT, 2)), true)
        val next = MemoryPractice.begin(listOf(1), mapOf(1L to SavedPractice(form = PracticeForm.CLOZE,
            hasSession = true, plan = plan)), PracticePhase.REVIEW, mapOf(1L to false)).showing!!
        assertNotEquals(PracticeForm.CLOZE, next.form)
        assertFalse(next.plan!!.targets.any { it.skill == MemorySkill.CONTEXT })
        assertEquals(MemoryPractice.formForSkill(next.plan!!.pending()!!.skill), next.form)
    }

}
