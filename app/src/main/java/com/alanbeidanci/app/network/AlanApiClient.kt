package com.alanbeidanci.app.network

import com.alanbeidanci.app.BuildConfig
import com.alanbeidanci.app.data.PendingSyncEventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.net.ssl.HttpsURLConnection

class AlanApiClient(private val sessionStore: SecureSessionStore) {
    private val refreshMutex = Mutex()

    val deviceId: String get() = sessionStore.deviceId

    fun nextClientSeq(): Long = sessionStore.nextClientSeq()

    suspend fun register(username: String, password: String): UserSession = authenticate("register", username, password)

    suspend fun login(username: String, password: String): UserSession = authenticate("login", username, password)

    suspend fun logout() {
        val access = sessionStore.session.value?.accessToken
        runCatching { request("POST", "/v1/auth/logout", accessToken = access) }
        sessionStore.clear()
    }

    suspend fun search(query: String): WordLookupResponse {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return parseLookup(authorizedRequest("GET", "/v1/words/search?q=$encoded"))
    }

    suspend fun scan(content: String): WordLookupResponse = parseLookup(
        authorizedRequest("POST", "/v1/words/scan", JSONObject().put("content", content)),
    )

    suspend fun aiQuery(query: String): String = authorizedRequest(
        "POST",
        "/v1/words/ai-query",
        JSONObject().put("query", query),
    ).getString("answer")

    suspend fun mainlineErrorReport(date: java.time.LocalDate): DailyMainlineErrorReport =
        parseMainlineErrorReport(
            authorizedRequest("GET", "/v1/reports/mainline-errors?date=${date}"),
        )

    suspend fun repeatRecordings(limit: Int = 500): List<RepeatRecording> {
        val response = authorizedRequest("GET", "/v1/repeat-recordings?limit=${limit.coerceIn(1, 500)}")
        val rows = response.optJSONArray("recordings") ?: return emptyList()
        return buildList {
            for (index in 0 until rows.length()) {
                val item = rows.optJSONObject(index) ?: continue
                add(
                    RepeatRecording(
                        id = item.optString("id"),
                        wordId = item.optLongOrNull("wordId"),
                        word = item.optString("word"),
                        translation = item.optString("translation"),
                        partOfSpeech = item.optString("partOfSpeech"),
                        mode = item.optString("mode"),
                        accent = item.optString("accent"),
                        recordedDate = item.optString("recordedDate"),
                        recordedAt = item.optString("recordedAt"),
                        durationMs = item.optInt("durationMs"),
                        assessmentStatus = item.optString("assessmentStatus", "NOT_REQUESTED"),
                        assessmentScore = item.optIntOrNull("assessmentScore"),
                        assessmentStars = item.optIntOrNull("assessmentStars"),
                        assessmentCode = item.optString("assessmentCode"),
                        contentType = item.optString("contentType", "audio/webm"),
                    ),
                )
            }
        }
    }

    suspend fun repeatRecordingAudio(id: String): RepeatRecordingAudio {
        require(id.matches(Regex("[0-9a-fA-F-]{36}"))) { "录音编号无效" }
        val current = sessionStore.session.value
            ?: throw AlanApiException(401, "authentication_required", "请先登录账号")
        return try {
            requestBytes("GET", "/v1/repeat-recordings/$id/audio", current.accessToken)
        } catch (error: AlanApiException) {
            if (error.status != 401) throw error
            val refreshed = refreshMutex.withLock {
                val latest = sessionStore.session.value
                    ?: throw AlanApiException(401, "authentication_required", "请重新登录")
                if (latest.accessToken != current.accessToken) latest else refresh(latest.refreshToken)
            }
            requestBytes("GET", "/v1/repeat-recordings/$id/audio", refreshed.accessToken)
        }
    }

    suspend fun syncAnswer(wordId: Long, eventType: String, correct: Boolean?) {
        val now = java.time.Instant.now()
        val event = JSONObject()
            .put("eventId", java.util.UUID.randomUUID().toString())
            .put("deviceId", sessionStore.deviceId)
            .put("wordId", wordId)
            .put("eventType", eventType)
            .put("clientTime", now.toString())
            .put("localDate", localDateKey(now))
            .put("clientSeq", sessionStore.nextClientSeq())
            .put("schemaVersion", 1)
        if (correct != null) event.put("correct", correct)
        authorizedRequest("POST", "/v1/sync/events/batch", JSONObject()
            .put("events", org.json.JSONArray().put(event))
            .put("appVersion", BuildConfig.VERSION_NAME))
    }

