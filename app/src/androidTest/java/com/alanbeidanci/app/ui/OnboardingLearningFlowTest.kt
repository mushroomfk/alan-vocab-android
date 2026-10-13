package com.alanbeidanci.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alanbeidanci.app.AlanVocabApplication
import com.alanbeidanci.app.data.DayStat
import com.alanbeidanci.app.data.StatsReport
import com.alanbeidanci.app.data.UserSettings
import com.alanbeidanci.app.ui.StatsSyncUiState
import com.alanbeidanci.app.ui.screens.HomeScreen
import com.alanbeidanci.app.ui.screens.StatsReportContent
import com.alanbeidanci.app.ui.theme.AlanVocabTheme
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OnboardingLearningFlowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val application: AlanVocabApplication
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetState() = runBlocking {
        application.container.preferences.resetForTesting()
        application.container.preferences.completeOnboarding()
        application.container.repository.ensureSeeded()
        application.container.repository.restore(emptyList(), emptyList())
    }

    @Test
    fun homeShowsFirstMainlineLesson() {
        composeRule.setContent {
            val settings by application.container.preferences.settings.collectAsState(initial = UserSettings())
            AlanVocabTheme(darkTheme = settings.darkMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HomeScreen(
                        container = application.container,
                        onStartMainline = {},
                        onBooks = {},
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("今天要做的事").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("开始学今天的词").assertIsDisplayed()
        composeRule.onNodeWithText("今天先学", substring = true).assertIsDisplayed()
    }

    @Test
    fun statsReportShowsCrossDeviceDetailsAndRefreshAction() {
        val report = StatsReport(
            todayDurationMs = 480000,
            todayWords = 2,
            todayAnswers = 4,
            todayAccuracy = 75,
            todayMainlineAnswers = 2,
            todayMainlineAccuracy = 50,
            streakDays = 1,
            days = listOf(
                DayStat(
                    label = "08-14（五）",
                    durationMs = 480000,
                    words = 2,
                    newWords = 1,
                    reviewWords = 1,
                    masteredWords = 1,
                    answers = 4,
                    methodDist = "拼写2 · 听音1 · 词义1",
                ),
            ),
        )
        composeRule.setContent {
            AlanVocabTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StatsReportContent(
                        report = report,
                        syncState = StatsSyncUiState(),
                    )
                }
            }
        }

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("学习效果报告").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("学习效果报告").assertIsDisplayed()
        composeRule.onNodeWithText("2 词").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("2 题").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("听音与中文题合计 · 正确率 50%").assertIsDisplayed()
        composeRule.onNodeWithTag("stats-report-list").performScrollToIndex(5)
        composeRule.onNodeWithText("拼写2 · 听音1 · 词义1", substring = true).performScrollTo().assertIsDisplayed()

        val screenshot = composeRule.onRoot().captureToImage().asAndroidBitmap()
        val screenshotFile = File(requireNotNull(application.getExternalFilesDir(null)), "stats-report.png")
        screenshotFile.outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Unit
    }
}
