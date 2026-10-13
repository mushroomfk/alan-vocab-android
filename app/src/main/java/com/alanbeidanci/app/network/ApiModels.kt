package com.alanbeidanci.app.network

data class UserSession(
    val userId: Long,
    val username: String,
    val accessToken: String,
    val accessTokenExpiresAt: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: String,
    val mainlineEnabled: Boolean = true,
)

data class WordLookupItem(
    val id: Long,
    val word: String,
    val phoneticUs: String,
    val phoneticUk: String,
    val translation: String,
    val partOfSpeech: String,
    val example: String,
    val exampleTranslation: String,
)

data class WordLookupResponse(
    val resolvedQuery: String,
    val language: String,
    val source: String,
    val results: List<WordLookupItem>,
)

data class ServerProgressRow(
    val wordId: Long,
    val familiarity: Int,
    val streak: Int,
    val wrongCount: Int,
    val attempts: Int,
    val correctCount: Int,
    val lastReviewedAt: Long,
    val dueAt: Long,
    val isMastered: Boolean,
    val isFavorite: Boolean,
    val updatedAt: Long,
    val category: String,
    val level: Int,
    val passStreak: Int,
    val lastListenPass: Boolean?,
    val lastChinesePass: Boolean?,
    val lastSpotCheckAt: Long,
    val masteryStreak: Int,
    val fixedAt: Long,
    val mainlineDualPending: Boolean,
    val mainlineClassifiedDate: String,
    val mainlineRemedyReviewDate: String,
    val mainlineRemedyTestDate: String,
    val mainlineLearnedDate: String,
    val mainlineDualDeferredDate: String,
)

data class ServerSetting(
    val key: String,
    val value: Any?,
    val clientUpdatedAt: Long,
    val deviceId: String,
    val serverVersion: Long,
)

data class ServerSettingUpdate(
    val key: String,
    val value: Any?,
    val clientUpdatedAtMs: Long,
)

data class ServerSessionUpload(
    val sessionId: String,
    val deviceId: String,
    val mode: String,
    val wordCount: Int,
    val completed: Int,
    val durationMs: Long,
    val typedChars: Int,
    val correctChars: Int,
    val startedAt: String,
    val finishedAt: String,
    val startedDate: String,
)

data class FullSnapshotPayload(
    val progress: List<ServerProgressRow>,
    val settings: List<ServerSetting>,
    // 服务端跨设备学习统计聚合原始 JSON；旧服务端可能为 null。
    val statsJson: String? = null,
    val mainlineEnabled: Boolean = true,
)

data class MainlineErrorTypeSummary(
    val code: String,
    val label: String,
    val count: Int,
    val examples: List<String>,
    val suggestion: String,
)

data class MainlineErrorWordSummary(
    val wordId: Long,
    val word: String,
    val latestInput: String,
    val errorCount: Int,
    val errorTypes: List<String>,
    val stages: List<String>,
)

data class DailyMainlineErrorReport(
    val date: String,
    val englishAnswers: Int,
    val incorrectAnswers: Int,
    val uniqueErrorWords: Int,
    val accuracyPercent: Int?,
    val errorTypes: List<MainlineErrorTypeSummary>,
    val words: List<MainlineErrorWordSummary>,
)

data class RepeatRecording(
    val id: String,
    val wordId: Long?,
    val word: String,
    val translation: String,
    val partOfSpeech: String,
    val mode: String,
    val accent: String,
    val recordedDate: String,
    val recordedAt: String,
    val durationMs: Int,
    val assessmentStatus: String,
    val assessmentScore: Int?,
    val assessmentStars: Int?,
    val assessmentCode: String,
    val contentType: String,
)

data class RepeatRecordingAudio(
    val bytes: ByteArray,
    val contentType: String,
)

class AlanApiException(
    val status: Int,
    val code: String,
    override val message: String,
) : Exception(message)