    suspend fun syncFavorite(wordId: Long, favorite: Boolean) {
        val now = java.time.Instant.now()
        val event = JSONObject()
            .put("eventId", java.util.UUID.randomUUID().toString())
            .put("deviceId", sessionStore.deviceId)
            .put("wordId", wordId)
            .put("eventType", "FAVORITE")
            .put("favorite", favorite)
            .put("clientTime", now.toString())
            .put("localDate", localDateKey(now))
            .put("clientSeq", sessionStore.nextClientSeq())
            .put("schemaVersion", 1)
        authorizedRequest("POST", "/v1/sync/events/batch", JSONObject()
            .put("events", org.json.JSONArray().put(event))
            .put("appVersion", BuildConfig.VERSION_NAME))
    }

    suspend fun uploadPendingEvents(events: List<PendingSyncEventEntity>): Boolean = runCatching {
        val array = org.json.JSONArray()
        events.forEach { event ->
            val json = JSONObject()
                .put("eventId", event.eventId)
                .put("deviceId", event.deviceId)
                .put("wordId", event.wordId)
                .put("eventType", event.eventType)
                .put("clientTime", event.clientTime)
                .put("localDate", localDateKey(event.clientTime))
                .put("clientSeq", event.clientSeq)
                .put("dual", event.isDual)
                .put("mainline", event.isMainline)
                .put("test", event.isTest)
                .put("schemaVersion", 1)
            if (event.correct != null) json.put("correct", event.correct)
            if (event.favorite != null) json.put("favorite", event.favorite)
            if (!event.spellingQuality.isNullOrBlank()) json.put("spellingQuality", event.spellingQuality)
            if (!event.progressStateJson.isNullOrBlank()) {
                json.put("progressState", JSONObject(event.progressStateJson))
            }
            array.put(json)
        }
        uploadEvents(array)
        true
    }.getOrDefault(false)

    private suspend fun uploadEvents(events: org.json.JSONArray) {
        authorizedRequest("POST", "/v1/sync/events/batch", JSONObject()
            .put("events", events)
            .put("appVersion", BuildConfig.VERSION_NAME))
    }

    suspend fun uploadPendingSessions(sessions: List<ServerSessionUpload>): Boolean = runCatching {
        val array = org.json.JSONArray()
        sessions.forEach { session ->
            array.put(
                JSONObject()
                    .put("sessionId", session.sessionId)
                    .put("deviceId", session.deviceId)
                    .put("mode", session.mode)
                    .put("wordCount", session.wordCount)
                    .put("completed", session.completed)
                    .put("durationMs", session.durationMs)
                    .put("typedChars", session.typedChars)
                    .put("correctChars", session.correctChars)
                    .put("startedAt", session.startedAt)
                    .put("finishedAt", session.finishedAt)
                    .put("startedDate", session.startedDate),
            )
        }
        authorizedRequest("POST", "/v1/sync/sessions/batch", JSONObject()
            .put("sessions", array)
            .put("appVersion", BuildConfig.VERSION_NAME))
        true
    }.getOrDefault(false)

    suspend fun pushSettings(updates: List<ServerSettingUpdate>): Boolean = runCatching {
        val array = org.json.JSONArray()
        updates.forEach { update ->
            val json = JSONObject()
                .put("key", update.key)
                .put("clientUpdatedAt", java.time.Instant.ofEpochMilli(update.clientUpdatedAtMs).toString())
            json.put("value", update.value)
            array.put(json)
        }
        authorizedRequest(
            "PUT",
            "/v1/sync/settings",
            JSONObject().put("updates", array).put("deviceId", sessionStore.deviceId),
        )
        true
    }.getOrDefault(false)

