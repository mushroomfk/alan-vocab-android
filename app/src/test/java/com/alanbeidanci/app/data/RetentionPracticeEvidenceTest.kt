package com.alanbeidanci.app.data

import org.junit.Assert.*
import org.junit.Test

class RetentionPracticeEvidenceTest {
    @Test fun onlyUnchangedCorrectPracticeCanBeSkippedBetweenIndependentReviews() {
        val day = SkillMemory.DAY
        val start = 1_700_000_000_000L
        val first = ReviewLogEntity(id = 1, wordId = 101, timestamp = start + 3 * day,
            correct = true, questionType = "LISTENING", eventKind = "schedule", scheduleGrade = "GOOD",
            memoryAlgorithm = SkillMemory.ALGORITHM, memorySkill = "LISTENING", memoryWeight = 0.8,
            memoryBeforeStability = 4.0, memoryBeforeDifficulty = 5.0,
            memoryPreviousReviewAt = start, memoryIndependent = true)
        val second = first.copy(id = 3, timestamp = first.timestamp + 3 * day, memoryPreviousReviewAt = first.timestamp)
        val practice = first.copy(id = 2, timestamp = first.timestamp + 1000,
            memoryPreviousReviewAt = first.timestamp, memoryIndependent = false, memoryExposed = true, memoryWeight = 0.0)
        val now = second.timestamp + day
        fun predictions(rows: List<ReviewLogEntity>) = RetentionOptimizer.predict(
            RetentionOptimizer.sequences(rows, now), RetentionParameters())
        assertEquals(predictions(listOf(first, second)), predictions(listOf(first, practice, second)))
        for (invalid in listOf(practice.copy(hinted = true), practice.copy(correct = false, scheduleGrade = "LAPSE"),
            practice.copy(memoryPreviousReviewAt = practice.timestamp + 1),
            practice.copy(memoryBeforeStability = Double.NaN), practice.copy(scheduleGrade = "RECHECK"))) {
            assertTrue("提示、失败或非法练习必须中断序列", predictions(listOf(first, invalid, second)).isEmpty())
        }
    }

    @Test fun zeroWeightPracticeMustNotDiscardValidSpacedEvidence() {
        val day = SkillMemory.DAY
        val start = 1_700_000_000_000L
        var state = SkillState(4.0, 5.0, start, start + day, 1, 0, "GOOD")
        val logs = mutableListOf<ReviewLogEntity>()
        repeat(4) { index ->
            val at = start + (index + 1) * 3 * day
            fun answer(first: Boolean, exposed: Boolean, time: Long) {
                val before = state
                val update = SkillMemory.update(before, true, first, false, MemorySource.REVIEW,
                    "LISTEN", time, RetentionParameters(), exposed)
                logs += ReviewLogEntity(id = logs.size + 1L, wordId = 101L, timestamp = time,
                    correct = true, questionType = "LISTENING", eventKind = "schedule", scheduleGrade = "GOOD",
                    firstAttempt = first, memoryAlgorithm = SkillMemory.ALGORITHM, memorySkill = "LISTENING",
                    memoryWeight = update.weight, memorySource = "REVIEW", memoryBeforeStability = before.stability,
                    memoryBeforeDifficulty = before.difficulty, memoryPreviousReviewAt = before.lastAt,
                    memoryIndependent = update.independent, memoryExposed = exposed)
                state = update.after
            }
            answer(true, false, at)
            val independentState = state
            answer(false, true, at + 1000)
            assertEquals("同日正确练习不改变长期状态", independentState, state)
        }
        val now = logs.last().timestamp + day
        val independent = logs.filter { it.memoryIndependent }
        assertTrue(RetentionOptimizer.predict(RetentionOptimizer.sequences(independent, now), RetentionParameters()).isNotEmpty())
        assertTrue("trainingLogs 返回的零权重练习不应抹掉全部合格样本",
            RetentionOptimizer.predict(RetentionOptimizer.sequences(logs, now), RetentionParameters()).isNotEmpty())
    }
}
