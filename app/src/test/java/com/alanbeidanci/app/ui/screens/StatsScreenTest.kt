package com.alanbeidanci.app.ui.screens

import com.alanbeidanci.app.data.BookMasteryGroup
import com.alanbeidanci.app.data.UnitMasteryItem
import com.alanbeidanci.app.data.WordBookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class StatsScreenTest {
    @Test
    fun levelLabelAddsVolumeSuffixToMergedSchoolBooks() {
        assertEquals("湘少英语三年级上册", normalizeMasteryLevelLabel("湘少英语三年级上"))
        assertEquals("新概念英语第一册", normalizeMasteryLevelLabel("新概念英语第一册"))
    }

    @Test
    fun levelLabelFallsBackToExplicitGradeInBookName() {
        assertEquals("六年级下册", masteryLevelLabel("上海新教材 六年级下", ""))
        assertEquals("", masteryLevelLabel("大学英语四级", ""))
    }

    @Test
    fun summaryShowsCountWhenBookContainsMultipleVolumes() {
        val group = BookMasteryGroup(
            book = WordBookEntity("xiangshao-all", "湘少版 小学英语", "", "", "", 0, 0),
            units = listOf(
                UnitMasteryItem(unitName = "Unit 1", folderName = "湘少英语三年级上"),
                UnitMasteryItem(unitName = "Unit 2", folderName = "湘少英语三年级上"),
                UnitMasteryItem(unitName = "Unit 1", folderName = "湘少英语三年级下"),
            ),
        )

        assertEquals("2 个年级/册次", masteryLevelSummary(group))
    }
}