    suspend fun fetchFullSnapshot(): FullSnapshotPayload? = runCatching {
        val response = authorizedRequest("POST", "/v1/sync/full-snapshot")
        val progress = response.optJSONArray("progress") ?: return@runCatching null
        val settings = response.optJSONArray("settings") ?: return@runCatching null
        FullSnapshotPayload(
            progress = buildList {
                for (index in 0 until progress.length()) {
                    val item = progress.optJSONObject(index) ?: continue
                    add(
                        ServerProgressRow(
                            wordId = item.optLong("wordId"),
                            familiarity = item.optInt("familiarity"),
                            streak = item.optInt("streak"),
                            wrongCount = item.optInt("wrongCount"),
                            attempts = item.optInt("attempts"),
                            correctCount = item.optInt("correctCount"),
                            lastReviewedAt = parseInstant(item.optString("lastReviewedAt")) ?: 0L,
                            dueAt = parseInstant(item.optString("dueAt")) ?: 0L,
                            isMastered = item.optBoolean("isMastered"),
                            isFavorite = item.optBoolean("isFavorite"),
                            updatedAt = parseInstant(item.optString("updatedAt")) ?: 0L,
                            category = item.optString("category", if (item.optBoolean("isMastered")) "mastered" else "learning"),
                            level = item.optInt("level"),
                            passStreak = item.optInt("passStreak"),
                            lastListenPass = item.optNullableBoolean("lastListenPass"),
                            lastChinesePass = item.optNullableBoolean("lastChinesePass"),
                            lastSpotCheckAt = parseInstant(item.optString("lastSpotCheckAt")) ?: 0L,
                            masteryStreak = item.optInt("masteryStreak"),
                            fixedAt = parseInstant(item.optString("fixedAt")) ?: 0L,
                            mainlineDualPending = item.optBoolean("mainlineDualPending"),
                            mainlineClassifiedDate = item.optString("mainlineClassifiedDate"),
                            mainlineRemedyReviewDate = item.optString("mainlineRemedyReviewDate"),
                            mainlineRemedyTestDate = item.optString("mainlineRemedyTestDate"),
                            mainlineLearnedDate = item.optString("mainlineLearnedDate"),
                            mainlineDualDeferredDate = item.optString("mainlineDualDeferredDate"),
                        ),
                    )
                }
            },
            settings = buildList {
                for (index in 0 until settings.length()) {
                    val item = settings.optJSONObject(index) ?: continue
                    add(
                        ServerSetting(
                            key = item.optString("key"),
                            value = item.opt("value"),
                            clientUpdatedAt = parseInstant(item.optString("clientUpdatedAt")) ?: 0L,
                            deviceId = item.optString("deviceId"),
                            serverVersion = item.optLong("serverVersion"),
                        )
                    )
                }
            },
            statsJson = response.optJSONObject("stats")?.toString(),
            mainlineEnabled = response.optBoolean("mainlineEnabled", sessionStore.session.value?.mainlineEnabled ?: true),
        ).also { sessionStore.updateMainlineEnabled(it.mainlineEnabled) }
    }.getOrNull()

    private fun parseInstant(raw: String): Long? =
        if (raw.isBlank()) null else runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull()

    private fun localDateKey(instant: java.time.Instant): String =
        instant.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

    private fun localDateKey(raw: String): String =
        runCatching { localDateKey(java.time.Instant.parse(raw)) }
            .getOrElse { java.time.LocalDate.now().toString() }

    private fun JSONObject.optNullableBoolean(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else optBoolean(key)

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key)

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private suspend fun authenticate(action: String, username: String, password: String): UserSession {
        val response = request(
            method = "POST",
            path = "/v1/auth/$action",
            body = JSONObject()
                .put("username", username.trim())
                .put("password", password)
                .put("deviceId", sessionStore.deviceId)
                .put("appVersion", BuildConfig.VERSION_NAME),
        )
        return parseSession(response).also(sessionStore::save)
    }

    private suspend fun authorizedRequest(method: String, path: String, body: JSONObject? = null): JSONObject {
        val current = sessionStore.session.value
            ?: throw AlanApiException(401, "authentication_required", "请先登录账号")
        return try {
            request(method, path, body, current.accessToken)
        } catch (error: AlanApiException) {
            if (error.status != 401) throw error
            val refreshed = refreshMutex.withLock {
                val latest = sessionStore.session.value
                    ?: throw AlanApiException(401, "authentication_required", "请重新登录")
                if (latest.accessToken != current.accessToken) latest else refresh(latest.refreshToken)
            }
            request(method, path, body, refreshed.accessToken)
        }
    }

