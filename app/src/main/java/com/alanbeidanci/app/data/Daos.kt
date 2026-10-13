package com.alanbeidanci.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WordBookDao {
    @Query("SELECT * FROM word_books ORDER BY orderIndex")
    fun observeAll(): Flow<List<WordBookEntity>>

    @Query("SELECT * FROM word_books ORDER BY orderIndex")
    suspend fun getAll(): List<WordBookEntity>

    @Query("SELECT * FROM word_books WHERE id = :id")
    suspend fun getById(id: String): WordBookEntity?

    @Query("SELECT COUNT(*) FROM word_books")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<WordBookEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WordBookEntity)

    @Update
    suspend fun update(item: WordBookEntity)

    @Query("DELETE FROM word_books WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM word_books")
    suspend fun clear()
}

data class BookStat(val bookId: String = "", val total: Int = 0)

data class UnitStat(
    val bookId: String = "",
    val unitId: String = "",
    val unitName: String = "",
    val folderId: String = "",
    val folderName: String = "",
    val total: Int = 0,
)

@Dao
interface WordDao {
    @Query("SELECT * FROM words ORDER BY bookId, chapter, orderIndex")
    fun observeAllWords(): Flow<List<WordEntity>>

    @Query("SELECT * FROM words WHERE bookId IN (:bookIds) ORDER BY bookId, chapter, orderIndex")
    fun observeByBooks(bookIds: List<String>): Flow<List<WordEntity>>

    @Query("SELECT bookId, COUNT(*) AS total FROM words GROUP BY bookId")
    suspend fun countByBookGrouped(): List<BookStat>

    @Query("SELECT w.bookId AS bookId, COUNT(*) AS total FROM words w INNER JOIN word_progress p ON p.wordId = w.id WHERE p.attempts > 0 GROUP BY w.bookId")
    suspend fun learnedByBook(): List<BookStat>

    @Query("SELECT w.bookId AS bookId, COUNT(*) AS total FROM words w INNER JOIN word_progress p ON p.wordId = w.id WHERE p.isMastered = 1 GROUP BY w.bookId")
    suspend fun masteredByBook(): List<BookStat>

    @Query("SELECT bookId, unitId, unitName, folderId, folderName, COUNT(*) AS total FROM words GROUP BY bookId, unitId")
    suspend fun unitStats(): List<UnitStat>

    @Query("SELECT w.* FROM words w INNER JOIN word_progress p ON p.wordId = w.id")
    suspend fun getWordsWithProgress(): List<WordEntity>

    @Query("SELECT * FROM words ORDER BY bookId, chapter, orderIndex")
    suspend fun getAll(): List<WordEntity>

    @Query("SELECT * FROM words WHERE bookId = :bookId ORDER BY chapter, orderIndex")
    fun observeByBook(bookId: String): Flow<List<WordEntity>>

    @Query("SELECT * FROM words WHERE bookId = :bookId ORDER BY chapter, orderIndex")
    suspend fun getByBook(bookId: String): List<WordEntity>

    @Query("SELECT MAX(id) FROM words")
    suspend fun maxId(): Long?

    @Query("SELECT id FROM words")
    suspend fun getIds(): List<Long>

    @Query("SELECT * FROM words WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<WordEntity>

    @Query("SELECT * FROM words WHERE id = :id")
    suspend fun getById(id: Long): WordEntity?

    @Query(
        """
        SELECT * FROM words
        WHERE word = :spelling COLLATE NOCASE
          AND (phoneticUs != '' OR phoneticUk != '')
        LIMIT 1
        """,
    )
    suspend fun findPhonetic(spelling: String): WordEntity?

    @Query(
        """
        SELECT * FROM words
        WHERE word LIKE '%' || :like || '%' ESCAPE '!'
           OR translation LIKE '%' || :like || '%' ESCAPE '!'
        ORDER BY
            CASE
                WHEN word = :query COLLATE NOCASE THEN 0
                WHEN translation = :query THEN 1
                WHEN word LIKE :like || '%' ESCAPE '!' THEN 2
                WHEN translation LIKE :like || '%' ESCAPE '!' THEN 3
                ELSE 4
            END,
            length(word), word COLLATE NOCASE, id
        LIMIT :limit
        """,
    )
    suspend fun search(query: String, like: String, limit: Int): List<WordEntity>

    @Query("SELECT COUNT(*) FROM words WHERE bookId = :bookId")
    suspend fun countByBook(bookId: String): Int

    @Query("SELECT id FROM words WHERE bookId = :bookId AND word = :spelling COLLATE NOCASE LIMIT 1")
    suspend fun findIdByBookAndSpelling(bookId: String, spelling: String): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<WordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WordEntity)

