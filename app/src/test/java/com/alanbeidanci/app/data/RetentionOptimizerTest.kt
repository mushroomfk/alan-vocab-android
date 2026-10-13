package com.alanbeidanci.app.data

import org.junit.Assert.*
import org.junit.Test
import java.util.Random

class RetentionOptimizerTest {
    private val day = RetentionOptimizer.DAY
    private val start = 1_700_000_000_000L

    private fun history(parameters: RetentionParameters = RetentionParameters(0.9, 1.0)): List<ReviewLogEntity> {
        val random = Random(712)
        return buildList {
            repeat(400) { word ->
                // Each independently seeded episode represents a new auditable chain after a gap.
                repeat(24) { episode ->
                    var memory = RetentionMath.Memory(3.0 + random.nextDouble() * 4.0, 5.0)
                    var previous = start + episode * 200 * day + word * 60_000L
                    repeat(2) { index ->
                        val elapsed = if (index == 0) (30 + random.nextInt(90)).toDouble() else (3 + random.nextInt(12)).toDouble()
                        val time = previous + (elapsed * day).toLong()
                        val good = random.nextDouble() < RetentionMath.recallProbability(memory.stability, elapsed)
                        add(ReviewLogEntity(
                            id = word * 1000L + episode * 2 + index + 1, wordId = word.toLong(), timestamp = time,
                            correct = good, questionType = "SPELLING", eventKind = "schedule",
                            scheduleGrade = if (good) "GOOD" else "LAPSE",
                            memoryAlgorithm = RetentionParameters.ALGORITHM,
                            memoryBeforeStability = memory.stability, memoryBeforeDifficulty = memory.difficulty,
                            memoryPreviousReviewAt = previous, memoryIndependent = true,
                        ))
                        memory = if (good) RetentionMath.reviewGood(memory, elapsed, parameters)
                            else RetentionMath.reviewAgain(memory, elapsed, parameters)
                        previous = time
                    }
                }
            }
        }
    }

    @Test fun adoptsParametersOnlyWhenTheyImproveUnseenLaterRecords() {
        val logs = history()
        val result = RetentionOptimizer.fit(logs, RetentionProfile(), start + 10_000 * day)
        assertEquals(result.toString(), "PERSONALIZED", result.status)
        assertEquals(1, result.revision)
        assertTrue(result.isValid())
        assertTrue(result.candidateLoss < result.baselineLoss)
        assertTrue(result.trainedThrough < result.lastAttemptThrough)
        assertEquals(RetentionParameters(), result.previousParameters())
    }

    @Test fun improvementOnOldHistoryCannotOverrideWorseLaterPredictions() {
        val earlier = history()
        val later = history(RetentionParameters(2.2, 3.0)).associateBy { it.id }
        val cutoff = earlier.sortedBy { it.timestamp }[(earlier.size * 0.75).toInt()].timestamp
        val shifted = earlier.map { if (it.timestamp > cutoff) later.getValue(it.id) else it }
        val result = RetentionOptimizer.fit(shifted, RetentionProfile(), start + 10_000 * day)
        assertEquals("NO_IMPROVEMENT", result.status)
        assertEquals(RetentionParameters(), result.parameters())
    }

    @Test fun matchingDefaultHistoryDoesNotForceAnUpdate() {
        val result = RetentionOptimizer.fit(history(RetentionParameters()), RetentionProfile(), start + 10_000 * day)
        assertEquals(0, result.revision)
        assertTrue(result.status in setOf("NO_IMPROVEMENT", "COLLECTING"))
    }

    @Test fun dataAgeAloneIsInsufficientAndDisabledMeansNoTraining() {
        assertEquals(RetentionProfile(), RetentionOptimizer.fit(emptyList(), RetentionProfile(), start + 10_000 * day))
        val disabled = RetentionProfile(autoEnabled = false)
        assertEquals(disabled, RetentionOptimizer.fit(history(), disabled, start + 10_000 * day))
        val tooSmall = RetentionOptimizer.fit(history().take(100), RetentionProfile(), start + 10_000 * day)
        assertEquals(0, tooSmall.lastAttemptAt)
    }

