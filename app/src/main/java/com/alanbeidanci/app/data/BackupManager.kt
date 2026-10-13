package com.alanbeidanci.app.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class BackupManager(
    private val repository: LearningRepository,
    private val preferences: PreferencesStore,
) {
    suspend fun exportJson(): String {
        val settings = preferences.snapshot()
        val snapshot = repository.backupSnapshot()
        val identities = repository.wordIdentities(snapshot.progress.map { it.wordId })
        val progressArray = JSONArray()
        snapshot.progress.forEach { item ->
            val word = identities[item.wordId]
            progressArray.put(progressJson(item, word?.bookId.orEmpty(), word?.word.orEmpty()))
        }
        val logsArray = JSONArray()
        snapshot.logs.forEach { item ->
            logsArray.put(
                JSONObject()
                    .put("wordId", item.wordId)
                    .put("timestamp", item.timestamp)
                    .put("correct", item.correct)
                    .put("questionType", item.questionType)
                    .put("isDual", item.isDual)
                    .put("isMainline", item.isMainline)
                    .put("isTest", item.isTest)
                    .put("firstAttempt", item.firstAttempt)
                    .put("hinted", item.hinted)
                    .put("practiceForm", item.practiceForm)
                    .put("eventKind", item.eventKind)
                    .put("scheduleGrade", item.scheduleGrade)
                    .put("memoryAlgorithm", item.memoryAlgorithm)
                    .put("memoryBeforeStability", item.memoryBeforeStability)
                    .put("memoryBeforeDifficulty", item.memoryBeforeDifficulty)
                    .put("memoryPreviousReviewAt", item.memoryPreviousReviewAt)
                    .put("memoryIndependent", item.memoryIndependent)
                    .put("memoryModelRevision", item.memoryModelRevision),
            )
        }
        val sessionsArray = JSONArray()
        snapshot.sessions.forEach { item ->
            sessionsArray.put(
                JSONObject()
                    .put("mode", item.mode)
                    .put("wordCount", item.wordCount)
                    .put("completed", item.completed)
                    .put("durationMs", item.durationMs)
                    .put("typedChars", item.typedChars)
                    .put("correctChars", item.correctChars)
                    .put("startedAt", item.startedAt)
                    .put("finishedAt", item.finishedAt)
                    .put("sessionId", item.sessionId),
            )
        }
        return JSONObject()
            .put("app", "Alan背单词")
            .put("formatVersion", 3)
            .put("retentionProfile", RetentionProfileJson.encode(snapshot.profile))
            .put("dictionaryVersion", repository.dictionaryVersion())
            .put("exportedAt", System.currentTimeMillis())
            .put(
                "settings",
                JSONObject()
                    .put("currentBookId", settings.currentBookId)
                    .put("selectedBookIds", JSONArray(settings.selectedBookIds.sorted()))
                    .put("selectedUnitKeys", JSONArray(settings.selectedUnitKeys.sorted()))
                    .put("dailyGoal", settings.dailyGoal)
                    .put("dailyMinutes", settings.dailyMinutes)
                    .put("autoSpeak", settings.autoSpeak)
                    .put("shuffle", settings.shuffle)
                    .put("darkMode", settings.darkMode)
                    .put("accent", settings.accent)
                    .put("studySpeechRate", settings.studySpeechRate)
                    .put("reminderEnabled", settings.reminderEnabled)
                    .put("reminderHour", settings.reminderHour),
            )
            .put("progress", progressArray)
            .put("logs", logsArray)
            .put("sessions", sessionsArray)
            .toString(2)
    }

    suspend fun restoreJson(content: String) {
        val plan = parse(content)
        val previous = repository.backupSnapshot()
        val previousSettings = preferences.snapshot()
        try {
            repository.restore(plan.progress, plan.logs, plan.sessions, plan.profile)
            plan.settings?.let { preferences.restoreLearningSettings(it) }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                runCatching {
                    repository.restore(previous.progress, previous.logs, previous.sessions, previous.profile)
                    preferences.restoreLearningSettings(previousSettings)
                }.onFailure { error.addSuppressed(it) }
            }
            throw error
        }
    }

    private suspend fun parse(content: String): RestorePlan {
        val root = JSONObject(content)
        val version = root.optInt("formatVersion")
        require(version in 1..3) { "不支持的备份版本" }
        val validWordIds = repository.allWordIds()
        val validBookIds = repository.allBookIds()
        val progressArray = root.getJSONArray("progress")
        val progressItems = buildList {
            for (index in 0 until progressArray.length()) {
                val item = progressArray.getJSONObject(index)
                val wordId = item.optLong("wordId")
                val resolved = when {
                    wordId in validWordIds -> wordId
                    else -> repository.findWordId(item.optString("bookId"), item.optString("spelling"))
                } ?: continue
                add(progressFrom(item, resolved))
            }
        }
        val logArray = root.getJSONArray("logs")
        val logItems = buildList {
            for (index in 0 until logArray.length()) {
                val item = logArray.getJSONObject(index)
                val wordId = item.getLong("wordId")
                if (wordId !in validWordIds) continue
                add(
                    ReviewLogEntity(
                        wordId = wordId,
                        timestamp = item.getLong("timestamp"),
                        correct = item.getBoolean("correct"),
                        questionType = item.getString("questionType"),
                        isDual = item.optBoolean("isDual"),
                        isMainline = item.optBoolean("isMainline"),
                        isTest = item.optBoolean("isTest"),
                        firstAttempt = item.optBoolean("firstAttempt"),
                        hinted = item.optBoolean("hinted"),
                        practiceForm = item.optString("practiceForm"),
                        eventKind = item.optString("eventKind", "answer"),
                        scheduleGrade = item.optString("scheduleGrade"),
                        memoryAlgorithm = item.optString("memoryAlgorithm"),
                        memoryBeforeStability = item.optDouble("memoryBeforeStability", 0.0).finiteOrZero(),
                        memoryBeforeDifficulty = item.optDouble("memoryBeforeDifficulty", 0.0).finiteOrZero(),
                        memoryPreviousReviewAt = item.optLong("memoryPreviousReviewAt"),
                        memoryIndependent = item.optBoolean("memoryIndependent"),
                        memoryModelRevision = item.optInt("memoryModelRevision"),
                    ),
                )
            }
        }
        // 没有 sessions 的旧备份按空列表整体替换，避免和这份进度混进别的会话。
        val sessions = root.optJSONArray("sessions")?.let { array ->
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        StudySessionEntity(
                            mode = item.optString("mode", "LEARN"),
                            wordCount = item.optInt("wordCount"),
                            completed = item.optInt("completed"),
                            durationMs = item.optLong("durationMs"),
                            typedChars = item.optInt("typedChars"),
                            correctChars = item.optInt("correctChars"),
                            startedAt = item.optLong("startedAt"),
                            finishedAt = item.optLong("finishedAt"),
                            sessionId = item.optString("sessionId"),
                        ),
                    )
                }
            }
        } ?: emptyList()
        val settings = root.optJSONObject("settings")?.let { item ->
            val current = preferences.snapshot()
            val bookId = item.optString("currentBookId", current.currentBookId)
            val selectedBookIds = if (item.has("selectedBookIds")) {
                item.optJSONArray("selectedBookIds")?.let { array ->
                    buildSet {
                        for (index in 0 until array.length()) {
                            array.optString(index).takeIf { it.isNotBlank() && it in validBookIds }?.let(::add)
                        }
                    }
                } ?: emptySet()
            } else {
                setOf(bookId.takeIf { it in validBookIds } ?: current.currentBookId)
            }
            val selectedUnitKeys = item.optJSONArray("selectedUnitKeys")?.let { array ->
                buildSet {
                    for (index in 0 until array.length()) {
                        array.optString(index).takeIf { it.isNotBlank() && it.contains("::") }?.let(::add)
                    }
                }
            }.orEmpty()
            val accent = item.optString("accent", current.accent)
            current.copy(
                currentBookId = bookId.takeIf { it.isNotBlank() && (selectedBookIds.isEmpty() || it in validBookIds) }
                    ?: selectedBookIds.minOrNull()
                    ?: current.currentBookId,
                selectedBookIds = selectedBookIds,
                selectedUnitKeys = selectedUnitKeys,
                dailyGoal = item.optInt("dailyGoal", current.dailyGoal).coerceIn(1, 100),
                dailyMinutes = item.optInt("dailyMinutes", current.dailyMinutes).coerceIn(5, 180),
                autoSpeak = item.optBoolean("autoSpeak", current.autoSpeak),
                shuffle = item.optBoolean("shuffle", current.shuffle),
                darkMode = item.optBoolean("darkMode", current.darkMode),
                accent = accent.takeIf { it == "us" || it == "uk" } ?: current.accent,
                studySpeechRate = item.optDouble("studySpeechRate", current.studySpeechRate.toDouble()).toFloat().coerceIn(0.5f, 2f),
                reminderEnabled = item.optBoolean("reminderEnabled", current.reminderEnabled),
                reminderHour = item.optInt("reminderHour", current.reminderHour).coerceIn(0, 23),
            )
        }
        val profile = if (version == 3) RetentionProfileJson.decode(root.getJSONObject("retentionProfile")) else RetentionProfile()
        return RestorePlan(progressItems, logItems, sessions, settings, profile)
    }

    private fun progressJson(item: WordProgressEntity, bookId: String, spelling: String): JSONObject =
        JSONObject()
            .put("wordId", item.wordId)
            .put("bookId", bookId)
            .put("spelling", spelling)
            .put("familiarity", item.familiarity)
            .put("streak", item.streak)
            .put("wrongCount", item.wrongCount)
            .put("attempts", item.attempts)
            .put("correctCount", item.correctCount)
            .put("lastReviewedAt", item.lastReviewedAt)
            .put("dueAt", item.dueAt)
            .put("isMastered", item.isMastered)
            .put("isFavorite", item.isFavorite)
            .put("updatedAt", item.updatedAt)
            .put("category", item.category)
            .put("level", item.level)
            .put("passStreak", item.passStreak)
            .put("lastListenPass", item.lastListenPass)
            .put("lastChinesePass", item.lastChinesePass)
            .put("lastSpotCheckAt", item.lastSpotCheckAt)
            .put("masteryStreak", item.masteryStreak)
            .put("fixedAt", item.fixedAt)
            .put("mainlineDualPending", item.mainlineDualPending)
            .put("mainlineClassifiedDate", item.mainlineClassifiedDate)
            .put("mainlineRemedyReviewDate", item.mainlineRemedyReviewDate)
            .put("mainlineRemedyTestDate", item.mainlineRemedyTestDate)
            .put("mainlineLearnedDate", item.mainlineLearnedDate)
            .put("mainlineDualDeferredDate", item.mainlineDualDeferredDate)
            .put("mainlineConsolidationDate", item.mainlineConsolidationDate)
            .put("stabilityDays", item.stabilityDays)
            .put("difficulty", item.difficulty)
            .put("practiceHits", item.practiceHits)
            .put("practiceMisses", item.practiceMisses)
            .put("practiceHinted", item.practiceHinted)
            .put("practiceForm", item.practiceForm)
            .put("firstResult", item.firstResult)
            .put("abilityMeaning", item.abilityMeaning)
            .put("abilityListening", item.abilityListening)
            .put("abilitySpelling", item.abilitySpelling)
            .put("abilityContext", item.abilityContext)

    private fun progressFrom(item: JSONObject, wordId: Long) = WordProgressEntity(
        wordId = wordId,
        familiarity = item.getInt("familiarity"),
        streak = item.getInt("streak"),
        wrongCount = item.getInt("wrongCount"),
        attempts = item.getInt("attempts"),
        correctCount = item.getInt("correctCount"),
        lastReviewedAt = item.getLong("lastReviewedAt"),
        dueAt = item.getLong("dueAt"),
        isMastered = item.getBoolean("isMastered"),
        isFavorite = item.getBoolean("isFavorite"),
        updatedAt = item.optLong("updatedAt"),
        category = item.optString("category", if (item.getBoolean("isMastered")) "mastered" else "learning"),
        level = item.optInt("level"),
        passStreak = item.optInt("passStreak"),
        lastListenPass = item.optNullableBoolean("lastListenPass"),
        lastChinesePass = item.optNullableBoolean("lastChinesePass"),
        lastSpotCheckAt = item.optLong("lastSpotCheckAt"),
        masteryStreak = item.optInt("masteryStreak"),
        fixedAt = item.optLong("fixedAt"),
        mainlineDualPending = item.optBoolean("mainlineDualPending"),
        mainlineClassifiedDate = item.optString("mainlineClassifiedDate"),
        mainlineRemedyReviewDate = item.optString("mainlineRemedyReviewDate"),
        mainlineRemedyTestDate = item.optString("mainlineRemedyTestDate"),
        mainlineLearnedDate = item.optString("mainlineLearnedDate"),
        mainlineDualDeferredDate = item.optString("mainlineDualDeferredDate"),
        mainlineConsolidationDate = item.optString("mainlineConsolidationDate"),
        stabilityDays = item.optDouble("stabilityDays", 0.0).finiteOrZero(),
        difficulty = item.optDouble("difficulty", 0.0).finiteOrZero(),
        practiceHits = item.optInt("practiceHits"),
        practiceMisses = item.optInt("practiceMisses"),
        practiceHinted = item.optBoolean("practiceHinted"),
        practiceForm = item.optString("practiceForm"),
        firstResult = item.optString("firstResult"),
        abilityMeaning = item.optInt("abilityMeaning"),
        abilityListening = item.optInt("abilityListening"),
        abilitySpelling = item.optInt("abilitySpelling"),
        abilityContext = item.optInt("abilityContext"),
    )

    private fun Double.finiteOrZero(): Double = if (isFinite()) this else 0.0

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else optBoolean(key)

    private data class RestorePlan(
        val progress: List<WordProgressEntity>,
        val logs: List<ReviewLogEntity>,
        val sessions: List<StudySessionEntity>,
        val settings: UserSettings?,
        val profile: RetentionProfile,
    )
}
