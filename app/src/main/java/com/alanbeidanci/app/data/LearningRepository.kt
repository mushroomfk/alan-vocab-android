package com.alanbeidanci.app.data

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import com.alanbeidanci.app.BuildConfig
import com.alanbeidanci.app.network.AlanApiClient
import com.alanbeidanci.app.network.WordLookupItem
import com.alanbeidanci.app.network.WordLookupResponse
import com.alanbeidanci.app.network.ServerSessionUpload
import com.alanbeidanci.app.network.ServerSetting
import com.alanbeidanci.app.network.ServerSettingUpdate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.json.JSONObject

data class HomeSummary(
    val todayLearned: Int = 0,
    val todayTouched: Int = 0,
    val todayReviews: Int = 0,
    val dueCount: Int = 0,
    val masteredCount: Int = 0,
    val fixedCount: Int = 0,
    val weakCount: Int = 0,
    val accuracy: Int = 0,
    val mainlineAnswers: Int = 0,
    val streakDays: Int = 0,
)

data class BookProgress(
    val book: WordBookEntity,
    val total: Int,
    val learned: Int,
    val mastered: Int,
    val units: List<UnitSummary> = emptyList(),
)

data class UnitSummary(
    val key: String,
    val name: String,
    val count: Int,
    val folderId: String = "",
    val folderName: String = "",
)

data class ReviewCollections(
    val due: List<WordWithProgress> = emptyList(),
    val weak: List<WordWithProgress> = emptyList(),
    val wrong: List<WordWithProgress> = emptyList(),
    val pending: List<WordWithProgress> = emptyList(),
    val forgotten: List<WordWithProgress> = emptyList(),
    val mastered: List<WordWithProgress> = emptyList(),
    val fixed: List<WordWithProgress> = emptyList(),
    val favorite: List<WordWithProgress> = emptyList(),
)

data class PracticeSnapshot(
    val level: Int = 0,
    val attempts: Int = 0,
    val learned: Boolean = false,
    val formName: String = "",
    val hits: Int = 0,
    val misses: Int = 0,
    val hinted: Boolean = false,
    val firstResult: String = "",
    val meaning: Int = 0,
    val listening: Int = 0,
    val spelling: Int = 0,
    val context: Int = 0,
    val skillMemory: String = "",
    val plan: String = "",
)

data class PracticeSession(
    val wordId: Long,
    val formName: String,
    val hits: Int,
    val misses: Int,
    val hinted: Boolean,
    val firstResult: String,
    val meaning: Int,
    val listening: Int,
    val spelling: Int,
    val context: Int,
    val plan: String = "",
)

data class DayStat(
    val label: String = "",
    val durationMs: Long = 0,
    val words: Int = 0,
    val newWords: Int = 0,
    val reviewWords: Int = 0,
    val masteredWords: Int = 0,
    val answers: Int = 0,
    val methodDist: String = "",
    val dueCount: Int = 0,
)

data class UnitMasteryItem(
    val unitName: String = "",
    val folderName: String = "",
    val total: Int = 0,
    val learned: Int = 0,
    val mastered: Int = 0,
)

data class BookMasteryGroup(
    val book: WordBookEntity,
    val total: Int = 0,
    val learned: Int = 0,
    val mastered: Int = 0,
    val units: List<UnitMasteryItem> = emptyList(),
)

data class StatsReport(
    val todayDurationMs: Long = 0,
    val todayWords: Int = 0,
    val todayAnswers: Int = 0,
    val todayAccuracy: Int = 0,
    val todayMainlineAnswers: Int = 0,
    val todayMainlineAccuracy: Int = 0,
    val streakDays: Int = 0,
    val dueCount: Int = 0,
    val listeningMastery: MasteryMetric = MasteryMetric(),
    val meaningMastery: MasteryMetric = MasteryMetric(),
    val spellingStability: MasteryMetric = MasteryMetric(),
    val independentRecall: Map<MemorySkill, MasteryMetric> = emptyMap(),
    val days: List<DayStat> = emptyList(),
    val books: List<BookMasteryGroup> = emptyList(),
)

data class MasteryMetric(
    val correct: Long = 0,
    val total: Long = 0,
    val near: Long = 0,
) {
    val percent: Int? get() = if (total > 0) ((correct * 100) / total).toInt() else null
}

internal data class PendingDayStatsDelta(
    val answers: Int = 0,
    val correct: Int = 0,
    val wordIds: Set<Long> = emptySet(),
    val newWordIds: Set<Long> = emptySet(),
    val masteredWordIds: Set<Long> = emptySet(),
    val meaningAnswers: Int = 0,
    val listeningAnswers: Int = 0,
    val spellingAnswers: Int = 0,
    val mainlineAnswers: Int = 0,
    val mainlineCorrect: Int = 0,
) {
    val reviewWords: Int get() = (wordIds - newWordIds).size
}

internal fun pendingDayStatsDeltas(
    events: List<PendingSyncEventEntity>,
    progressByWord: Map<Long, WordProgressEntity>,
): Map<String, PendingDayStatsDelta> {
    data class MutableDelta(
        var answers: Int = 0,
        var correct: Int = 0,
        val wordIds: MutableSet<Long> = linkedSetOf(),
        val newWordIds: MutableSet<Long> = linkedSetOf(),
        val masteredWordIds: MutableSet<Long> = linkedSetOf(),
        var meaningAnswers: Int = 0,
        var listeningAnswers: Int = 0,
        var spellingAnswers: Int = 0,
        var mainlineAnswers: Int = 0,
        var mainlineCorrect: Int = 0,
    )

    val result = linkedMapOf<String, MutableDelta>()
    events.forEach { event ->
        if (event.eventType !in setOf("MEANING", "LISTENING", "SPELLING") || event.correct == null) return@forEach
        val instant = runCatching { Instant.parse(event.clientTime) }.getOrNull() ?: return@forEach
        val date = instant.atZone(ZoneId.systemDefault()).toLocalDate().toString()
        val row = result.getOrPut(date) { MutableDelta() }
        row.answers += 1
        if (event.correct == true) row.correct += 1
        row.wordIds += event.wordId
        val progress = progressByWord[event.wordId]
        if (progress?.mainlineLearnedDate == date) row.newWordIds += event.wordId
        if (progress?.isMastered == true) row.masteredWordIds += event.wordId
        when (event.eventType) {
            "MEANING" -> row.meaningAnswers += 1
            "LISTENING" -> row.listeningAnswers += 1
            "SPELLING" -> row.spellingAnswers += 1
        }
        if (event.isDual && event.isMainline) {
            row.mainlineAnswers += 1
            if (event.correct == true) row.mainlineCorrect += 1
        }
    }
    return result.mapValues { (_, row) ->
        PendingDayStatsDelta(
            answers = row.answers,
            correct = row.correct,
            wordIds = row.wordIds,
            newWordIds = row.newWordIds,
            masteredWordIds = row.masteredWordIds,
            meaningAnswers = row.meaningAnswers,
            listeningAnswers = row.listeningAnswers,
            spellingAnswers = row.spellingAnswers,
            mainlineAnswers = row.mainlineAnswers,
            mainlineCorrect = row.mainlineCorrect,
        )
    }
}

internal data class PendingMainlineDimensions(
    val listening: MasteryMetric = MasteryMetric(),
    val meaning: MasteryMetric = MasteryMetric(),
    val spelling: MasteryMetric = MasteryMetric(),
)

internal fun pendingMainlineDimensions(events: List<PendingSyncEventEntity>): PendingMainlineDimensions {
    val answers = events.filter {
        it.isDual && it.isMainline && it.correct != null && it.eventType in setOf("LISTENING", "MEANING")
    }
    fun metric(type: String): MasteryMetric {
        val rows = answers.filter { it.eventType == type }
        return MasteryMetric(correct = rows.count { it.correct == true }.toLong(), total = rows.size.toLong())
    }
    val exact = answers.count { it.spellingQuality == "exact" || (it.spellingQuality == null && it.correct == true) }
    val near = answers.count { it.spellingQuality == "near" }
    return PendingMainlineDimensions(
        listening = metric("LISTENING"),
        meaning = metric("MEANING"),
        spelling = MasteryMetric(correct = exact.toLong(), total = answers.size.toLong(), near = near.toLong()),
    )
}

sealed interface DictionaryPrepareState {
    data object Waiting : DictionaryPrepareState
    data class Importing(val index: Int, val total: Int, val bookName: String) : DictionaryPrepareState
    data object Ready : DictionaryPrepareState
    data class Failed(val message: String) : DictionaryPrepareState
}

