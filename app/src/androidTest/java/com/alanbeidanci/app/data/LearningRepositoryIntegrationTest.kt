package com.alanbeidanci.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class LearningRepositoryIntegrationTest {
    private lateinit var database: AlanDatabase
    private lateinit var repository: LearningRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AlanDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = LearningRepository(database, context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun seedStudyAndRestoreRoundTrip() = runBlocking {
        repository.ensureSeeded()

        val books = repository.observeBookProgress().first()
        assertEquals(2, books.size)
        assertEquals(40, books.sumOf { it.total })

        val session = repository.getSession(
            bookIds = setOf("demo-basics"),
            mode = StudyMode.LEARN,
            limit = 20,
            shuffle = false,
        )
        assertEquals(20, session.size)
        assertEquals("learn", session.first().word)

        val mixedSession = repository.getWords(
            bookIds = setOf("demo-basics", "demo-daily"),
        )
        assertTrue(mixedSession.any { it.bookId == "demo-basics" })
        assertTrue(mixedSession.any { it.bookId == "demo-daily" })

        val word = session.first()
        repository.logAnswer(word.id, true, QuestionType.MEANING)
        repository.logAnswer(word.id, true, QuestionType.LISTENING)
        repository.logAnswer(word.id, true, QuestionType.SPELLING)
        repository.completeWord(word.id, true)

        val saved = requireNotNull(repository.progressFor(word.id))
        assertEquals(1, saved.attempts)
        assertEquals(1, saved.correctCount)
        assertTrue(saved.dueAt > saved.lastReviewedAt)

        repository.restore(listOf(saved.copy(isFavorite = true)), repository.allLogs())
        assertTrue(requireNotNull(repository.progressFor(word.id)).isFavorite)
        assertEquals(3, repository.allLogs().size)
    }

    @Test
    fun remoteDailyReportIncludesCrossDeviceWordAndMethodStats() = runBlocking {
        val today = LocalDate.now().toString()
        database.serverStatsDao().upsert(
            ServerStatsEntity(
                key = "remote_stats",
                payloadJson = """
                    {
                      "totalDurationMs": 480000,
                      "totalSessions": 1,
                      "totalAnswers": 4,
                      "totalCorrect": 3,
                      "days": [{
                        "date": "$today",
                        "durationMs": 480000,
                        "answers": 4,
                        "correct": 3,
                        "sessions": 1,
                        "words": 2,
                        "newWords": 1,
                        "reviewWords": 1,
                        "masteredWords": 1,
                        "meaningAnswers": 1,
                        "listeningAnswers": 1,
                        "spellingAnswers": 2
                      }]
                    }
                """.trimIndent(),
            ),
        )

        val report = repository.observeStats().first { it.todayAnswers == 4 }
        assertEquals(480000L, report.todayDurationMs)
        assertEquals(2, report.todayWords)
        assertEquals(75, report.todayAccuracy)
        val todayRow = report.days.last()
        assertEquals(2, todayRow.words)
        assertEquals(1, todayRow.newWords)
        assertEquals(1, todayRow.reviewWords)
        assertEquals(1, todayRow.masteredWords)
        assertEquals("拼写2 · 听音1 · 词义1", todayRow.methodDist)
    }
}
