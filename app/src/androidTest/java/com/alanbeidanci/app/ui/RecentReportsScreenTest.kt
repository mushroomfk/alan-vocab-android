package com.alanbeidanci.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alanbeidanci.app.network.DailyMainlineErrorReport
import com.alanbeidanci.app.network.MainlineErrorTypeSummary
import com.alanbeidanci.app.network.MainlineErrorWordSummary
import com.alanbeidanci.app.network.RepeatRecording
import com.alanbeidanci.app.ui.screens.MainlineErrorReportContent
import com.alanbeidanci.app.ui.screens.RepeatRecordingsContent
import com.alanbeidanci.app.ui.theme.AlanVocabTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RecentReportsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun mainlineErrorReportShowsSummaryAndWordDetails() {
        val today = LocalDate.now()
        val report = DailyMainlineErrorReport(
            date = today.toString(),
            englishAnswers = 10,
            incorrectAnswers = 2,
            uniqueErrorWords = 1,
            accuracyPercent = 80,
            errorTypes = listOf(
                MainlineErrorTypeSummary("MISSING", "漏字母", 2, listOf("violin"), "分音节慢读后再拼写。"),
            ),
            words = listOf(
                MainlineErrorWordSummary(1, "violin", "violi", 2, listOf("MISSING"), listOf("REMEDY")),
            ),
        )
        composeRule.setContent {
            AlanVocabTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainlineErrorReportContent(
                        MainlineErrorReportUiState(
                            periodEnd = today,
                            selectedDate = today,
                            reports = mapOf(today to report),
                            loading = false,
                        ),
                    )
                }
            }
        }

        composeRule.onNodeWithText("近期易错知识点").assertIsDisplayed()
        composeRule.onNodeWithText("近 7 天正确率").assertIsDisplayed()
        composeRule.onNodeWithTag("mainline-error-report-list").performScrollToIndex(8)
        composeRule.onNodeWithText("violin").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("最近输入：violi").assertIsDisplayed()
        saveScreenshot("mainline-error-report.png")
    }

    @Test
    fun repeatRecordingsShowsRatingAndPlaybackActions() {
        val today = LocalDate.now().toString()
        val recording = RepeatRecording(
            id = "recording-1",
            wordId = 1,
            word = "old",
            translation = "老的；旧的",
            partOfSpeech = "adj.",
            mode = "REVIEW",
            accent = "US",
            recordedDate = today,
            recordedAt = "2026-08-31T08:00:00Z",
            durationMs = 1900,
            assessmentStatus = "READY",
            assessmentScore = 84,
            assessmentStars = 4,
            assessmentCode = "MATCHED",
            contentType = "audio/webm",
        )
        composeRule.setContent {
            AlanVocabTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RepeatRecordingsContent(
                        RepeatRecordingsUiState(recordings = listOf(recording), loading = false),
                    )
                }
            }
        }

        composeRule.onNodeWithText("跟读记录").assertIsDisplayed()
        composeRule.onNodeWithText("4.0").assertIsDisplayed()
        composeRule.onNodeWithText("old").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("标准发音").assertIsDisplayed()
        composeRule.onNodeWithText("我的录音").assertIsDisplayed()
        composeRule.onNodeWithText("★★★★☆").assertIsDisplayed()
        saveScreenshot("repeat-recordings.png")
    }

    private fun saveScreenshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(requireNotNull(context.getExternalFilesDir(null)), name)
        composeRule.onRoot().captureToImage().asAndroidBitmap().compress(
            Bitmap.CompressFormat.PNG,
            100,
            file.outputStream(),
        )
    }
}
