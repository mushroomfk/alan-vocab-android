package com.alanbeidanci.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LearningStatsMergeTest {
    @Test
    fun pendingEventsContributeWordsMethodsAndMainlineAccuracyWithoutDuplicatingWordCount() {
        val date = "2026-08-14"
        val progress = mapOf(
            1L to WordProgressEntity(wordId = 1, mainlineLearnedDate = date),
            2L to WordProgressEntity(wordId = 2, isMastered = true, category = "mastered"),
        )
        val events = listOf(
            event("a", 1, "LISTENING", correct = true, dual = true, mainline = true),
            event("b", 1, "MEANING", correct = false, dual = true, mainline = true),
            event("c", 2, "SPELLING", correct = true),
        )

        val row = requireNotNull(pendingDayStatsDeltas(events, progress)[date])

        assertEquals(3, row.answers)
        assertEquals(2, row.correct)
        assertEquals(setOf(1L, 2L), row.wordIds)
        assertEquals(setOf(1L), row.newWordIds)
        assertEquals(1, row.reviewWords)
        assertEquals(setOf(2L), row.masteredWordIds)
        assertEquals(1, row.listeningAnswers)
        assertEquals(1, row.meaningAnswers)
        assertEquals(1, row.spellingAnswers)
        assertEquals(2, row.mainlineAnswers)
        assertEquals(1, row.mainlineCorrect)
    }

    @Test
    fun pendingMainlineDimensionsSeparateListeningMeaningAndSpellingQuality() {
        val events = listOf(
            event("a", 1, "LISTENING", correct = true, dual = true, mainline = true, spellingQuality = "exact"),
            event("b", 2, "LISTENING", correct = false, dual = true, mainline = true, spellingQuality = "near"),
            event("c", 3, "MEANING", correct = false, dual = true, mainline = true, spellingQuality = "unknown"),
            event("d", 4, "MEANING", correct = true, dual = false, mainline = false, spellingQuality = "exact"),
        )

        val dimensions = pendingMainlineDimensions(events)

        assertEquals(MasteryMetric(correct = 1, total = 2), dimensions.listening)
        assertEquals(MasteryMetric(correct = 0, total = 1), dimensions.meaning)
        assertEquals(MasteryMetric(correct = 1, total = 3, near = 1), dimensions.spelling)
    }

    private fun event(
        id: String,
        wordId: Long,
        type: String,
        correct: Boolean,
        dual: Boolean = false,
        mainline: Boolean = false,
        spellingQuality: String? = null,
    ) = PendingSyncEventEntity(
        eventId = id,
        deviceId = "device",
        wordId = wordId,
        eventType = type,
        correct = correct,
        clientTime = "2026-08-14T02:00:00Z",
        clientSeq = id.first().code.toLong(),
        isDual = dual,
        isMainline = mainline,
        spellingQuality = spellingQuality,
    )
}
