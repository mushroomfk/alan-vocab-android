package com.alanbeidanci.app.ui

import com.alanbeidanci.app.network.DailyMainlineErrorReport
import com.alanbeidanci.app.network.MainlineErrorWordSummary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentReportsTest {
    @Test
    fun errorReportPeriodContainsSevenOrderedDays() {
        val end = LocalDate.of(2026, 8, 30)
        val dates = mainlineErrorPeriodDates(end)

        assertEquals(7, dates.size)
        assertEquals(LocalDate.of(2026, 8, 24), dates.first())
        assertEquals(end, dates.last())
    }

    @Test
    fun summaryDeduplicatesErrorWordsAcrossDays() {
        val shared = MainlineErrorWordSummary(9L, "old", "od", 1, listOf("MISSING_LETTER"), listOf("REMEDY"))
        val reports = listOf(
            DailyMainlineErrorReport("2026-08-29", 10, 2, 1, 80, emptyList(), listOf(shared)),
            DailyMainlineErrorReport("2026-08-30", 5, 1, 1, 80, emptyList(), listOf(shared)),
        )

        assertEquals(MainlineErrorPeriodSummary(15, 3, 1), summarizeMainlineErrors(reports))
    }

    @Test
    fun assessmentLabelsExplainMissingStars() {
        assertEquals("录音时的浏览器不支持评星", pronunciationAssessmentLabel("UNSUPPORTED"))
        assertEquals("语音分析超时", pronunciationAssessmentLabel("ANALYSIS_TIMEOUT"))
    }
}
