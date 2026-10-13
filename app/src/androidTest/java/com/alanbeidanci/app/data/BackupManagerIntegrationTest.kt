package com.alanbeidanci.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alanbeidanci.app.AlanVocabApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupManagerIntegrationTest {
    @Test
    fun backupRestoresProgressLogsAndLearningSettings() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AlanVocabApplication>()
        val container = application.container
        container.repository.ensureSeeded()
        container.repository.restore(emptyList(), emptyList())
        container.preferences.resetForTesting()

        container.preferences.setCurrentBook("demo-basics")
        container.preferences.toggleBookSelection("demo-daily")
        container.preferences.setDailyGoal(50)
        container.preferences.setAccent("uk")
        container.preferences.setShuffle(true)

        val word = container.repository.getWords(setOf("demo-basics")).first()
        container.repository.logAnswer(word.id, false, QuestionType.SPELLING)
        container.repository.completeWord(word.id, false)
        container.repository.toggleFavorite(word.id)
        val backup = container.backupManager.exportJson()

        container.preferences.setCurrentBook("demo-basics")
        container.preferences.setDailyGoal(10)
        container.preferences.setAccent("us")
        container.preferences.setShuffle(false)
        container.repository.restore(emptyList(), emptyList())

        container.backupManager.restoreJson(backup)

        val restoredSettings = container.preferences.snapshot()
        assertEquals("demo-basics", restoredSettings.currentBookId)
        assertEquals(setOf("demo-basics", "demo-daily"), restoredSettings.selectedBookIds)
        assertEquals(50, restoredSettings.dailyGoal)
        assertEquals("uk", restoredSettings.accent)
        assertTrue(restoredSettings.shuffle)

        val restoredProgress = requireNotNull(container.repository.progressFor(word.id))
        assertEquals(1, restoredProgress.attempts)
        assertEquals(1, restoredProgress.wrongCount)
        assertTrue(restoredProgress.isFavorite)
        assertEquals(1, container.repository.allLogs().size)
    }
}