class LearningRepository(
    private val db: AlanDatabase,
    private val context: Context,
    private val api: AlanApiClient? = null,
    private val preferences: PreferencesStore? = null,
) {
    private val bookDao = db.wordBookDao()
    private val wordDao = db.wordDao()
    private val progressDao = db.progressDao()
    val dualTests = DualTestSessions(db, this)
    private val logDao = db.reviewLogDao()
    private val sessionDao = db.studySessionDao()
    private val pendingSyncDao = db.pendingSyncDao()
    private val serverStatsDao = db.serverStatsDao()
    private val assetLoader = DictionaryAssetLoader(context)
    private val syncMutex = Mutex()
    private val seedMutex = Mutex()
    private val _prepareState = MutableStateFlow<DictionaryPrepareState>(DictionaryPrepareState.Waiting)
    val prepareState: StateFlow<DictionaryPrepareState> = _prepareState
    private val accountScope = context.getSharedPreferences("alan_account_scope", Context.MODE_PRIVATE)

    val books: Flow<List<WordBookEntity>> = bookDao.observeAll()
    val progress: Flow<List<WordProgressEntity>> = progressDao.observeAll()
    val logs: Flow<List<ReviewLogEntity>> = logDao.observeAll()
    val sessions: Flow<List<StudySessionEntity>> = sessionDao.observeAll()
    internal val calendarChanges = localCalendarChanges()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeScopedWords(): Flow<List<WordEntity>> =
        (preferences?.settings ?: flowOf(UserSettings())).flatMapLatest { settings ->
            val bookIds = settings.selectedBookIds.sorted()
            if (bookIds.isEmpty()) {
                flowOf(emptyList())
            } else {
                wordDao.observeByBooks(bookIds).map { words ->
                    words.filter { MainlineScheduler.matchesUnitScope(it, settings.selectedUnitKeys) }
                }
            }
        }

    suspend fun ensureSeeded() {
        if (_prepareState.value is DictionaryPrepareState.Ready) return
        seedMutex.withLock {
            if (_prepareState.value is DictionaryPrepareState.Ready) return
            try {
                importDictionaries()
                _prepareState.value = DictionaryPrepareState.Ready
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _prepareState.value = DictionaryPrepareState.Failed(error.message ?: "词库没有准备好")
            }
        }
    }

    private suspend fun importDictionaries() {
        val preferences = context.getSharedPreferences("alan_dictionary_assets", Context.MODE_PRIVATE)
        val stored = preferences.getInt("version", 0)
        if (stored >= assetLoader.version && bookDao.count() > 0) return
        val total = assetLoader.dictionaryCount.coerceAtLeast(1)
        _prepareState.value = DictionaryPrepareState.Importing(0, total, "正在读取词库")
        val existingIds = bookDao.getAll().map { it.id }.toMutableSet()
        val refreshSitcom = stored in 1 until assetLoader.version
        // 已有词书只补新书。美剧词书按稳定单词 id 原地更新，不能整本删除，否则外键会清掉进度和收藏。
        assetLoader.forEachDictionary(
            onBook = { index, bookTotal, name ->
                _prepareState.value = DictionaryPrepareState.Importing(index, bookTotal, name)
            },
        ) { asset ->
            when {
                refreshSitcom && asset.book.id in SITCOM_BOOK_IDS && asset.book.id in existingIds ->
                    refreshBookInPlace(asset)
                existingIds.isNotEmpty() && asset.book.id in existingIds -> Unit
                else -> {
                    db.withTransaction {
                        if (existingIds.isEmpty()) bookDao.insertAll(listOf(asset.book)) else bookDao.insert(asset.book)
                        wordDao.insertAll(asset.words)
                    }
                }
            }
        }
        preferences.edit().putInt("version", assetLoader.version).apply()
    }

    /**
     * 单词 id 由词书序号和词条下标生成。同一 id 改内容时保留进度；新 id 插入；旧 id 消失才删除对应进度。
     * 不能用 REPLACE：SQLite 会先删行，进度外键跟着级联删除。
     */
    private suspend fun refreshBookInPlace(asset: AssetDictionary) {
        db.withTransaction {
            if (bookDao.getById(asset.book.id) == null) {
                bookDao.insert(asset.book)
                if (asset.words.isNotEmpty()) wordDao.insertAll(asset.words)
                return@withTransaction
            }
            bookDao.update(asset.book)
            val current = wordDao.getByBook(asset.book.id).associateBy { it.id }
            val incomingIds = HashSet<Long>(asset.words.size)
            val toInsert = ArrayList<WordEntity>()
            val toUpdate = ArrayList<WordEntity>()
            for (word in asset.words) {
                incomingIds += word.id
                val old = current[word.id]
                when {
                    old == null -> toInsert += word
                    old != word -> toUpdate += word
                }
            }
            if (toUpdate.isNotEmpty()) wordDao.updateAll(toUpdate)
            if (toInsert.isNotEmpty()) wordDao.insertAll(toInsert)
            current.keys.filter { it !in incomingIds }.chunked(400).forEach { removed ->
                if (removed.isNotEmpty()) wordDao.deleteByIds(removed)
            }
        }
    }

    fun observeHome(): Flow<HomeSummary> = combine(progress, logs, calendarChanges) { progressItems, logItems, _ ->
        val today = LocalDate.now()
        val todayLogs = logItems.filter { it.localDate() == today && it.countsAsAnswer() && it.questionType != QuestionType.CONFIDENCE.name }
        val mainlineDualLogs = todayLogs.filter { it.isDual && it.isMainline }
        val learnedToday = progressItems.count { it.mainlineLearnedDate == today.toString() }
        val activeDays = logItems
            .filter { it.countsAsAnswer() && it.questionType != QuestionType.CONFIDENCE.name }
            .map { it.localDate() }
            .toSet()
        HomeSummary(
            todayLearned = learnedToday,
            todayTouched = todayLogs.map { it.wordId }.distinct().size,
            todayReviews = todayLogs.size,
            dueCount = progressItems.count { it.mainlineClassifiedDate.isNotBlank() && it.category in setOf("error", "weak") },
            masteredCount = progressItems.count { it.category == "mastered" },
            fixedCount = progressItems.count { it.category == "fixed" },
            weakCount = progressItems.count { it.mainlineClassifiedDate.isNotBlank() && it.category in setOf("error", "weak") },
            accuracy = mainlineDualLogs.accuracy(),
            mainlineAnswers = mainlineDualLogs.size,
            streakDays = calculateStreak(activeDays),
        )
    }

    fun observeStats(): Flow<StatsReport> = combine(
        combine(progress, logs, sessionDao.observeAll(), books, observeScopedWords()) { progressItems, logItems, sessionItems, bookItems, wordItems ->
            StatsInput(progressItems, logItems, sessionItems, bookItems, wordItems)
        },
        serverStatsDao.observeAll(),
        pendingSyncDao.observeAll(),
        preferences?.settings ?: flowOf(UserSettings()),
        calendarChanges,
    ) { input, statsItems, pendingEvents, settings, _ ->
        val progressItems = input.progressItems
        val logItems = input.logItems
        val sessionItems = input.sessionItems
        val bookItems = input.bookItems
        val wordItems = input.wordItems
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val weekday = arrayOf("日", "一", "二", "三", "四", "五", "六")
        val nonConfidence = logItems.filter { it.countsAsAnswer() && it.questionType != QuestionType.CONFIDENCE.name }

        // 跨设备统计合并：服务端聚合 + 本机未上传部分（未同步会话与待上传答题事件）。
        val remote = parseRemoteStats(statsItems)
        val unsyncedSessions = sessionItems.filter { !it.synced }
        val progressByWord = progressItems.associateBy { it.wordId }
        val pendingDeltas = pendingDayStatsDeltas(pendingEvents, progressByWord)
        val remoteByDate = (remote?.days.orEmpty()).associate { it.date to it }
        val mergedByDate = LinkedHashMap<String, MergedDay>()
        val streakDates = HashSet<String>()
        for ((date, day) in remoteByDate) {
            mergedByDate[date] = MergedDay(
                durationMs = day.durationMs,
                answers = day.answers,
                correct = day.correct,
                words = day.words,
                newWords = day.newWords,
                reviewWords = day.reviewWords,
                masteredWords = day.masteredWords,
                meaningAnswers = day.meaningAnswers,
                listeningAnswers = day.listeningAnswers,
                spellingAnswers = day.spellingAnswers,
                mainlineAnswers = day.mainlineAnswers,
                mainlineCorrect = day.mainlineCorrect,
                hasDetails = day.hasDetails,
            )
            if (day.durationMs > 0 || day.answers > 0) streakDates.add(date)
        }
        fun mergeRow(
            date: String,
            durationMs: Long,
            answers: Int,
            correct: Int,
            words: Int = 0,
            newWords: Int = 0,
            reviewWords: Int = 0,
            masteredWords: Int = 0,
            meaningAnswers: Int = 0,
            listeningAnswers: Int = 0,
            spellingAnswers: Int = 0,
            mainlineAnswers: Int = 0,
            mainlineCorrect: Int = 0,
        ) {
            val row = mergedByDate[date] ?: MergedDay().also {
                mergedByDate[date] = it
            }
            mergedByDate[date] = row.copy(
                durationMs = row.durationMs + durationMs,
                answers = row.answers + answers,
                correct = row.correct + correct,
                words = row.words + words,
                newWords = row.newWords + newWords,
                reviewWords = row.reviewWords + reviewWords,
                masteredWords = row.masteredWords + masteredWords,
                meaningAnswers = row.meaningAnswers + meaningAnswers,
                listeningAnswers = row.listeningAnswers + listeningAnswers,
                spellingAnswers = row.spellingAnswers + spellingAnswers,
                mainlineAnswers = row.mainlineAnswers + mainlineAnswers,
                mainlineCorrect = row.mainlineCorrect + mainlineCorrect,
            )
            streakDates.add(date)
        }
        for (session in unsyncedSessions) {
            if (session.durationMs <= 0) continue
            mergeRow(dateKey(session.startedAt), session.durationMs, 0, 0)
        }
        for ((date, delta) in pendingDeltas) {
            mergeRow(
                date = date,
                durationMs = 0,
                answers = delta.answers,
                correct = delta.correct,
                words = delta.wordIds.size,
                newWords = delta.newWordIds.size,
                reviewWords = delta.reviewWords,
                masteredWords = delta.masteredWordIds.size,
                meaningAnswers = delta.meaningAnswers,
                listeningAnswers = delta.listeningAnswers,
                spellingAnswers = delta.spellingAnswers,
                mainlineAnswers = delta.mainlineAnswers,
                mainlineCorrect = delta.mainlineCorrect,
            )
        }
        val todayKey = today.toString()
        val todayMerged = mergedByDate[todayKey]

        val days = buildList {
            for (offset in 6 downTo 0) add(today.minusDays(offset.toLong()))
        }
        val dayStartMs = days.map { date ->
            date to date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        val firstSeen = HashMap<Long, LocalDate>()
        for (log in nonConfidence) {
            val date = Instant.ofEpochMilli(log.timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
            val existing = firstSeen[log.wordId]
            if (existing == null || date < existing) firstSeen[log.wordId] = date
        }
        val dueNow = progressItems.count {
            it.mainlineClassifiedDate.isNotBlank() && it.category in setOf("error", "weak")
        }

        val dayStats = dayStartMs.map { (date, start) ->
            val end = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val dayLogs = nonConfidence.filter { it.timestamp in start until end }
            val daySessions = sessionItems.filter { it.startedAt in start until end }
            val wordIds = dayLogs.map { it.wordId }.toSet()
            val newWords = wordIds.count { firstSeen[it] == date }
            val masteredWords = wordIds.count { progressByWord[it]?.isMastered == true }
            val spelling = dayLogs.count { it.actualSkill() == MemorySkill.SPELLING }
            val listening = dayLogs.count { it.actualSkill() == MemorySkill.LISTENING }
            val meaning = dayLogs.count { it.actualSkill() == MemorySkill.MEANING }
            val methodParts = buildList {
                if (spelling > 0) add("拼写$spelling")
                if (listening > 0) add("听音$listening")
                if (meaning > 0) add("词义$meaning")
            }
            val merged = mergedByDate[date.toString()]
            val useRemoteDetails = merged?.hasDetails == true
            val mergedMethodParts = buildList {
                if ((merged?.spellingAnswers ?: 0) > 0) add("拼写${merged?.spellingAnswers}")
                if ((merged?.listeningAnswers ?: 0) > 0) add("听音${merged?.listeningAnswers}")
                if ((merged?.meaningAnswers ?: 0) > 0) add("词义${merged?.meaningAnswers}")
            }
            DayStat(
                label = "${date.monthValue.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}（${weekday[date.dayOfWeek.value % 7]}）",
                durationMs = merged?.durationMs ?: daySessions.sumOf { it.durationMs },
                words = if (useRemoteDetails) merged.words else wordIds.size,
                newWords = if (useRemoteDetails) merged.newWords else newWords,
                reviewWords = if (useRemoteDetails) merged.reviewWords else wordIds.size - newWords,
                masteredWords = if (useRemoteDetails) merged.masteredWords else masteredWords,
                // 会话汇总的 answers 是 0，没有答题明细时不能盖住本地日志。
                answers = merged?.takeIf { it.hasDetails }?.answers ?: dayLogs.size,
                methodDist = (if (useRemoteDetails) mergedMethodParts else methodParts).joinToString(" · "),
                dueCount = if (date == today) dueNow else 0,
            )
        }

        val todayStart = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val tomorrowStart = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val todayLogs = nonConfidence.filter { it.timestamp in todayStart until tomorrowStart }
        val todayWords = if (todayMerged?.hasDetails == true) todayMerged.words else todayLogs.map { it.wordId }.distinct().size
        val todayDuration = todayMerged?.durationMs
            ?: sessionItems.filter { it.startedAt in todayStart until tomorrowStart }.sumOf { it.durationMs }
        val useRemoteAnswerDetails = todayMerged?.hasDetails == true
        val todayAnswers = if (useRemoteAnswerDetails) todayMerged.answers else todayLogs.size
        val todayCorrect = if (useRemoteAnswerDetails) todayMerged.correct else todayLogs.count { it.correct }
        val todayAccuracy = if (todayAnswers == 0) 0 else
            (todayCorrect * 100 / todayAnswers)
        val todayMainlineLogs = todayLogs.filter { it.isDual && it.isMainline }
        val todayMainlineAnswers = if (useRemoteAnswerDetails) todayMerged.mainlineAnswers else todayMainlineLogs.size
        val todayMainlineCorrect = if (useRemoteAnswerDetails) todayMerged.mainlineCorrect else todayMainlineLogs.count { it.correct }
        val todayMainlineAccuracy = if (todayMainlineAnswers == 0) 0 else
            (todayMainlineCorrect * 100 / todayMainlineAnswers)
        val dimensionAnswers = nonConfidence.filter { it.isMainline }
        fun localDimension(skill: MemorySkill): MasteryMetric {
            val rows = dimensionAnswers.filter { it.actualSkill() == skill }
            return MasteryMetric(rows.count { it.correct }.toLong(), rows.size.toLong())
        }
        // Legacy remote "meaning" aggregates were Chinese-to-English spelling, not meaning recall.
        val listeningMastery = localDimension(MemorySkill.LISTENING)
        val meaningMastery = localDimension(MemorySkill.MEANING)
        val spellingStability = localDimension(MemorySkill.SPELLING)
        val independentRecall = MemorySkill.entries.associateWith { skill ->
            val rows = logItems.filter { it.eventKind == "schedule" && it.memoryAlgorithm == SkillMemory.ALGORITHM &&
                it.memorySkill == skill.name && it.memoryIndependent && !it.memoryExposed && !it.hinted &&
                it.memoryPreviousReviewAt > 0 && it.timestamp - it.memoryPreviousReviewAt >= SkillMemory.DAY }
            MasteryMetric(rows.count { it.correct }.toLong(), rows.size.toLong())
        }
        val activeDays = nonConfidence
            .map { Instant.ofEpochMilli(it.timestamp).atZone(ZoneId.systemDefault()).toLocalDate() }
            .toSet()

        val wordByBook = wordItems.groupBy { it.bookId }
        val selectedBookIds = settings.selectedBookIds.ifEmpty { setOf(settings.currentBookId) }
        val bookMastery = bookItems.filter { it.id in selectedBookIds }.map { book ->
            val bookWords = wordByBook[book.id].orEmpty().filter { word ->
                MainlineScheduler.matchesUnitScope(word, settings.selectedUnitKeys)
            }
            val unitMap = LinkedHashMap<String, MutableList<WordEntity>>()
            for (word in bookWords) {
                val key = "${word.unitId}::${word.unitName}"
                unitMap.getOrPut(key) { mutableListOf() }.add(word)
            }
            val units = unitMap.map { (key, words) ->
                val id = key.substringBefore("::")
                val name = key.substringAfter("::", id).ifBlank { id.ifBlank { key } }
                val learned = words.count { progressByWord[it.id]?.attempts ?: 0 > 0 }
                val mastered = words.count { progressByWord[it.id]?.isMastered == true }
                UnitMasteryItem(
                    unitName = name,
                    folderName = words.firstOrNull()?.folderName.orEmpty(),
                    total = words.size,
                    learned = learned,
                    mastered = mastered,
                )
            }
            BookMasteryGroup(
                book = book,
                total = bookWords.size,
                learned = bookWords.count { progressByWord[it.id]?.attempts ?: 0 > 0 },
                mastered = bookWords.count { progressByWord[it.id]?.isMastered == true },
                units = units,
            )
        }

        StatsReport(
            todayDurationMs = todayDuration,
            todayWords = todayWords,
            todayAnswers = todayAnswers,
            todayAccuracy = todayAccuracy,
            todayMainlineAnswers = todayMainlineAnswers,
            todayMainlineAccuracy = todayMainlineAccuracy,
            streakDays = if (streakDates.isNotEmpty()) streakFromDateKeys(streakDates) else calculateStreak(activeDays),
            dueCount = dueNow,
            listeningMastery = listeningMastery,
            meaningMastery = meaningMastery,
            spellingStability = spellingStability,
            independentRecall = independentRecall,
            days = dayStats,
            books = bookMastery,
        )
    }

    private fun dateKey(ts: Long): String =
        Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    private fun streakFromDateKeys(dateKeys: Set<String>): Int {
        var day = LocalDate.now()
        if (day.toString() !in dateKeys && day.minusDays(1).toString() in dateKeys) day = day.minusDays(1)
        var streak = 0
        while (day.toString() in dateKeys) {
            streak += 1
            day = day.minusDays(1)
        }
        return streak
    }

    private data class StatsInput(
        val progressItems: List<WordProgressEntity>,
        val logItems: List<ReviewLogEntity>,
        val sessionItems: List<StudySessionEntity>,
        val bookItems: List<WordBookEntity>,
        val wordItems: List<WordEntity>,
    )

    private data class MergedDay(
        val durationMs: Long = 0,
        val answers: Int = 0,
        val correct: Int = 0,
        val words: Int = 0,
        val newWords: Int = 0,
        val reviewWords: Int = 0,
        val masteredWords: Int = 0,
        val meaningAnswers: Int = 0,
        val listeningAnswers: Int = 0,
        val spellingAnswers: Int = 0,
        val mainlineAnswers: Int = 0,
        val mainlineCorrect: Int = 0,
        val hasDetails: Boolean = false,
    )

    private data class RemoteDay(
        val date: String,
        val durationMs: Long,
        val answers: Int,
        val correct: Int,
        val words: Int,
        val newWords: Int,
        val reviewWords: Int,
        val masteredWords: Int,
        val meaningAnswers: Int,
        val listeningAnswers: Int,
        val spellingAnswers: Int,
        val mainlineAnswers: Int,
        val mainlineCorrect: Int,
        val hasDetails: Boolean,
    )

    private data class RemoteStats(
        val totalDurationMs: Long = 0,
        val totalSessions: Int = 0,
        val totalAnswers: Long = 0,
        val totalCorrect: Long = 0,
        val hasMainlineDimensions: Boolean = false,
        val mainlineListeningAnswers: Long = 0,
        val mainlineListeningCorrect: Long = 0,
        val mainlineMeaningAnswers: Long = 0,
        val mainlineMeaningCorrect: Long = 0,
        val mainlineSpellingAnswers: Long = 0,
        val mainlineSpellingExact: Long = 0,
        val mainlineSpellingNear: Long = 0,
        val days: List<RemoteDay> = emptyList(),
    )

    private fun parseRemoteStats(items: List<ServerStatsEntity>): RemoteStats? {
        val row = items.firstOrNull() ?: return null
        return runCatching {
            val json = JSONObject(row.payloadJson)
            val days = buildList {
                val array = json.optJSONArray("days") ?: return@buildList
                for (index in 0 until array.length()) {
                    val day = array.optJSONObject(index) ?: continue
                    add(
                        RemoteDay(
                            date = day.optString("date"),
                            durationMs = day.optLong("durationMs"),
                            answers = day.optInt("answers"),
                            correct = day.optInt("correct"),
                            words = day.optInt("words"),
                            newWords = day.optInt("newWords"),
                            reviewWords = day.optInt("reviewWords"),
                            masteredWords = day.optInt("masteredWords"),
                            meaningAnswers = day.optInt("meaningAnswers"),
                            listeningAnswers = day.optInt("listeningAnswers"),
                            spellingAnswers = day.optInt("spellingAnswers"),
                            mainlineAnswers = day.optInt("mainlineAnswers"),
                            mainlineCorrect = day.optInt("mainlineCorrect"),
                            hasDetails = day.has("words"),
                        )
                    )
                }
            }
            RemoteStats(
                totalDurationMs = json.optLong("totalDurationMs"),
                totalSessions = json.optInt("totalSessions"),
                totalAnswers = json.optLong("totalAnswers"),
                totalCorrect = json.optLong("totalCorrect"),
                hasMainlineDimensions = json.has("mainlineListeningAnswers"),
                mainlineListeningAnswers = json.optLong("mainlineListeningAnswers"),
                mainlineListeningCorrect = json.optLong("mainlineListeningCorrect"),
                mainlineMeaningAnswers = json.optLong("mainlineMeaningAnswers"),
                mainlineMeaningCorrect = json.optLong("mainlineMeaningCorrect"),
                mainlineSpellingAnswers = json.optLong("mainlineSpellingAnswers"),
                mainlineSpellingExact = json.optLong("mainlineSpellingExact"),
                mainlineSpellingNear = json.optLong("mainlineSpellingNear"),
                days = days,
            )
        }.getOrNull()
    }

    fun observeBookProgress(): Flow<List<BookProgress>> = combine(books, progress) { bookItems, _ ->
        val totalBy = wordDao.countByBookGrouped().associate { it.bookId to it.total }
        val learnedBy = wordDao.learnedByBook().associate { it.bookId to it.total }
        val masteredBy = wordDao.masteredByBook().associate { it.bookId to it.total }
        val unitsBy = wordDao.unitStats().groupBy { it.bookId }
        bookItems.map { book ->
            BookProgress(
                book = book,
                total = totalBy[book.id] ?: 0,
                learned = learnedBy[book.id] ?: 0,
                mastered = masteredBy[book.id] ?: 0,
                units = (unitsBy[book.id].orEmpty()).map { stat ->
                    UnitSummary(
                        key = "${stat.bookId}::${stat.unitId}",
                        name = stat.unitName.ifBlank { stat.unitId.ifBlank { "${stat.bookId}::${stat.unitId}" } },
                        count = stat.total,
                        folderId = stat.folderId,
                        folderName = stat.folderName,
                    )
                }.sortedBy { it.key },
            )
        }
    }

    fun observeReviewCollections(): Flow<ReviewCollections> = combine(progress, observeScopedWords(), calendarChanges) { items, scopedWords, _ ->
        val words = scopedWords.associateBy { it.id }
        val all = items.mapNotNull { progressItem ->
            words[progressItem.wordId]?.let { WordWithProgress(it, progressItem) }
        }
        val wrong = all.filter {
            it.progress!!.mainlineClassifiedDate.isNotBlank() && it.progress.category == "error"
        }
            .sortedByDescending { it.progress!!.wrongCount }
        val pending = all.filter {
            it.progress!!.mainlineClassifiedDate.isNotBlank() && it.progress.category in setOf("error", "weak")
        }.sortedByDescending { it.progress!!.wrongCount }
        ReviewCollections(
            due = pending,
            weak = all.filter {
                it.progress!!.mainlineClassifiedDate.isNotBlank() && it.progress.category == "weak"
            }
                .sortedByDescending { it.progress!!.wrongCount },
            wrong = wrong,
            pending = pending,
            forgotten = all.filter { it.progress!!.category == "relearn" }
                .sortedBy { it.progress!!.dueAt },
            mastered = all.filter { it.progress!!.category == "mastered" }
                .sortedByDescending { it.progress!!.lastReviewedAt },
            fixed = all.filter { it.progress!!.category == "fixed" }
                .sortedByDescending { it.progress!!.fixedAt },
            favorite = all.filter { it.progress!!.isFavorite }
                .sortedBy { it.word.word },
        )
    }

    suspend fun saveSession(
        mode: String,
        wordCount: Int,
        completed: Int,
        durationMs: Long,
        typedChars: Int = 0,
        correctChars: Int = 0,
        startedAt: Long,
        finishedAt: Long,
    ) {
        val session = StudySessionEntity(
            mode = mode,
            wordCount = wordCount,
            completed = completed,
            durationMs = durationMs,
            typedChars = typedChars.coerceAtLeast(0),
            correctChars = correctChars.coerceIn(0, typedChars.coerceAtLeast(0)),
            startedAt = startedAt,
            finishedAt = finishedAt,
            sessionId = UUID.randomUUID().toString(),
            synced = false,
        )
        sessionDao.insert(session)
        if (!BuildConfig.LOCAL_ONLY) {
            api?.let { client -> runCatching { client.uploadPendingSessions(listOf(session.toServerUpload(client.deviceId))) } }
        }
    }

    suspend fun searchLocal(query: String, limit: Int = 20): WordLookupResponse {
        val trimmed = query.trim().replace(Regex("\\s+"), " ")
        if (trimmed.length !in 1..64) error("查询内容长度应为 1 到 64 个字符")
        val rows = wordDao.search(trimmed, LocalWordLookup.likePattern(trimmed), limit.coerceIn(1, 50))
        return WordLookupResponse(
            resolvedQuery = trimmed,
            language = if (trimmed.any { it.code in 0x4E00..0x9FFF }) "zh" else "en",
            source = "local",
            results = rows.map { word ->
                WordLookupItem(
                    id = word.id,
                    word = word.word,
                    phoneticUs = word.phoneticUs,
                    phoneticUk = word.phoneticUk,
                    translation = word.translation,
                    partOfSpeech = word.partOfSpeech,
                    example = word.example,
                    exampleTranslation = word.exampleTranslation,
                )
            },
        )
    }

    private fun StudySessionEntity.toServerUpload(deviceId: String): ServerSessionUpload = ServerSessionUpload(
        sessionId = sessionId,
        deviceId = deviceId,
        mode = canonicalSessionMode(mode),
        wordCount = wordCount,
        completed = completed,
        durationMs = durationMs,
        typedChars = typedChars,
        correctChars = correctChars,
        startedAt = Instant.ofEpochMilli(startedAt).toString(),
        finishedAt = Instant.ofEpochMilli(finishedAt).toString(),
        startedDate = dateKey(startedAt),
    )

    suspend fun getBook(id: String): WordBookEntity? = bookDao.getById(id)

    fun observeBookWords(bookId: String): Flow<List<WordEntity>> = wordDao.observeByBook(bookId)

    suspend fun getWords(bookIds: Set<String>, unitKeys: Set<String> = emptySet()): List<WordEntity> =
        bookIds.sorted().flatMap { wordDao.getByBook(it) }
            .filter { MainlineScheduler.matchesUnitScope(it, unitKeys) }

    suspend fun getSession(
        bookIds: Set<String>,
        unitKeys: Set<String> = emptySet(),
        mode: StudyMode,
        limit: Int,
        shuffle: Boolean,
    ): List<WordEntity> {
        val bookWords = bookIds.sorted().flatMap { wordDao.getByBook(it) }
            .filter { MainlineScheduler.matchesUnitScope(it, unitKeys) }
        val progressMap = progressDao.getAll().associateBy { it.wordId }
        val selected = when (mode) {
            StudyMode.LEARN -> {
                val newWords = bookWords.filter { (progressMap[it.id]?.attempts ?: 0) == 0 }
                if (newWords.isNotEmpty()) newWords else bookWords.filter { progressMap[it.id]?.isMastered != true }
            }
            StudyMode.REVIEW -> {
                val dueIds = progressDao.getDue(System.currentTimeMillis()).map { it.wordId }.toSet()
                bookWords.filter { it.id in dueIds }
            }
        }
        val usable = if (selected.isEmpty() && mode == StudyMode.REVIEW) {
            bookWords.filter { (progressMap[it.id]?.attempts ?: 0) > 0 && progressMap[it.id]?.isMastered != true }
        } else selected
        return (if (shuffle) usable.shuffled() else usable).take(limit)
    }

    suspend fun getRemedyWords(bookIds: Set<String>, unitKeys: Set<String> = emptySet()): List<WordEntity> {
        val progressMap = progressDao.getAll().associateBy { it.wordId }
        val all = bookIds.sorted().flatMap { wordDao.getByBook(it) }
            .filter { unitKeys.isEmpty() || "${it.bookId}::${it.unitId}" in unitKeys }
            .filter {
                val item = progressMap[it.id]
                item?.mainlineClassifiedDate?.isNotBlank() == true && item.category in setOf("error", "weak")
            }
        return all.sortedWith(compareByDescending { progressMap[it.id]?.wrongCount ?: 0 })
    }

    suspend fun getTypingWords(settings: UserSettings, purpose: TypingPurpose): List<WordEntity> {
        val words = getScopedWords(settings)
        val progress = progressDao.getAll()
        val plan = MainlineScheduler.plan(words, progress, settings, pace = currentPace())
        val selected = when (purpose) {
            TypingPurpose.MAINLINE_LEARN -> plan.learnWords
            TypingPurpose.MAINLINE_CONSOLIDATE -> plan.consolidationWords
            TypingPurpose.RELEARN -> {
                val dueRelearn = progress.filter {
                    it.category == "relearn" && MainlineScheduler.isDueOnOrBefore(it.dueAt, System.currentTimeMillis())
                }.map { it.wordId }.toSet()
                plan.consolidationWords.filter { it.id in dueRelearn }
            }
            TypingPurpose.MAINLINE_REVIEW -> plan.reviewWords
            TypingPurpose.AUTONOMOUS_REVIEW -> {
                val scopedIds = MainlineScheduler.scopedWords(words, settings).mapTo(hashSetOf()) { it.id }
                val byId = words.associateBy { it.id }
                progress.filter {
                    it.wordId in scopedIds && it.mainlineClassifiedDate.isNotBlank() && it.category in setOf("error", "weak")
                }.sortedByDescending { it.wrongCount }.mapNotNull { byId[it.wordId] }
            }
        }
        return if (purpose == TypingPurpose.MAINLINE_LEARN || purpose == TypingPurpose.MAINLINE_CONSOLIDATE || purpose == TypingPurpose.RELEARN) selected
        else MainlineScheduler.expandReviewRounds(selected, progress)
    }

    /** 今天计划时长之外，再取一批还没学过的新词。 */
    suspend fun moreLearnWords(settings: UserSettings, excludeIds: Set<Long>): List<WordEntity> =
        MainlineScheduler.extraLearnWords(
            getScopedWords(settings),
            progressDao.getAll(),
            settings,
            excludeIds,
            pace = currentPace(),
        )

    suspend fun getDualTestWords(settings: UserSettings, kind: MainlineTestKind): List<WordEntity> {
        val plan = MainlineScheduler.plan(getScopedWords(settings), progressDao.getAll(), settings, pace = currentPace())
        return when (kind) {
            MainlineTestKind.REMEDY -> plan.remedyTestWords
            MainlineTestKind.CONSOLIDATION -> plan.consolidationWords
            MainlineTestKind.NEW_WORDS -> plan.pendingTestWords
        }
    }

    // 复习页当前列表的练习词，顺序与页面上看到的一致。不改主线排期。
    suspend fun reviewListWords(settings: UserSettings, scope: String): List<WordEntity> {
        val byId = getScopedWords(settings).associateBy { it.id }
        val progress = progressDao.getAll().filter { it.wordId in byId }
        fun wordsOf(rows: List<WordProgressEntity>) = rows.mapNotNull { byId[it.wordId] }
        return when (scope) {
            "mastered" -> wordsOf(progress.filter { it.category == "mastered" }.sortedByDescending { it.lastReviewedAt })
            "fixed" -> wordsOf(progress.filter { it.category == "fixed" }.sortedByDescending { it.fixedAt })
            "favorite" -> wordsOf(progress.filter { it.isFavorite }.sortedBy { byId[it.wordId]?.word.orEmpty() })
            "pending" -> {
                val forgotten = wordsOf(progress.filter { it.category == "relearn" }.sortedBy { it.dueAt })
                val pending = wordsOf(
                    progress.filter { it.mainlineClassifiedDate.isNotBlank() && it.category in setOf("error", "weak") }
                        .sortedByDescending { it.wrongCount },
                )
                (forgotten + pending).distinctBy { it.id }
            }
            else -> emptyList()
        }
    }

    private suspend fun getScopedWords(settings: UserSettings): List<WordEntity> =
        settings.selectedBookIds.sorted().flatMap { wordDao.getByBook(it) }
            .filter { MainlineScheduler.matchesUnitScope(it, settings.selectedUnitKeys) }

    suspend fun practiceSnapshots(wordIds: Set<Long>): Map<Long, PracticeSnapshot> {
        if (wordIds.isEmpty()) return emptyMap()
        val rows = progressDao.getAll().filter { it.wordId in wordIds }.associateBy { it.wordId }
        return wordIds.associateWith { id ->
            val row = rows[id]
            if (row == null) {
                PracticeSnapshot()
            } else {
                PracticeSnapshot(
                    level = row.level,
                    attempts = row.attempts,
                    learned = row.mainlineLearnedDate.isNotBlank(),
                    formName = row.practiceForm,
                    hits = row.practiceHits,
                    misses = row.practiceMisses,
                    hinted = row.practiceHinted,
                    firstResult = row.firstResult,
                    meaning = row.abilityMeaning,
                    listening = row.abilityListening,
                    spelling = row.abilitySpelling,
                    context = row.abilityContext,
                    skillMemory = row.skillMemory,
                    plan = row.practicePlan,
                )
            }
        }
    }

    /** 中断时保留这词已经写对、写错和有没有求助。不改到期日，也不当成遗忘。比这次保存更新的写入不会被盖掉。 */
    suspend fun savePracticeSession(session: PracticeSession, requestedAt: Long = System.currentTimeMillis()) = db.withTransaction {
        val current = progressDao.getByWordId(session.wordId) ?: WordProgressEntity(wordId = session.wordId)
        if (current.practiceSavedAt > requestedAt) return@withTransaction
        val now = System.currentTimeMillis()
        val keepLevel = current.attempts > 0 || current.mainlineLearnedDate.isNotBlank()
        progressDao.upsert(
            current.copy(
                level = if (keepLevel) current.level else formRung(session.formName),
                practicePlan = session.plan,
                practiceSavedAt = requestedAt,
                practiceHits = session.hits,
                practiceMisses = session.misses,
                practiceHinted = session.hinted,
                practiceForm = session.formName,
                firstResult = session.firstResult,
                abilityMeaning = session.meaning,
                abilityListening = session.listening,
                abilitySpelling = session.spelling,
                abilityContext = session.context,
                updatedAt = now,
            ),
        )
    }

    suspend fun recordTypingCompletion(
        wordId: Long,
        purpose: TypingPurpose,
        shaky: Boolean = false,
        meaning: Int = 0,
        listening: Int = 0,
        spelling: Int = 0,
        context: Int = 0,
        logSummary: Boolean = true,
    ) = db.withTransaction {
        val now = System.currentTimeMillis()
        val today = MainlineScheduler.dateKey(now)
        val current = progressDao.getByWordId(wordId) ?: WordProgressEntity(wordId = wordId)
        var next = MainlineScheduler.nextProgress(current, correct = !shaky, now = now, countWrong = shaky)
        if (purpose == TypingPurpose.MAINLINE_LEARN) {
            next = next.copy(
                mainlineDualPending = true,
                mainlineLearnedDate = today,
                mainlineDualDeferredDate = "",
                // 4：练的时候错过或求助过。5：一次没错过。两种都在第二天再默写，还不等于长期记住。
                level = if (shaky) 4 else 5,
                abilityMeaning = meaning,
                abilityListening = listening,
                abilitySpelling = spelling,
                abilityContext = context,
                practiceForm = "",
                practicePlan = "",
                practiceSavedAt = now,
                practiceHits = 0,
                practiceMisses = 0,
                practiceHinted = false,
                firstResult = "",
            )
        }
        if (current.skillMemory.isNotBlank()) {
            next = SkillMemory.aggregate(next, SkillMemory.finishPractice(activeSkillStates(current), now), now)
                .copy(category = next.category, isMastered = next.isMastered)
        }
        next = next.copy(answerExposedAt = maxOf(current.answerExposedAt, now))
        progressDao.upsert(next)
        if (!logSummary) return@withTransaction
        logAnswer(
            wordId = wordId,
            correct = !shaky,
            type = QuestionType.SPELLING,
            mainline = purpose != TypingPurpose.AUTONOMOUS_REVIEW,
            firstAttempt = !shaky,
            practiceForm = if (shaky) "SHAKY" else "CLEAN",
            scheduleGrade = RetentionGrade.PRACTICE.name,
            progressState = next,
        )
    }

    /**
     * 巩固的下次间隔只看第一次独立回忆。
     * [ReviewOutcome.SHAKY] 和 [ReviewOutcome.FAILED] 都进入明天的重学，不再连跳或只降一档。
     * [nextRung] 不再决定隔多少天，只留着兼容旧调用。
     */
    suspend fun recordAdaptiveReview(wordId: Long, outcome: ReviewOutcome, nextRung: Int) {
        val grade = when (outcome) {
            ReviewOutcome.CLEAN -> RetentionGrade.GOOD
            ReviewOutcome.SHAKY, ReviewOutcome.FAILED -> RetentionGrade.LAPSE
        }
        recordRetention(wordId, grade, practiceForm = "rung-$nextRung")
    }

    private suspend fun currentPace(): StudyPace = MainlineScheduler.paceFromSessions(sessionDao.getAll())

    /** Preview uses the very same planner as the next review, at its currently scheduled date. */
    suspend fun nextSkillReviewDescription(wordId: Long): String {
        val progress = progressDao.getByWordId(wordId) ?: return ""
        if (progress.skillMemory.isBlank()) return ""
        val states = activeSkillStates(progress)
        val now = System.currentTimeMillis()
        val at = maxOf(now, progress.dueAt)
        val plan = SkillPracticePlan.create(states, review = true, now = at)
        val steps = plan.targets.joinToString(" → ") { "${it.skill.label}${it.required}次" }
        val days = MainlineScheduler.daysUntil(now, progress.dueAt).coerceAtLeast(0)
        return "下次复习：$steps（无提示练习次数），${if (days == 0) "尚未完成，可继续" else "隔${days}天"}。"
    }

    /** 返回下次再隔的天数，方便界面直接显示。 */
    suspend fun recordRetention(
        wordId: Long,
        grade: RetentionGrade,
        meaning: Int? = null,
        listening: Int? = null,
        spelling: Int? = null,
        context: Int? = null,
        practiceForm: String = "",
        firstAttempt: Boolean = true,
        hinted: Boolean = false,
        independentEvidence: Boolean = firstAttempt && !hinted,
    ): Int = db.withTransaction {
        if (grade == RetentionGrade.PRACTICE) return@withTransaction 1
        val now = System.currentTimeMillis()
        val current = progressDao.getByWordId(wordId) ?: return@withTransaction 1
        val profile = db.retentionProfileDao().get() ?: RetentionProfile()
        val parameters = if (profile.isValid()) profile.parameters() else RetentionParameters()
        val before = MainlineScheduler.seedMemory(current)
        val next = if (current.skillMemory.isNotBlank()) {
            val states = SkillMemory.finishPractice(activeSkillStates(current), now)
            SkillMemory.aggregate(current, states, now).copy(
                practiceForm = "", practicePlan = "", practiceSavedAt = now,
                practiceHits = 0, practiceMisses = 0, practiceHinted = false, firstResult = "",
                mainlineConsolidationDate = MainlineScheduler.dateKey(now),
                lastReviewedAt = now, updatedAt = now,
            )
        } else MainlineScheduler.applyRetention(
            current, grade, now,
            meaning = meaning ?: current.abilityMeaning,
            listening = listening ?: current.abilityListening,
            spelling = spelling ?: current.abilitySpelling,
            context = context ?: current.abilityContext,
            parameters = parameters,
        )
        progressDao.upsert(next)
        logDao.insert(ReviewLogEntity(
            wordId = wordId, timestamp = now, correct = grade == RetentionGrade.GOOD,
            questionType = questionTypeForForm(practiceForm).name, isMainline = true,
            firstAttempt = firstAttempt, hinted = hinted, practiceForm = practiceForm,
            eventKind = if (current.skillMemory.isBlank()) "schedule" else "summary", scheduleGrade = grade.name,
            memoryAlgorithm = RetentionParameters.ALGORITHM,
            memoryBeforeStability = before.stability, memoryBeforeDifficulty = before.difficulty,
            memoryPreviousReviewAt = current.lastReviewedAt,
            memoryIndependent = independentEvidence && grade in setOf(RetentionGrade.GOOD, RetentionGrade.LAPSE),
            memoryModelRevision = profile.revision,
        ))
        enqueueEvent(eventType = questionTypeForForm(practiceForm).name, wordId = wordId,
            correct = grade == RetentionGrade.GOOD, favorite = null, mainline = true, progressState = next)
        MainlineScheduler.daysUntil(now, next.dueAt)
    }

    suspend fun logRetrieval(
        wordId: Long,
        correct: Boolean,
        type: QuestionType,
        form: String,
        firstAttempt: Boolean,
        hinted: Boolean,
        eventKind: String,
        mainline: Boolean,
        source: MemorySource = MemorySource.REVIEW,
        exposedBeforeQuestion: Boolean? = null,
    ) = db.withTransaction {
        val exposed = exposedBeforeQuestion ?: SkillMemory.exposedOnDay(
            progressDao.getByWordId(wordId)?.answerExposedAt ?: 0, System.currentTimeMillis())
        if (eventKind == "answer") {
            MemorySkill.forForm(form)?.let { recordSkillEvidence(wordId, it, correct, firstAttempt, hinted, source, form, exposed = exposed) }
        }
        logAnswer(
            wordId = wordId,
            correct = correct,
            type = type,
            mainline = mainline,
            firstAttempt = firstAttempt,
            hinted = hinted,
            practiceForm = form,
            eventKind = eventKind,
            memoryExposed = exposed,
        )
        if (eventKind == "answer" || eventKind == "hint") noteAnswerExposure(wordId)
    }

    private suspend fun activeSkillStates(progress: WordProgressEntity): Map<MemorySkill, SkillState> {
        val word = wordDao.getById(progress.wordId)
        return SkillMemory.states(progress, word != null && LearningContentPresentation.clozeFor(context, word) != null)
    }

    data class RetrievalAccess(val exposed: Boolean, val preparedAt: Long)

    /** Capture eligibility before the cue/result is shown; one unexposed probe per word/calendar day. */
    suspend fun prepareRetrieval(wordId: Long, form: String): RetrievalAccess = db.withTransaction {
        val now = System.currentTimeMillis()
        val current = progressDao.getByWordId(wordId) ?: WordProgressEntity(wordId)
        val exposed = form == "STUDY" || SkillMemory.formReliability(form) == 0.0 ||
            SkillMemory.exposedOnDay(current.answerExposedAt, now)
        progressDao.upsert(current.copy(answerExposedAt = maxOf(current.answerExposedAt, now)))
        RetrievalAccess(exposed, now)
    }

    suspend fun noteAnswerExposure(wordId: Long) = db.withTransaction {
        val current = progressDao.getByWordId(wordId) ?: WordProgressEntity(wordId)
        progressDao.upsert(current.copy(answerExposedAt = maxOf(current.answerExposedAt, System.currentTimeMillis())))
    }

    private suspend fun recordSkillEvidence(
        wordId: Long, skill: MemorySkill, correct: Boolean, first: Boolean, hinted: Boolean,
        source: MemorySource, form: String, finalizeWord: Boolean = false,
        exposed: Boolean = false, now: Long = System.currentTimeMillis(),
    ) {
        val current = progressDao.getByWordId(wordId) ?: WordProgressEntity(wordId = wordId)
        val word = wordDao.getById(wordId) ?: return
        val states = SkillMemory.states(current, LearningContentPresentation.clozeFor(context, word) != null).toMutableMap()
        val before = states[skill] ?: return
        val profile = db.retentionProfileDao().get() ?: RetentionProfile()
        val parameters = if (profile.isValid()) profile.parameters() else RetentionParameters()
        val update = SkillMemory.update(before, correct, first, hinted, source, form, now, parameters, exposed)
        states[skill] = update.after
        var next = SkillMemory.aggregate(current, states, now).copy(updatedAt = now)
        if (finalizeWord) {
            val completedAt = System.currentTimeMillis()
            next = SkillMemory.aggregate(next, SkillMemory.finishPractice(states, completedAt), completedAt)
        }
        // During first learning, keep the word in its current mainline stage until completion/dual test.
        if (source in setOf(MemorySource.LEARN, MemorySource.REVIEW, MemorySource.REPAIR) ||
            (source in setOf(MemorySource.TEST, MemorySource.NEW_TEST) && !finalizeWord)) {
            next = next.copy(category = current.category, isMastered = current.isMastered, dueAt = current.dueAt)
        }
        val mark = if (hinted || !correct) 1 else if (first && SkillMemory.formReliability(form) > 0) 2 else SkillMemory.mark(current, skill)
        next = when (skill) {
            MemorySkill.MEANING -> next.copy(abilityMeaning = mark)
            MemorySkill.LISTENING -> next.copy(abilityListening = mark)
            MemorySkill.SPELLING -> next.copy(abilitySpelling = mark)
            MemorySkill.CONTEXT -> next.copy(abilityContext = mark)
        }
        progressDao.upsert(next)
        logDao.insert(ReviewLogEntity(
            wordId = wordId, timestamp = now, correct = correct, questionType = when (skill) {
                MemorySkill.MEANING -> QuestionType.MEANING.name
                MemorySkill.LISTENING -> QuestionType.LISTENING.name
                else -> QuestionType.SPELLING.name
            }, isMainline = true, isTest = source in setOf(MemorySource.TEST, MemorySource.NEW_TEST),
            firstAttempt = first, hinted = hinted, practiceForm = form,
            eventKind = "schedule", scheduleGrade = if (hinted) "RECHECK" else if (correct) "GOOD" else "LAPSE",
            memoryAlgorithm = SkillMemory.ALGORITHM, memorySkill = skill.name, memoryWeight = update.weight,
            memorySource = source.name, memoryBeforeStability = before.stability, memoryBeforeDifficulty = before.difficulty,
            memoryPreviousReviewAt = before.lastAt, memoryIndependent = update.independent,
            memoryModelRevision = profile.revision,
            memoryExposed = exposed,
        ))
    }

    suspend fun confirmMainlineReview(wordIds: Collection<Long>) {
        val today = MainlineScheduler.dateKey()
        wordIds.distinct().forEach { wordId ->
            val current = progressDao.getByWordId(wordId) ?: return@forEach
            if (current.mainlineRemedyReviewDate == today) return@forEach
            val next = current.copy(mainlineRemedyReviewDate = today, updatedAt = System.currentTimeMillis())
            progressDao.upsert(next)
            logAnswer(
                wordId = wordId,
                correct = true,
                type = QuestionType.CONFIDENCE,
                mainline = true,
                progressState = next,
            )
        }
    }

    suspend fun recordDualAnswer(
        wordId: Long,
        correct: Boolean,
        type: QuestionType,
        listenPass: Boolean?,
        chinesePass: Boolean?,
        kind: MainlineTestKind,
        spellingQuality: String,
        exposedBeforeQuestion: Boolean? = null,
        answeredAt: Long = System.currentTimeMillis(),
    ) = db.withTransaction {
        require(type in setOf(QuestionType.LISTENING, QuestionType.MEANING, QuestionType.SPELLING))
        val now = answeredAt
        require(now > 0 && now <= System.currentTimeMillis())
        val completedAt = System.currentTimeMillis()
        val today = MainlineScheduler.dateKey(completedAt)
        val current = progressDao.getByWordId(wordId) ?: WordProgressEntity(wordId = wordId)
        val exposed = exposedBeforeQuestion ?: SkillMemory.exposedOnDay(current.answerExposedAt, now)
        var next = MainlineScheduler.nextProgress(current, correct = correct, now = now, countWrong = true)
            .copy(
                lastListenPass = listenPass ?: current.lastListenPass,
                lastChinesePass = chinesePass ?: current.lastChinesePass,
            )
        if (listenPass != null && chinesePass != null) {
            next = MainlineScheduler.classifyDual(next, listenPass, chinesePass, completedAt).copy(
                mainlineClassifiedDate = today,
                mainlineRemedyTestDate = if (kind == MainlineTestKind.REMEDY) today else next.mainlineRemedyTestDate,
                mainlineDualDeferredDate = "",
            )
        }
        progressDao.upsert(next)
        recordSkillEvidence(wordId, if (type == QuestionType.LISTENING) MemorySkill.LISTENING else MemorySkill.SPELLING,
            correct, first = true, hinted = false,
            source = if (kind == MainlineTestKind.NEW_WORDS) MemorySource.NEW_TEST else MemorySource.TEST,
            form = if (type == QuestionType.LISTENING) "LISTEN" else "RECALL",
            finalizeWord = listenPass != null && chinesePass != null, exposed = exposed, now = now)
        logAnswer(
            wordId = wordId,
            correct = correct,
            type = if (type == QuestionType.MEANING) QuestionType.SPELLING else type,
            dual = true,
            mainline = true,
            test = true,
            spellingQuality = spellingQuality,
            progressState = progressDao.getByWordId(wordId),
            firstAttempt = true,
            practiceForm = if (type == QuestionType.LISTENING) "LISTEN" else "RECALL",
            memoryExposed = exposed,
            timestamp = now,
        )
        noteAnswerExposure(wordId)
    }

    suspend fun phoneticFor(word: WordEntity): Pair<String, String> {
        if (word.phoneticUs.isNotBlank() || word.phoneticUk.isNotBlank()) return word.phoneticUs to word.phoneticUk
        val found = wordDao.findPhonetic(word.word) ?: return "" to ""
        return found.phoneticUs to found.phoneticUk
    }

    suspend fun getOptions(word: WordEntity, count: Int = 4): List<WordEntity> {
        val alternatives = wordDao.getByBook(word.bookId).filter { it.id != word.id }.shuffled().take(count - 1)
        return (alternatives + word).shuffled()
    }

    suspend fun logAnswer(
        wordId: Long,
        correct: Boolean,
        type: QuestionType,
        dual: Boolean = false,
        mainline: Boolean = false,
        test: Boolean = false,
        spellingQuality: String? = null,
        progressState: WordProgressEntity? = null,
        firstAttempt: Boolean = false,
        hinted: Boolean = false,
        practiceForm: String = "",
        eventKind: String = "answer",
        scheduleGrade: String = "",
        memoryExposed: Boolean = false,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        logDao.insert(
            ReviewLogEntity(
                wordId = wordId,
                timestamp = timestamp,
                correct = correct,
                questionType = type.name,
                isDual = dual,
                isMainline = mainline,
                isTest = test,
                firstAttempt = firstAttempt,
                hinted = hinted,
                practiceForm = practiceForm,
                eventKind = eventKind,
                scheduleGrade = scheduleGrade,
                memoryExposed = memoryExposed,
            ),
        )
        enqueueEvent(
            eventType = type.name,
            wordId = wordId,
            correct = if (type == QuestionType.CONFIDENCE) null else correct,
            favorite = null,
            dual = dual,
            mainline = mainline,
            test = test,
            spellingQuality = spellingQuality,
            progressState = progressState,
        )
    }

    suspend fun completeWord(wordId: Long, correct: Boolean, graduate: Boolean = false) {
        val now = System.currentTimeMillis()
        val current = progressDao.getByWordId(wordId) ?: WordProgressEntity(wordId = wordId)
        val next = if (graduate) {
            ReviewScheduler.next(current, correct, now).copy(isMastered = true)
        } else {
            ReviewScheduler.next(current, correct, now)
        }
        progressDao.upsert(next.copy(updatedAt = now))
    }

    suspend fun toggleFavorite(wordId: Long) {
        val current = progressDao.getByWordId(wordId) ?: WordProgressEntity(wordId = wordId)
        val favorite = !current.isFavorite
        progressDao.upsert(current.copy(isFavorite = favorite, updatedAt = System.currentTimeMillis()))
        enqueueEvent(eventType = "FAVORITE", wordId = wordId, correct = null, favorite = favorite, progressState = null)
    }

    suspend fun progressFor(wordId: Long): WordProgressEntity? = progressDao.getByWordId(wordId)

    private suspend fun enqueueEvent(
        eventType: String,
        wordId: Long,
        correct: Boolean?,
        favorite: Boolean?,
        dual: Boolean = false,
        mainline: Boolean = false,
        test: Boolean = false,
        spellingQuality: String? = null,
        progressState: WordProgressEntity?,
    ) {
        if (BuildConfig.LOCAL_ONLY) return
        val client = api ?: return
        val event = PendingSyncEventEntity(
            eventId = UUID.randomUUID().toString(),
            deviceId = client.deviceId,
            wordId = wordId,
            eventType = eventType,
            correct = correct,
            favorite = favorite,
            clientTime = Instant.now().toString(),
            clientSeq = client.nextClientSeq(),
            isDual = dual,
            isMainline = mainline,
            isTest = test,
            spellingQuality = spellingQuality,
            progressStateJson = progressState?.toProgressStateJson()?.toString(),
        )
        pendingSyncDao.insert(event)
        // 立即上传供其他设备查看，但保留本地确认项；待新服务端快照落库后再原子清理。
        runCatching { client.uploadPendingEvents(listOf(event)) }
    }

    data class SyncSummary(
        val progressMerged: Int = 0,
        val settingsApplied: Int = 0,
        val eventsFlushed: Int = 0,
        val sessionsFlushed: Int = 0,
        val ok: Boolean = false,
    )

    /**
     * 同一安装切换真实账号时隔离本地学习记录。
     * 联网账号改绑到本机用户时，记录本来就在这台设备上，只改绑定，不能走清空分支。
     */
    suspend fun prepareForUser(userId: Long): Boolean = syncMutex.withLock {
        val previousUserId = accountScope.getLong("bound_user_id", 0L)
        if (previousUserId == 0L) {
            accountScope.edit().putLong("bound_user_id", userId).apply()
            return@withLock false
        }
        if (previousUserId == userId) return@withLock false
        if (BuildConfig.LOCAL_ONLY && userId == LOCAL_USER_ID) {
            accountScope.edit().putLong("bound_user_id", userId).apply()
            return@withLock false
        }
        db.withTransaction {
            progressDao.clear()
            logDao.clear()
            sessionDao.clear()
            pendingSyncDao.clear()
            serverStatsDao.clear()
            db.retentionProfileDao().clear()
        }
        preferences?.resetCloudSettingsForAccount()
        accountScope.edit().putLong("bound_user_id", userId).apply()
        true
    }

    suspend fun currentMainlinePlan(): MainlinePlan {
        val settings = preferences?.snapshot() ?: UserSettings()
        return MainlineScheduler.plan(getScopedWords(settings), progressDao.getAll(), settings, pace = currentPace())
    }

    /** 双向同步：上传本地确认项，拉取包含它们的新快照，然后原子更新统计与队列状态。 */
    suspend fun syncNow(): SyncSummary = syncMutex.withLock { syncNowLocked() }

    private suspend fun syncNowLocked(): SyncSummary {
        if (BuildConfig.LOCAL_ONLY) return SyncSummary(ok = true)
        val api = api ?: return SyncSummary()
        val eventBatch = pendingSyncDao.loadBatch(500)
        val eventsUploaded = eventBatch.isNotEmpty() && runCatching { api.uploadPendingEvents(eventBatch) }.getOrDefault(false)
        val sessionBatch = sessionDao.loadUnsynced(200).map { session ->
            if (session.sessionId.isBlank()) {
                val sessionId = UUID.randomUUID().toString()
                sessionDao.updateSessionId(session.id, sessionId)
                session.copy(sessionId = sessionId)
            } else session
        }
        val sessionsUploaded = sessionBatch.isNotEmpty() && runCatching {
            api.uploadPendingSessions(sessionBatch.map { it.toServerUpload(api.deviceId) })
        }.getOrDefault(false)
        val eventsFlushed = if (eventsUploaded) eventBatch.size else 0
        val sessionsFlushed = if (sessionsUploaded) sessionBatch.size else 0
        val snapshot = runCatching { api.fetchFullSnapshot() }.getOrNull()
            ?: return SyncSummary(eventsFlushed = eventsFlushed, sessionsFlushed = sessionsFlushed)
        var progressMerged = 0
        var settingsApplied = 0

        val localIds = wordDao.getIds()
        val localByWord = progressDao.getAll().associateBy { it.wordId }
        val toUpsert = snapshot.progress.mapNotNull { row ->
            if (row.wordId !in localIds) return@mapNotNull null
            val local = localByWord[row.wordId]
            WordProgressEntity(
                wordId = row.wordId,
                familiarity = row.familiarity,
                streak = row.streak,
                wrongCount = row.wrongCount,
                attempts = row.attempts,
                correctCount = row.correctCount,
                lastReviewedAt = row.lastReviewedAt,
                dueAt = row.dueAt,
                isMastered = row.isMastered,
                isFavorite = row.isFavorite,
                updatedAt = row.updatedAt,
                category = row.category,
                level = row.level,
                passStreak = row.passStreak,
                lastListenPass = row.lastListenPass,
                lastChinesePass = row.lastChinesePass,
                lastSpotCheckAt = row.lastSpotCheckAt,
                masteryStreak = row.masteryStreak,
                fixedAt = row.fixedAt,
                mainlineDualPending = row.mainlineDualPending,
                mainlineClassifiedDate = row.mainlineClassifiedDate,
                mainlineRemedyReviewDate = row.mainlineRemedyReviewDate,
                mainlineRemedyTestDate = row.mainlineRemedyTestDate,
                mainlineLearnedDate = row.mainlineLearnedDate,
                mainlineDualDeferredDate = row.mainlineDualDeferredDate,
                mainlineConsolidationDate = local?.mainlineConsolidationDate.orEmpty(),
                stabilityDays = local?.stabilityDays ?: 0.0,
                difficulty = local?.difficulty ?: 0.0,
                practiceHits = local?.practiceHits ?: 0,
                practiceMisses = local?.practiceMisses ?: 0,
                practiceHinted = local?.practiceHinted == true,
                practiceForm = local?.practiceForm.orEmpty(),
                firstResult = local?.firstResult.orEmpty(),
                abilityMeaning = local?.abilityMeaning ?: 0,
                abilityListening = local?.abilityListening ?: 0,
                abilitySpelling = local?.abilitySpelling ?: 0,
                abilityContext = local?.abilityContext ?: 0,
            )
        }.filter { row ->
            val local = localByWord[row.wordId]
            local == null || row.updatedAt >= local.updatedAt
        }
        if (toUpsert.isNotEmpty()) {
            progressDao.upsertAll(toUpsert)
            progressMerged = toUpsert.size
        }

        settingsApplied = applyRemoteSettings(snapshot.settings)

        db.withTransaction {
            if (!snapshot.statsJson.isNullOrBlank()) {
                serverStatsDao.clear()
                serverStatsDao.upsert(ServerStatsEntity(key = "remote_stats", payloadJson = snapshot.statsJson))
            }
            if (eventsUploaded) pendingSyncDao.deleteByIds(eventBatch.map { it.eventId })
            if (sessionsUploaded) sessionDao.markSynced(sessionBatch.map { it.id })
        }

        pushLocalSettings(snapshot.settings)

        return SyncSummary(progressMerged, settingsApplied, eventsFlushed, sessionsFlushed, ok = true)
    }

    private suspend fun applyRemoteSettings(remote: List<ServerSetting>): Int {
        val preferences = preferences ?: return 0
        var applied = 0
        remote.forEach { setting ->
            if (setting.key !in SYNCHED_SETTING_KEYS) return@forEach
            val localWriteAt = preferences.localWriteTime(setting.key)
            if (setting.clientUpdatedAt > 0 && setting.clientUpdatedAt > localWriteAt) {
                if (preferences.applyServerSetting(setting.key, setting.value)) applied += 1
            }
        }
        return applied
    }

    private suspend fun pushLocalSettings(remote: List<ServerSetting>) {
        val preferences = preferences ?: return
        val api = api ?: return
        val remoteByKey = remote.associateBy { it.key }
        val updates = SYNCHED_SETTING_KEYS.mapNotNull { key ->
            val remoteRow = remoteByKey[key]
            val localWriteAt = preferences.localWriteTime(key)
            if (localWriteAt > 0 && (remoteRow == null || localWriteAt > remoteRow.clientUpdatedAt)) {
                ServerSettingUpdate(key, preferences.serverValue(key), localWriteAt)
            } else null
        }
        if (updates.isNotEmpty()) runCatching { api.pushSettings(updates) }
    }

    private companion object {
        const val LOCAL_USER_ID = 1L
        val SITCOM_BOOK_IDS = setOf(
            "sitcom-listening",
            "friends-daily",
            "big-bang-daily",
            "the-office-daily",
            "modern-family-daily",
        )
        val SYNCHED_SETTING_KEYS = listOf(
            "currentBookId",
            "selectedBookIds",
            "selectedUnitKeys",
            "dailyGoal",
            "autoSpeak",
            "shuffle",
            "darkMode",
            "accent",
            "studySpeechRate",
            "reminderEnabled",
            "reminderHour",
        )
    }

    private fun canonicalSessionMode(mode: String): String = when (mode) {
        "MAINLINE_LEARN" -> "LEARN"
        "MAINLINE_REVIEW", "AUTONOMOUS_REVIEW", "CONSOLIDATE" -> "REVIEW"
        "MAINLINE_REMEDY", "MAINLINE_CONSOLIDATION", "MAINLINE_NEW_WORDS" -> "TEST"
        else -> mode
    }

    suspend fun retentionProfile(): RetentionProfile = db.retentionProfileDao().get() ?: RetentionProfile()

    data class BackupSnapshot(val progress: List<WordProgressEntity>, val logs: List<ReviewLogEntity>,
        val sessions: List<StudySessionEntity>, val profile: RetentionProfile,
        val dualTests: List<DualTestSessionEntity> = emptyList())

    suspend fun backupSnapshot(): BackupSnapshot = db.withTransaction {
        BackupSnapshot(progressDao.getAll(), logDao.getAll(), sessionDao.getAll(), retentionProfile(), db.dualTestSessionDao().getAll())
    }

    suspend fun allProgress(): List<WordProgressEntity> = progressDao.getAll()
    suspend fun allLogs(): List<ReviewLogEntity> = logDao.getAll()
    suspend fun allSessions(): List<StudySessionEntity> = sessionDao.getAll()
    suspend fun allWordIds(): Set<Long> = wordDao.getIds().toHashSet()
    suspend fun allBookIds(): Set<String> = bookDao.getAll().mapTo(hashSetOf()) { it.id }
    fun dictionaryVersion(): Int = assetLoader.version

    suspend fun wordIdentities(ids: Collection<Long>): Map<Long, WordEntity> {
        if (ids.isEmpty()) return emptyMap()
        val map = HashMap<Long, WordEntity>(ids.size)
        ids.distinct().chunked(400).forEach { chunk ->
            wordDao.getByIds(chunk).forEach { map[it.id] = it }
        }
        return map
    }

    suspend fun findWordId(bookId: String, spelling: String): Long? {
        if (bookId.isBlank() || spelling.isBlank()) return null
        val ids = wordDao.findIdsByBookAndSpelling(bookId, spelling)
        require(ids.size <= 1) { "词书中有多个同名词，无法安全匹配：$spelling" }
        return ids.singleOrNull()
    }

    /**
     * [sessions] 为 null 时不动学习会话。传入列表（包括空列表）则整体替换，避免和恢复后的进度混在一起。
     */
    suspend fun restore(
        progressItems: List<WordProgressEntity>,
        logs: List<ReviewLogEntity>,
        sessions: List<StudySessionEntity>? = null,
        retentionProfile: RetentionProfile = RetentionProfile(),
        dualTests: List<DualTestSessionEntity> = emptyList(),
    ) {
        require(retentionProfile.isValid()) { "复习参数无效" }
        db.withTransaction {
            db.retentionProfileDao().save(retentionProfile.copy(generation = UUID.randomUUID().toString()))
            progressDao.clear()
            logDao.clear()
            progressDao.upsertAll(progressItems)
            logDao.insertAll(logs.map { it.copy(id = 0) })
            db.dualTestSessionDao().clear()
            dualTests.forEach { item ->
                // Invalidate writers belonging to a pre-restore ViewModel, while preserving the question/results.
                db.dualTestSessionDao().save(item.checkpoint().copy(sessionId = UUID.randomUUID().toString()).entity())
            }
            if (sessions != null) {
                sessionDao.clear()
                sessions.forEach { session ->
                    sessionDao.insert(session.copy(id = 0, synced = false))
                }
            }
        }
    }

    private fun formRung(formName: String): Int = when (formName) {
        "PICK_MEANING" -> 1
        "PICK_SPELLING" -> 2
        "FADED" -> 3
        "RECALL" -> 4
        "LISTEN" -> 5
        "CLOZE" -> 6
        else -> 0
    }

    private fun questionTypeForForm(formName: String): QuestionType = when (formName) {
        "PICK_MEANING" -> QuestionType.MEANING
        "LISTEN" -> QuestionType.LISTENING
        else -> QuestionType.SPELLING
    }

    private fun WordProgressEntity.toProgressStateJson(): JSONObject = JSONObject()
        .put("familiarity", familiarity)
        .put("streak", streak)
        .put("wrongCount", wrongCount)
        .put("attempts", attempts)
        .put("correctCount", correctCount)
        .put("lastReviewedAt", lastReviewedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() })
        .put("dueAt", dueAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() })
        .put("isMastered", isMastered)
        .put("category", category)
        .put("level", level)
        .put("passStreak", passStreak)
        .put("lastListenPass", lastListenPass)
        .put("lastChinesePass", lastChinesePass)
        .put("lastSpotCheckAt", lastSpotCheckAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() })
        .put("masteryStreak", masteryStreak)
        .put("fixedAt", fixedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).toString() })
        .put("mainlineDualPending", mainlineDualPending)
        .put("mainlineClassifiedDate", mainlineClassifiedDate)
        .put("mainlineRemedyReviewDate", mainlineRemedyReviewDate)
        .put("mainlineRemedyTestDate", mainlineRemedyTestDate)
        .put("mainlineLearnedDate", mainlineLearnedDate)
        .put("mainlineDualDeferredDate", mainlineDualDeferredDate)
        .put("mainlineConsolidationDate", mainlineConsolidationDate)
        .put("stabilityDays", stabilityDays)
        .put("difficulty", difficulty)
        .put("practiceForm", practiceForm)
        .put("firstResult", firstResult)
        .put("abilityMeaning", abilityMeaning)
        .put("abilityListening", abilityListening)
        .put("abilitySpelling", abilitySpelling)
        .put("abilityContext", abilityContext)

    private fun ReviewLogEntity.localDate(): LocalDate = Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()

    private fun List<ReviewLogEntity>.accuracy(): Int =
        if (isEmpty()) 0 else (count { it.correct } * 100 / size)

    private fun calculateStreak(activeDays: Set<LocalDate>): Int {
        var day = LocalDate.now()
        if (day !in activeDays && day.minusDays(1) in activeDays) day = day.minusDays(1)
        var streak = 0
        while (day in activeDays) {
            streak += 1
            day = day.minusDays(1)
        }
        return streak
    }
}
