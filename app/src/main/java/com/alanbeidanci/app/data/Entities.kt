package com.alanbeidanci.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "word_books")
data class WordBookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val subtitle: String,
    val category: String,
    val description: String,
    val colorHex: Long,
    val orderIndex: Int,
)

@Entity(
    tableName = "words",
    foreignKeys = [
        ForeignKey(
            entity = WordBookEntity::class,
            parentColumns = ["id"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId")],
)
data class WordEntity(
    @PrimaryKey val id: Long,
    val bookId: String,
    val word: String,
    val phoneticUs: String,
    val phoneticUk: String,
    val translation: String,
    val partOfSpeech: String,
    val example: String,
    val exampleTranslation: String,
    val chapter: Int,
    val orderIndex: Int,
    val unitId: String = "",
    val unitName: String = "",
    val folderId: String = "",
    val folderName: String = "",
)

@Entity(
    tableName = "word_progress",
    foreignKeys = [
        ForeignKey(
            entity = WordEntity::class,
            parentColumns = ["id"],
            childColumns = ["wordId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("dueAt"), Index("isMastered")],
)
data class WordProgressEntity(
    @PrimaryKey val wordId: Long,
    val familiarity: Int = 0,
    val streak: Int = 0,
    val wrongCount: Int = 0,
    val attempts: Int = 0,
    val correctCount: Int = 0,
    val lastReviewedAt: Long = 0,
    val dueAt: Long = 0,
    val isMastered: Boolean = false,
    val isFavorite: Boolean = false,
    val updatedAt: Long = 0,
    val category: String = "learning",
    val level: Int = 0,
    val passStreak: Int = 0,
    val lastListenPass: Boolean? = null,
    val lastChinesePass: Boolean? = null,
    val lastSpotCheckAt: Long = 0,
    val masteryStreak: Int = 0,
    val fixedAt: Long = 0,
    val mainlineDualPending: Boolean = false,
    val mainlineClassifiedDate: String = "",
    val mainlineRemedyReviewDate: String = "",
    val mainlineRemedyTestDate: String = "",
    val mainlineLearnedDate: String = "",
    val mainlineDualDeferredDate: String = "",
    val mainlineConsolidationDate: String = "",
    val stabilityDays: Double = 0.0,
    val difficulty: Double = 0.0,
    val practiceHits: Int = 0,
    val practiceMisses: Int = 0,
    val practiceHinted: Boolean = false,
    val practiceForm: String = "",
    val firstResult: String = "",
    val abilityMeaning: Int = 0,
    val abilityListening: Int = 0,
    val abilitySpelling: Int = 0,
    val abilityContext: Int = 0,
)

@Entity(
    tableName = "review_logs",
    indices = [Index("wordId"), Index("timestamp")],
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val wordId: Long,
    val timestamp: Long,
    val correct: Boolean,
    val questionType: String,
    val isDual: Boolean = false,
    val isMainline: Boolean = false,
    val isTest: Boolean = false,
    val firstAttempt: Boolean = false,
    val hinted: Boolean = false,
    val practiceForm: String = "",
    val eventKind: String = "answer",
    val scheduleGrade: String = "",
    // 仅新版本保存完整排期快照，旧日志不猜测、不参与个人拟合。
    val memoryAlgorithm: String = "",
    val memoryBeforeStability: Double = 0.0,
    val memoryBeforeDifficulty: Double = 0.0,
    val memoryPreviousReviewAt: Long = 0,
    val memoryIndependent: Boolean = false,
    val memoryModelRevision: Int = 0,
) {
    fun countsAsAnswer(): Boolean = eventKind.isEmpty() || eventKind == "answer"
}

@Entity(tableName = "study_sessions")
data class StudySessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: String,
    val wordCount: Int,
    val completed: Int,
    val durationMs: Long,
    val typedChars: Int = 0,
    val correctChars: Int = 0,
    val startedAt: Long,
    val finishedAt: Long,
    // 同步：sessionId 幂等键；synced=1 表示已上传到服务端
    val sessionId: String = "",
    val synced: Boolean = false,
)

/** 服务端全量快照下发的跨设备学习统计聚合（原样保存，统计页合并展示）。 */
@Entity(tableName = "server_stats")
data class ServerStatsEntity(
    @PrimaryKey val key: String,
    val payloadJson: String,
)

@Entity(tableName = "pending_sync_events")
data class PendingSyncEventEntity(
    @PrimaryKey val eventId: String,
    val deviceId: String,
    val wordId: Long,
    val eventType: String,
    val correct: Boolean? = null,
    val favorite: Boolean? = null,
    val clientTime: String,
    val clientSeq: Long,
    val isDual: Boolean = false,
    val isMainline: Boolean = false,
    val isTest: Boolean = false,
    val spellingQuality: String? = null,
    val progressStateJson: String? = null,
)

data class WordWithProgress(
    val word: WordEntity,
    val progress: WordProgressEntity?,
)

enum class StudyMode { LEARN, REVIEW }

enum class QuestionType { CONFIDENCE, MEANING, LISTENING, SPELLING }
