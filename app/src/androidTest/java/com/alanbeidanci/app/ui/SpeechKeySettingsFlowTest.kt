package com.alanbeidanci.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alanbeidanci.app.AlanVocabApplication
import com.alanbeidanci.app.audio.SpeechCredentials
import com.alanbeidanci.app.ui.screens.SettingsScreen
import com.alanbeidanci.app.ui.theme.AlanVocabTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class SpeechKeySettingsFlowTest {
    @get:Rule val compose = createComposeRule()
    private val app: AlanVocabApplication get() = ApplicationProvider.getApplicationContext()
    @Before fun reset() { SpeechCredentials.clear(app) }
    @After fun cleanup() { SpeechCredentials.clear(app) }

    @Test fun offlinePreviewStartsPlaybackWithoutChangingLearningRecords() {
        runBlocking { app.container.repository.ensureSeeded() }
        val before = runBlocking { app.container.repository.backupSnapshot() }
        compose.setContent { AlanVocabTheme { SettingsScreen(app.container, {}, {}, {}, {}) } }
        compose.onNodeWithTag("speech-test-offline").performScrollTo().performClick()
        compose.waitUntil(15000) {
            compose.onNodeWithTag("speech-playback-status").fetchSemanticsNode().config[SemanticsProperties.Text]
                .any { it.text.contains("离线英文") && it.text.contains("已开始播放") }
        }
        compose.onNodeWithTag("speech-playback-status").performScrollTo().assertTextContains("离线英文", substring = true)
        assertEquals(before, runBlocking { app.container.repository.backupSnapshot() })
    }

    @Test fun userCanSaveReplaceAndRemoveKeyWithoutRevealingTheSavedValue() {
        compose.setContent { AlanVocabTheme { SettingsScreen(app.container, {}, {}, {}, {}) } }
        compose.onNodeWithText("设置在线发音 Key").performScrollTo().performClick()
        compose.onNodeWithTag("speech-api-key").performTextInput("test-only-settings-key")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(10000) { SpeechCredentials.read(app) == "test-only-settings-key" }
        compose.onNodeWithText("更换在线发音 Key").performScrollTo().performClick()
        compose.onNodeWithTag("speech-api-key").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("删除在线发音 Key").performScrollTo().performClick()
        compose.waitUntil(10000) { !SpeechCredentials.isConfigured(app) }
        compose.onNodeWithText("设置在线发音 Key").assertIsDisplayed()
    }
}