    private suspend fun refresh(refreshToken: String): UserSession = try {
        parseSession(
            request(
                "POST",
                "/v1/auth/refresh",
                JSONObject().put("refreshToken", refreshToken),
            ),
        ).also(sessionStore::save)
    } catch (error: Exception) {
        sessionStore.clear()
        throw AlanApiException(401, "session_expired", "登录已过期，请重新登录")
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        accessToken: String? = null,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = URI.create(BuildConfig.API_BASE_URL + path).toURL().openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "AlanVocab-Android/${BuildConfig.VERSION_NAME}")
            accessToken?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val problem = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                throw AlanApiException(
                    status = status,
                    code = problem.optString("code", "request_failed"),
                    message = problem.optString("detail", "请求失败，请稍后重试"),
                )
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } catch (error: AlanApiException) {
            throw error
        } catch (error: IOException) {
            throw AlanApiException(0, "network_unavailable", "无法连接服务器，请检查网络后重试")
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun requestBytes(
        method: String,
        path: String,
        accessToken: String,
    ): RepeatRecordingAudio = withContext(Dispatchers.IO) {
        val connection = URI.create(BuildConfig.API_BASE_URL + path).toURL().openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.setRequestProperty("Accept", "audio/webm,audio/mp4,audio/ogg")
            connection.setRequestProperty("User-Agent", "AlanVocab-Android/${BuildConfig.VERSION_NAME}")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            val status = connection.responseCode
            if (status !in 200..299) {
                val text = connection.errorStream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                val problem = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                throw AlanApiException(
                    status = status,
                    code = problem.optString("code", "request_failed"),
                    message = problem.optString("detail", "录音加载失败，请稍后重试"),
                )
            }
            RepeatRecordingAudio(
                bytes = connection.inputStream.use { it.readBytes() },
                contentType = connection.contentType.orEmpty().substringBefore(';').ifBlank { "audio/webm" },
            )
        } catch (error: AlanApiException) {
            throw error
        } catch (error: IOException) {
            throw AlanApiException(0, "network_unavailable", "无法连接服务器，请检查网络后重试")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSession(json: JSONObject): UserSession {
        val user = json.getJSONObject("user")
        return UserSession(
            userId = user.getLong("id"),
            username = user.getString("username"),
            accessToken = json.getString("accessToken"),
            accessTokenExpiresAt = json.getString("accessTokenExpiresAt"),
            refreshToken = json.getString("refreshToken"),
            refreshTokenExpiresAt = json.getString("refreshTokenExpiresAt"),
            mainlineEnabled = user.optBoolean("mainlineEnabled", true),
        )
    }

    private fun parseLookup(json: JSONObject): WordLookupResponse {
        val resultsJson = json.getJSONArray("results")
        val results = buildList {
            for (index in 0 until resultsJson.length()) {
                val item = resultsJson.getJSONObject(index)
                add(
                    WordLookupItem(
                        id = item.getLong("id"),
                        word = item.getString("word"),
                        phoneticUs = item.optString("phoneticUs"),
                        phoneticUk = item.optString("phoneticUk"),
                        translation = item.getString("translation"),
                        partOfSpeech = item.optString("partOfSpeech"),
                        example = item.optString("example"),
                        exampleTranslation = item.optString("exampleTranslation"),
                    ),
                )
            }
        }
        return WordLookupResponse(
            resolvedQuery = json.getString("resolvedQuery"),
            language = json.getString("language"),
            source = json.getString("source"),
            results = results,
        )
    }

    private fun parseMainlineErrorReport(json: JSONObject): DailyMainlineErrorReport {
        val errorTypes = json.optJSONArray("errorTypes")
        val words = json.optJSONArray("words")
        return DailyMainlineErrorReport(
            date = json.optString("date"),
            englishAnswers = json.optInt("englishAnswers"),
            incorrectAnswers = json.optInt("incorrectAnswers"),
            uniqueErrorWords = json.optInt("uniqueErrorWords"),
            accuracyPercent = json.optIntOrNull("accuracyPercent"),
            errorTypes = buildList {
                if (errorTypes != null) for (index in 0 until errorTypes.length()) {
                    val item = errorTypes.optJSONObject(index) ?: continue
                    add(
                        MainlineErrorTypeSummary(
                            code = item.optString("code"),
                            label = item.optString("label"),
                            count = item.optInt("count"),
                            examples = item.optJSONArray("examples").toStringList(),
                            suggestion = item.optString("suggestion"),
                        ),
                    )
                }
            },
            words = buildList {
                if (words != null) for (index in 0 until words.length()) {
                    val item = words.optJSONObject(index) ?: continue
                    add(
                        MainlineErrorWordSummary(
                            wordId = item.optLong("wordId"),
                            word = item.optString("word"),
                            latestInput = item.optString("latestInput"),
                            errorCount = item.optInt("errorCount"),
                            errorTypes = item.optJSONArray("errorTypes").toStringList(),
                            stages = item.optJSONArray("stages").toStringList(),
                        ),
                    )
                }
            },
        )
    }

    private fun org.json.JSONArray?.toStringList(): List<String> = buildList {
        val array = this@toStringList ?: return@buildList
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
