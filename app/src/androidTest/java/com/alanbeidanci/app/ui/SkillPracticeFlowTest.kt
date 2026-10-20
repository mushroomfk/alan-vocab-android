package com.alanbeidanci.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alanbeidanci.app.AlanVocabApplication
import com.alanbeidanci.app.data.*
import com.alanbeidanci.app.ui.screens.TypingStudyScreen
import com.alanbeidanci.app.ui.theme.AlanVocabTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SkillPracticeFlowTest {
    @get:Rule val compose = createComposeRule()
    private val app: AlanVocabApplication get() = ApplicationProvider.getApplicationContext()
    private val wordId = 9_000_001L

    @Before fun prepareIsolatedFixture() = runBlocking {
        app.container.repository.ensureSeeded()
        app.container.preferences.resetForTesting()
        app.container.preferences.completeOnboarding()
        app.container.preferences.selectOnlyBook("skill-flow-test")
        app.container.database.wordBookDao().insert(WordBookEntity("skill-flow-test", "分项测试", "", "", "", 0, 0))
        app.container.database.wordDao().insert(WordEntity(wordId, "skill-flow-test", "cancel", "", "", "取消", "v.", "", "", 1, 1))
        val now = System.currentTimeMillis()
        val good = SkillState(30.0, 3.0, now - 10 * SkillMemory.DAY, MainlineScheduler.dueAfterDays(now, 15), 3, 0, "GOOD")
        val weak = SkillState(3.0, 8.5, now - 10 * SkillMemory.DAY, now - SkillMemory.DAY, 0, 3, "LAPSE")
        app.container.repository.restore(listOf(WordProgressEntity(wordId, attempts = 8,
            category = "mastered", isMastered = true, dueAt = now - SkillMemory.DAY,
            mainlineClassifiedDate = MainlineScheduler.dateKey(now - 2 * SkillMemory.DAY),
            skillMemory = SkillMemory.encode(mapOf(MemorySkill.MEANING to good, MemorySkill.SPELLING to good, MemorySkill.LISTENING to weak)))), emptyList())
    }

    @Test fun actualViewModelOpensWeakListeningAndResumesItsRemainingCount() {
        lateinit var vm: TypingViewModel
        compose.runOnIdle { vm = TypingViewModel(app.container, TypingPurpose.MAINLINE_CONSOLIDATE) }
        compose.waitUntil(20000) { !vm.uiState.value.loading }
        val settings = runBlocking { app.container.preferences.snapshot().copy(autoSpeak = false) }
        compose.setContent {
            AlanVocabTheme {
                TypingStudyScreen(app.container, TypingPurpose.MAINLINE_CONSOLIDATE, settings, {}, {}, viewModel = vm)
            }
        }
        compose.runOnIdle { vm.start() }
        compose.waitUntil(5000) { vm.uiState.value.started && !vm.uiState.value.locked && vm.uiState.value.practiceForm == PracticeForm.LISTEN }
        compose.onNodeWithText("听写 0/3").assertIsDisplayed()
        val shot = compose.onRoot().captureToImage().asAndroidBitmap()
        File(app.getExternalFilesDir(null), "skill-listening-plan.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        compose.runOnIdle { vm.updateInput("cancel") }
        compose.waitUntil(5000) { vm.uiState.value.holdResult }
        // The only word is deferred rather than immediately repeated; persisted plan has one credit.
        compose.waitUntil(10000) { runBlocking { app.container.repository.practiceSnapshots(setOf(wordId))[wordId]?.plan?.contains("LISTENING,3,1,1") == true } }
        lateinit var resumed: TypingViewModel
        compose.runOnIdle { resumed = TypingViewModel(app.container, TypingPurpose.MAINLINE_CONSOLIDATE) }
        compose.waitUntil(10000) { !resumed.uiState.value.loading }
        compose.runOnIdle { resumed.start() }
        compose.waitUntil(5000) { resumed.uiState.value.started }
        assertEquals(PracticeForm.LISTEN, resumed.uiState.value.practiceForm)
        assertEquals("听写 1/3", resumed.uiState.value.skillProgress)
        assertEquals(1, resumed.uiState.value.originalWordCount)
    }
}
