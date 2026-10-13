package com.alanbeidanci.app.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** 记忆逻辑评估：到期的长期记忆仍需维护，不能被状态筛选永久排除。 */
class MaintenanceReviewRegressionTest {
    @Test
    fun dueFixedWordMustRemainInTheReviewPlan() {
        val date = LocalDate.of(2026, 10, 13)
        val now = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val word = WordEntity(1, "book", "cancel", "", "", "取消", "v.", "", "", 1, 1)
        val progress = WordProgressEntity(
            wordId = word.id,
            attempts = 10,
            category = "fixed",
            isMastered = true,
            masteryStreak = 5,
            fixedAt = now - 40L * 86_400_000,
            dueAt = now - 10L * 86_400_000,
            mainlineClassifiedDate = date.minusDays(40).toString(),
        )
        val plan = MainlineScheduler.plan(
            listOf(word), listOf(progress),
            UserSettings(currentBookId = "book", selectedBookIds = setOf("book")),
            now = now,
        )
        assertEquals("已经超过维护日期的已牢记单词仍应进入复习", listOf(word.id), plan.consolidationWords.map { it.id })
    }
}
