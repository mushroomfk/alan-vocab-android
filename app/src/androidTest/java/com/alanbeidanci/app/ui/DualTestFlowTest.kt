package com.alanbeidanci.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alanbeidanci.app.AlanVocabApplication
import com.alanbeidanci.app.data.*
import com.alanbeidanci.app.ui.screens.RemedyTestScreen
import com.alanbeidanci.app.ui.screens.StatsReportContent
import com.alanbeidanci.app.ui.theme.AlanVocabTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DualTestFlowTest {
    @get:Rule val compose = createComposeRule()
    private val app: AlanVocabApplication get() = ApplicationProvider.getApplicationContext()
    private val id = 91_000_001L

    @Before fun setup() = runBlocking {
        if (InstrumentationRegistry.getArguments().getString("memoryProcessStage") == "verify") return@runBlocking
        app.container.repository.ensureSeeded()
        app.container.preferences.resetForTesting()
        app.container.preferences.completeOnboarding()
        app.container.preferences.selectOnlyBook("memory-fix")
        app.container.database.wordBookDao().insert(WordBookEntity("memory-fix", "记忆修复测试", "", "", "", 0, 0))
        app.container.database.wordDao().insert(WordEntity(id, "memory-fix", "cancel", "", "", "取消", "v.", "", "", 1, 1))
        app.container.repository.restore(listOf(WordProgressEntity(id, attempts = 1,
            mainlineLearnedDate = MainlineScheduler.dateKey(), mainlineDualPending = true)), emptyList())
    }

    private fun open(): RemedyTestViewModel {
        lateinit var vm: RemedyTestViewModel
        compose.runOnIdle { vm = RemedyTestViewModel(app.container, MainlineTestKind.NEW_WORDS) }
        compose.waitUntil(20000) { !vm.uiState.value.loading }
        return vm
    }

    private fun start(vm: RemedyTestViewModel) {
        compose.runOnIdle { vm.start() }
        compose.waitUntil(10000) { vm.uiState.value.started && !vm.uiState.value.locked }
    }

    @Test fun englishRoundResumesAtChineseWithoutRepeatingOrAddingAnAnswer() {
        val vm = open(); start(vm)
        compose.runOnIdle { vm.updateInput("cancel"); vm.acknowledgeResult() }
        compose.waitUntil(10000) { vm.uiState.value.round == RemedyRound.CHINESE && !vm.uiState.value.locked }
        val count = runBlocking { app.container.repository.allLogs().count { it.countsAsAnswer() } }
        val resumed = open()
        assertEquals(RemedyRound.CHINESE, resumed.uiState.value.round)
        assertEquals(1, resumed.uiState.value.listenScore)
        assertEquals(1, resumed.uiState.value.completedQuestions)
        start(resumed)
        assertEquals(count, runBlocking { app.container.repository.allLogs().count { it.countsAsAnswer() } })
        compose.runOnIdle { resumed.updateInput("cancel"); resumed.acknowledgeResult() }
        compose.waitUntil(10000) { resumed.uiState.value.completed }
        assertEquals(1, resumed.uiState.value.bothCorrect)
        assertEquals(2, runBlocking { app.container.repository.allLogs().count { it.countsAsAnswer() } })
        assertTrue(runBlocking { app.container.database.dualTestSessionDao().getAll().isEmpty() })
    }

    @Test fun pendingWrongFeedbackRestoresOnTheActualScreenBeforeAcknowledgement() {
        val vm = open(); start(vm)
        compose.runOnIdle { vm.giveUp() }
        compose.waitUntil(10000) { runBlocking { app.container.database.dualTestSessionDao().getAll().singleOrNull()?.checkpoint()?.holdResult == true } }
        val resumed = open()
        assertTrue(resumed.uiState.value.holdResult)
        assertFalse(resumed.uiState.value.holdCorrect)
        assertEquals(0, runBlocking { app.container.repository.allLogs().count { it.countsAsAnswer() } })
        val settings = runBlocking { app.container.preferences.snapshot().copy(autoSpeak = false) }
        compose.setContent { AlanVocabTheme { RemedyTestScreen(app.container, settings, MainlineTestKind.NEW_WORDS, {}, resumed) } }
        compose.runOnIdle { resumed.start() }
        compose.waitUntil(10000) { resumed.uiState.value.started && resumed.uiState.value.holdResult }
        compose.onNodeWithText("下一个").assertIsDisplayed()
        compose.onAllNodesWithText("cancel").assertCountEquals(2)
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(app.getExternalFilesDir(null), "dual-test-restored-result.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.runOnIdle { resumed.acknowledgeResult() }
        compose.waitUntil(10000) { resumed.uiState.value.round == RemedyRound.CHINESE && !resumed.uiState.value.locked }
        assertEquals(0, resumed.uiState.value.listenScore)
        assertEquals(1, runBlocking { app.container.repository.allLogs().count { it.countsAsAnswer() } })
    }

    @Test fun checkpointSurvivesProcessTermination() {
        val stage = InstrumentationRegistry.getArguments().getString("memoryProcessStage")
        if (stage != "verify") {
            val vm = open(); start(vm)
            compose.runOnIdle { vm.updateInput("cancel"); vm.acknowledgeResult() }
            compose.waitUntil(10000) { vm.uiState.value.round == RemedyRound.CHINESE && !vm.uiState.value.locked }
            compose.runOnIdle { vm.updateInput("xxxxxx") }
            compose.waitUntil(10000) { runBlocking { app.container.database.dualTestSessionDao().getAll().singleOrNull()?.checkpoint()?.holdResult == true } }
            assertEquals(1, runBlocking { app.container.repository.allLogs().count { it.countsAsAnswer() } })
            if (stage == "prepare") return // host force-stops the process before invoking stage=verify
        }
        val resumed = open()
        assertEquals(RemedyRound.CHINESE, resumed.uiState.value.round)
        assertTrue(resumed.uiState.value.holdResult)
        assertFalse(resumed.uiState.value.holdCorrect)
        val cp = runBlocking { app.container.database.dualTestSessionDao().getAll().single().checkpoint() }
        val before = runBlocking { app.container.repository.allLogs().count { it.countsAsAnswer() } }
        assertEquals(1, before)
        compose.runOnIdle { resumed.start() }
        compose.waitUntil(10000) { resumed.uiState.value.started && resumed.uiState.value.holdResult }
        compose.runOnIdle { resumed.acknowledgeResult() }
        compose.waitUntil(10000) { resumed.uiState.value.completed }
        val logs = runBlocking { app.container.repository.allLogs().filter { it.countsAsAnswer() } }
        assertEquals(2, logs.size)
        assertEquals(cp.answeredAt, logs.first { it.questionType == "SPELLING" }.timestamp)
        assertTrue(runBlocking { app.container.database.dualTestSessionDao().getAll().isEmpty() })
    }

    @Test fun statisticsScreenSeparatesPracticeAccuracyFromIndependentRecall() {
        val report = StatsReport(listeningMastery = MasteryMetric(3, 4), meaningMastery = MasteryMetric(1, 2),
            spellingStability = MasteryMetric(4, 5), independentRecall = MemorySkill.entries.associateWith { MasteryMetric(0, 0) })
        compose.setContent { AlanVocabTheme { StatsReportContent(report, StatsSyncUiState()) } }
        compose.onNodeWithTag("stats-report-list").performScrollToIndex(3)
        compose.onNodeWithText("词义练习正确率").assertIsDisplayed()
        compose.onNodeWithText("词义跨日首次回忆").assertIsDisplayed()
        compose.onNodeWithText("准确 1/2").assertIsDisplayed()
        val screenshot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(app.getExternalFilesDir(null), "memory-statistics-metrics.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