    @Test fun rejectsLegacyHintedSameDayFutureAndBrokenHistories() {
        val logs = history().filter { it.wordId == 0L }.take(2)
        val now = start + 10_000 * day
        assertEquals(1, RetentionOptimizer.sequences(logs, now).size)
        val changes: List<(ReviewLogEntity) -> ReviewLogEntity> = listOf(
            { it.copy(memoryAlgorithm = "") },
            { it.copy(memoryIndependent = false) },
            { it.copy(timestamp = it.memoryPreviousReviewAt + day / 2) },
            { it.copy(timestamp = now + day) },
            { it.copy(memoryBeforeStability = Double.NaN) },
            { it.copy(memoryBeforeDifficulty = 11.0) },
            { it.copy(memoryPreviousReviewAt = it.memoryPreviousReviewAt + 1) },
            { it.copy(scheduleGrade = "RECHECK") },
        )
        changes.forEach { change ->
            assertTrue(RetentionOptimizer.sequences(listOf(logs[0], change(logs[1])), now).isEmpty())
        }
    }

    @Test fun predictionDoesNotSeeItsOwnOutcome() {
        val logs = history().filter { it.wordId == 0L }.take(3)
        val flipped = logs.dropLast(1) + logs.last().let { it.copy(correct = !it.correct, scheduleGrade = if (it.correct) "LAPSE" else "GOOD") }
        fun predictions(rows: List<ReviewLogEntity>) = RetentionOptimizer.predict(
            RetentionOptimizer.sequences(rows, start + 10_000 * day), RetentionParameters())
        assertEquals(predictions(logs).last().probability, predictions(flipped).last().probability, 0.0)
    }

    @Test fun refusesRepeatedTrainingOnTheSameHoldoutAndHonorsCooldown() {
        val logs = history()
        val now = start + 10_000 * day
        val first = RetentionOptimizer.fit(logs, RetentionProfile(), now)
        assertEquals(first, RetentionOptimizer.fit(logs, first, now + day))
        assertEquals(first, RetentionOptimizer.fit(logs, first, now + 60 * day))
    }

    @Test fun automaticRollbackUsesOnlyPostActivationEvidence() {
        val logs = history()
        val current = RetentionProfile(gain = 2.2, lapse = 3.0, previousGain = 0.9, previousLapse = 1.0,
            revision = 1, activatedAt = start, lastAttemptAt = start)
        val result = RetentionOptimizer.fit(logs, current, start + 10_000 * day)
        assertEquals("ROLLED_BACK", result.status)
        assertEquals(current.previousParameters(), result.parameters())
        assertEquals(current.parameters(), result.previousParameters())
    }

    @Test fun cancellationAndInvalidParametersNeverPublishCandidate() {
        val invalid = RetentionProfile(gain = Double.NaN)
        assertEquals("INVALID_RESET", RetentionOptimizer.fit(emptyList(), invalid, start).status)
        try {
            RetentionOptimizer.fit(history(), RetentionProfile(), start + 10_000 * day) {
                throw java.util.concurrent.CancellationException()
            }
            fail("must cancel")
        } catch (_: java.util.concurrent.CancellationException) { }
    }

    @Test fun sameWordRepeatedManyTimesCannotPassIndependentWordGate() {
        val baseline = (1..100).map { RetentionOptimizer.Prediction(1, it.toLong(), 0.7, false) }
        val better = baseline.map { it.copy(probability = 0.1) }
        assertFalse(RetentionOptimizer.reliablyBetter(better, baseline))
    }

    @Test fun personalParametersChangeFutureScheduleButDoNotMutateOldProgress() {
        val now = start + 30 * day
        val original = WordProgressEntity(wordId = 1, stabilityDays = 3.0, difficulty = 5.0,
            lastReviewedAt = now - 7 * day, dueAt = now)
        val defaults = MainlineScheduler.applyRetention(original, RetentionGrade.GOOD, now)
        val personal = MainlineScheduler.applyRetention(original, RetentionGrade.GOOD, now,
            parameters = RetentionParameters(0.9, 1.0))
        assertTrue(personal.dueAt < defaults.dueAt)
        assertEquals(now, original.dueAt)
        val lapse = MainlineScheduler.applyRetention(original, RetentionGrade.LAPSE, now,
            parameters = RetentionParameters(0.9, 1.0))
        assertEquals("relearn", lapse.category)
        assertEquals(MainlineScheduler.dueAfterDays(now, 1), lapse.dueAt)
    }
}
