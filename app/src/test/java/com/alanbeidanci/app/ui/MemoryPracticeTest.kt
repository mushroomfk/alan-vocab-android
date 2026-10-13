package com.alanbeidanci.app.ui

import com.alanbeidanci.app.data.RetentionGrade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPracticeTest {
    @Test
    fun aWordTheyCanWriteSkipsHintsAndChoices() {
        val studied = MemoryPractice.afterStudy(MemoryPractice.opening(1, 0))
        assertEquals(PracticeForm.RECALL, studied.state.form)

        val meaning = MemoryPractice.afterCorrect(studied.state) as PracticeEffect.Show
        assertEquals(PracticeForm.PICK_MEANING, meaning.state.form)
        assertEquals(1, meaning.state.cleanRecalls)

        val listened = MemoryPractice.afterCorrect(meaning.state) as PracticeEffect.Show
        assertEquals(PracticeForm.LISTEN, listened.state.form)
        assertEquals(1, listened.state.cleanRecalls)

        val cloze = MemoryPractice.afterCorrect(listened.state) as PracticeEffect.Show
        assertEquals(PracticeForm.CLOZE, cloze.state.form)
        assertEquals(2, cloze.state.cleanRecalls)

        val done = MemoryPractice.afterCorrect(cloze.state) as PracticeEffect.Graduate
        assertFalse(done.shaky)
        assertEquals(RetentionGrade.PRACTICE, done.grade)
    }

    @Test
    fun aMissDropsToLetterHintsAndKeepsEarlierCredits() {
        val hinted = MemoryPractice.afterWrong(
            MemoryState(1, PracticeForm.RECALL, cleanRecalls = 1, studied = true, abilities = AbilityMarks(meaning = 2)),
        ) as PracticeEffect.Show
        assertEquals(PracticeForm.FADED, hinted.state.form)
        assertEquals(1, hinted.state.cleanRecalls)
        assertTrue(hinted.reveal)

        val retry = MemoryPractice.afterCorrect(hinted.state) as PracticeEffect.Show
        assertEquals(PracticeForm.RECALL, retry.state.form)
        assertEquals(1, retry.state.cleanRecalls)

        val listened = MemoryPractice.afterCorrect(retry.state) as PracticeEffect.Show
        assertEquals(PracticeForm.LISTEN, listened.state.form)
        assertEquals(2, listened.state.cleanRecalls)
        assertTrue((MemoryPractice.afterCorrect(listened.state) as PracticeEffect.Graduate).shaky)
    }

    @Test
    fun listenFailureReturnsToWritingNotToChoices() {
        val back = MemoryPractice.afterWrong(MemoryState(1, PracticeForm.LISTEN, cleanRecalls = 1, studied = true)) as PracticeEffect.Show
        assertEquals(PracticeForm.RECALL, back.state.form)
        assertEquals(1, back.state.cleanRecalls)
    }

    @Test
    fun letterHintFailureFallsBackToChoices() {
        val spelling = MemoryPractice.afterWrong(MemoryState(1, PracticeForm.FADED, studied = true)) as PracticeEffect.Show
        assertEquals(PracticeForm.PICK_SPELLING, spelling.state.form)

        val meaning = MemoryPractice.afterWrong(spelling.state) as PracticeEffect.Show
        assertEquals(PracticeForm.PICK_MEANING, meaning.state.form)

        val again = MemoryPractice.afterCorrect(meaning.state) as PracticeEffect.Show
        assertEquals(PracticeForm.RECALL, again.state.form)
        assertEquals(0, again.state.cleanRecalls)
    }

    @Test
    fun fourthMissParksAtAnEasierRung() {
        var state = MemoryState(1, PracticeForm.RECALL, studied = true)
        var parked: PracticeEffect.Park? = null
        repeat(MemoryPractice.MISS_CAP) {
            when (val effect = MemoryPractice.afterWrong(state)) {
                is PracticeEffect.Show -> state = effect.state
                is PracticeEffect.Park -> parked = effect
                is PracticeEffect.Graduate -> error("wrong answer must not graduate")
                is PracticeEffect.Defer -> error("a miss is not deferred")
            }
        }
        assertEquals(1, parked?.rung)
        assertEquals(PracticeForm.PICK_MEANING, MemoryPractice.opening(1, parked!!.rung).form)
    }

    @Test
    fun savedRungSkipsTheFirstLook() {
        val session = MemoryPractice.begin(listOf(7), mapOf(7L to 4))
        assertEquals(PracticeForm.RECALL, session.showing?.form)
        assertTrue(session.pending.isEmpty())
    }

    @Test
    fun successWaitsBehindOtherWords() {
        val queue = mutableListOf(
            MemoryState(2, PracticeForm.STUDY),
            MemoryState(3, PracticeForm.STUDY),
            MemoryState(4, PracticeForm.STUDY),
        )
        MemoryPractice.place(queue, MemoryState(1, PracticeForm.RECALL, cleanRecalls = 1), MemoryPractice.SUCCESS_GAP)
        assertEquals(listOf(2L, 3L, 4L, 1L), queue.map { it.wordId })
    }

    @Test
    fun loneWordDoesNotRepeatAProductionImmediately() {
        val ready = MemoryState(
            1,
            PracticeForm.PICK_MEANING,
            cleanRecalls = 1,
            studied = true,
            hasExample = false,
            abilities = AbilityMarks(meaning = 1, spelling = 2, listening = 2, context = -1),
        )
        val deferred = MemoryPractice.afterCorrect(ready, queueSize = 0)
        assertTrue(deferred is PracticeEffect.Defer)
        assertEquals(PracticeForm.RECALL, (deferred as PracticeEffect.Defer).state.form)

        val spaced = MemoryPractice.afterCorrect(ready, queueSize = 2) as PracticeEffect.Show
        assertEquals(PracticeForm.RECALL, spaced.state.form)
    }

    @Test
    fun missingExampleIsNotRecordedAsContext() {
        val state = MemoryState(
            1,
            PracticeForm.LISTEN,
            cleanRecalls = 1,
            studied = true,
            hasExample = false,
            abilities = AbilityMarks(spelling = 2, meaning = 2, context = -1),
        )
        val next = MemoryPractice.afterCorrect(state, queueSize = 3) as PracticeEffect.Show
        assertEquals(PracticeForm.RECALL, next.state.form)
        assertEquals(-1, next.state.abilities.context)
    }

    @Test
    fun reviewUsesTheFirstAttemptAndDoesNotEraseAMiss() {
        val clean = MemoryPractice.afterCorrect(
            MemoryState(1, PracticeForm.RECALL, phase = PracticePhase.REVIEW, studied = true),
        ) as PracticeEffect.Graduate
        assertEquals(RetentionGrade.GOOD, clean.grade)
        assertFalse(clean.shaky)

        val missed = MemoryPractice.afterWrong(
            MemoryState(1, PracticeForm.RECALL, phase = PracticePhase.REVIEW, studied = true),
        ) as PracticeEffect.Show
        assertEquals(FirstResult.WRONG, missed.state.firstResult)
        val recovered = MemoryPractice.afterCorrect(missed.state) as PracticeEffect.Graduate
        assertEquals(RetentionGrade.LAPSE, recovered.grade)
        assertTrue(recovered.shaky)

        val hinted = MemoryPractice.afterCorrect(
            MemoryState(1, PracticeForm.RECALL, phase = PracticePhase.REVIEW, studied = true, hinted = true),
        ) as PracticeEffect.Graduate
        assertEquals(RetentionGrade.RECHECK, hinted.grade)
    }

    @Test
    fun knownWordOnlyAddsTheWeakAbilityAfterACleanRecall() {
        val probe = MemoryPractice.afterCorrect(
            MemoryState(
                1,
                PracticeForm.RECALL,
                phase = PracticePhase.REVIEW,
                studied = true,
                abilities = AbilityMarks(listening = 1),
            ),
        ) as PracticeEffect.Show
        assertEquals(PracticeForm.LISTEN, probe.state.form)
        assertEquals(FirstResult.CORRECT, probe.state.firstResult)
        val failedProbe = MemoryPractice.afterWrong(probe.state) as PracticeEffect.Graduate
        assertEquals(RetentionGrade.GOOD, failedProbe.grade)
        assertEquals(1, failedProbe.abilities.listening)
    }

    @Test
    fun skipIsNotAFailure() {
        val parked = MemoryPractice.afterSkip(MemoryState(1, PracticeForm.RECALL, phase = PracticePhase.REVIEW, studied = true))
        assertEquals(StopReason.SKIP, parked.reason)
    }

    @Test
    fun letterHintKeepsTheEndsAndHidesTheMiddle() {
        assertEquals("c···e", MemoryPractice.letterHint("close"))
        assertEquals("a·", MemoryPractice.letterHint("at"))
        assertEquals("·", MemoryPractice.letterHint("a"))
    }
}