    @Update
    suspend fun updateAll(items: List<WordEntity>)

    @Query("DELETE FROM words WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM words WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM words WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)

    @Query("DELETE FROM words")
    suspend fun clear()
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM word_progress")
    fun observeAll(): Flow<List<WordProgressEntity>>

    @Query("SELECT * FROM word_progress")
    suspend fun getAll(): List<WordProgressEntity>

    @Query("SELECT * FROM word_progress WHERE wordId = :wordId")
    suspend fun getByWordId(wordId: Long): WordProgressEntity?

    @Query("SELECT * FROM word_progress WHERE dueAt <= :now AND attempts > 0 AND isMastered = 0 ORDER BY dueAt")
    suspend fun getDue(now: Long): List<WordProgressEntity>

    @Upsert
    suspend fun upsert(item: WordProgressEntity)

    @Upsert
    suspend fun upsertAll(items: List<WordProgressEntity>)

    @Query("DELETE FROM word_progress")
    suspend fun clear()
}

@Dao
interface ReviewLogDao {
    @Query("SELECT * FROM review_logs WHERE eventKind = 'schedule' ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun trainingLogs(limit: Int): List<ReviewLogEntity>

    @Query("SELECT * FROM review_logs ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<ReviewLogEntity>>

    @Query("SELECT * FROM review_logs ORDER BY timestamp DESC")
    suspend fun getAll(): List<ReviewLogEntity>

    @Insert
    suspend fun insert(item: ReviewLogEntity)

    @Insert
    suspend fun insertAll(items: List<ReviewLogEntity>)

    @Query("DELETE FROM review_logs")
    suspend fun clear()
}

@Dao
interface StudySessionDao {
    @Query("SELECT * FROM study_sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<StudySessionEntity>>

    @Query("SELECT * FROM study_sessions ORDER BY startedAt DESC")
    suspend fun getAll(): List<StudySessionEntity>

    @Query("SELECT * FROM study_sessions WHERE synced = 0 ORDER BY startedAt LIMIT :limit")
    suspend fun loadUnsynced(limit: Int): List<StudySessionEntity>

    @Query("UPDATE study_sessions SET synced = 1 WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("UPDATE study_sessions SET sessionId = :sessionId WHERE id = :id")
    suspend fun updateSessionId(id: Long, sessionId: String)

    @Insert
    suspend fun insert(item: StudySessionEntity): Long

    @Query("DELETE FROM study_sessions")
    suspend fun clear()
}

@Dao
interface ServerStatsDao {
    @Query("SELECT * FROM server_stats ORDER BY key")
    fun observeAll(): Flow<List<ServerStatsEntity>>

    @Query("SELECT * FROM server_stats WHERE key = :key")
    suspend fun get(key: String): ServerStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ServerStatsEntity)

    @Query("DELETE FROM server_stats")
    suspend fun clear()
}

@Dao
interface PendingSyncDao {
    @Query("SELECT * FROM pending_sync_events ORDER BY clientSeq")
    fun observeAll(): Flow<List<PendingSyncEventEntity>>

    @Query("SELECT * FROM pending_sync_events ORDER BY clientSeq LIMIT :limit")
    suspend fun loadBatch(limit: Int): List<PendingSyncEventEntity>

    @Query("SELECT COUNT(*) FROM pending_sync_events")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PendingSyncEventEntity)

    @Query("DELETE FROM pending_sync_events WHERE eventId IN (:eventIds)")
    suspend fun deleteByIds(eventIds: List<String>)

    @Query("DELETE FROM pending_sync_events")
    suspend fun clear()
}
