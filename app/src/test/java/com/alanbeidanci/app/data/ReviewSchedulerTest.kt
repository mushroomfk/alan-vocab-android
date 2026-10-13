package com.alanbeidanci.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewSchedulerTest {
    @Test
    fun correctAnswerAdvancesProgress() {
        val initial = WordProgressEntity(wordId = 1)
        val result = ReviewScheduler.next(initial, correct = true, now = 1_000)

        assertEquals(1, result.familiarity)
        assertEquals(1, result.streak)
        assertEquals(1, result.attempts)
        assertEquals(1, result.correctCount)
        assertEquals(86_401_000L, result.dueAt)
        assertFalse(result.isMastered)
    }

    @Test
    fun wrongAnswerResetsStreakAndTracksMistake() {
        val initial = WordProgressEntity(wordId = 1, familiarity = 4, streak = 4)
        val result = ReviewScheduler.next(initial, correct = false, now = 1_000)

        assertEquals(3, result.familiarity)
        assertEquals(0, result.streak)
        assertEquals(1, result.wrongCount)
        assertEquals(601_000L, result.dueAt)
    }

    @Test
    fun sustainedCorrectAnswersReachMasteredState() {
        val almostMastered = WordProgressEntity(
            wordId = 1,
            familiarity = 4,
            streak = 4,
            attempts = 4,
            correctCount = 4,
        )

        val result = ReviewScheduler.next(almostMastered, correct = true, now = 1_000)

        assertEquals(5, result.familiarity)
        assertEquals(5, result.streak)
        assertEquals(5, result.attempts)
        assertTrue(result.isMastered)
    }
}
