package com.alanbeidanci.app.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.data.BookProgress
import com.alanbeidanci.app.data.HomeSummary
import com.alanbeidanci.app.data.LocalWordLookup
import com.alanbeidanci.app.data.MainlinePlan
import com.alanbeidanci.app.data.MainlineScheduler
import com.alanbeidanci.app.data.QuestionType
import com.alanbeidanci.app.data.ReviewCollections
import com.alanbeidanci.app.data.StatsReport
import com.alanbeidanci.app.data.StudyMode
import com.alanbeidanci.app.data.UserSettings
import com.alanbeidanci.app.data.WordEntity
import com.alanbeidanci.app.notifications.ReminderScheduler
import com.alanbeidanci.app.network.AlanApiException
import com.alanbeidanci.app.network.DailyMainlineErrorReport
import com.alanbeidanci.app.network.RepeatRecording
import com.alanbeidanci.app.network.WordLookupItem
import com.alanbeidanci.app.network.WordLookupResponse
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AuthMode { LOGIN, REGISTER }

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val username: String = "",
    val password: String = "",
    val passwordVisible: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

class AuthViewModel(private val container: AppContainer) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState

    fun setMode(mode: AuthMode) {
        _uiState.value = _uiState.value.copy(mode = mode, password = "", error = null)
    }

    fun setUsername(value: String) {
        if (value.length <= 32) _uiState.value = _uiState.value.copy(username = value, error = null)
    }

    fun setPassword(value: String) {
        _uiState.value = _uiState.value.copy(password = value, error = null)
    }

    fun togglePasswordVisibility() {
        _uiState.value = _uiState.value.copy(passwordVisible = !_uiState.value.passwordVisible)
    }

    fun submit() {
        val state = _uiState.value
        if (!state.username.matches(Regex("[A-Za-z][A-Za-z0-9._-]{3,31}"))) {
            _uiState.value = state.copy(error = "账号需为 4–32 位，以字母开头，可使用数字、点、横线和下划线")
            return
        }
        if (state.password.isBlank()) {
            _uiState.value = state.copy(error = "请输入密码")
            return
        }
        _uiState.value = state.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching {
                val session = if (state.mode == AuthMode.LOGIN) {
                    container.api.login(state.username, state.password)
                } else {
                    container.api.register(state.username, state.password)
                }
                container.repository.prepareForUser(session.userId)
                container.repository.syncNow()
                session
            }.onSuccess {
                _uiState.value = _uiState.value.copy(password = "", loading = false)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    error = (error as? AlanApiException)?.message ?: "暂时无法登录，请稍后重试",
                )
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = simpleFactory { AuthViewModel(container) }
    }
}

data class SearchUiState(
    val query: String = "",
    val resolvedQuery: String = "",
    val results: List<WordLookupItem> = emptyList(),
    val loading: Boolean = false,
    val searched: Boolean = false,
    val aiAnswer: String = "",
    val aiLoading: Boolean = false,
    val error: String? = null,
)

class SearchViewModel(private val container: AppContainer) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState

    fun setQuery(value: String) {
        if (value.length <= 64) _uiState.value = _uiState.value.copy(query = value, error = null)
    }

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "请输入中文释义或英文单词")
            return
        }
        launchLookup { container.repository.searchLocal(query) }
    }

    fun scan(content: String) {
        val term = LocalWordLookup.scanTerm(content)
        if (term == null) {
            _uiState.value = _uiState.value.copy(
                loading = false,
                searched = true,
                results = emptyList(),
                error = "没有识别到可查询的中文或英文",
            )
            return
        }
        launchLookup { container.repository.searchLocal(term) }
    }

    private fun launchLookup(block: suspend () -> WordLookupResponse) {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { response ->
                    _uiState.value = _uiState.value.copy(
                        query = response.resolvedQuery,
                        resolvedQuery = response.resolvedQuery,
                        results = response.results,
                        loading = false,
                        searched = true,
                    )
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = error.message ?: "查询失败，请稍后重试",
                    )
                }
        }
    }

    companion object {
        fun factory(container: AppContainer) = simpleFactory { SearchViewModel(container) }
    }
}

data class HomeUiState(
    val summary: HomeSummary = HomeSummary(),
    val books: List<BookProgress> = emptyList(),
    val settings: UserSettings = UserSettings(),
    val allCovered: Boolean = false,
    val plan: MainlinePlan = MainlinePlan(),
) {
    val selectedBooks: List<BookProgress>
        get() = books.filter { it.book.id in settings.selectedBookIds }
            .ifEmpty { books.filter { it.book.id == settings.currentBookId }.ifEmpty { books.take(1) } }
    val currentBook: BookProgress? get() = selectedBooks.firstOrNull { it.book.id == settings.currentBookId } ?: selectedBooks.firstOrNull()
}

data class StatsSyncUiState(
    val isSyncing: Boolean = false,
    val lastSyncedAt: Long = 0,
    val lastSyncSucceeded: Boolean? = null,
)

class StatsViewModel(private val container: AppContainer) : ViewModel() {
    val uiState: StateFlow<StatsReport> = container.repository.observeStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsReport())

    private val _syncState = MutableStateFlow(StatsSyncUiState())
    val syncState: StateFlow<StatsSyncUiState> = _syncState

    init {
        refresh()
    }

    fun refresh() {
        if (_syncState.value.isSyncing) return
        _syncState.value = _syncState.value.copy(isSyncing = true)
        viewModelScope.launch {
            val ok = runCatching { container.repository.syncNow().ok }.getOrDefault(false)
            _syncState.value = StatsSyncUiState(
                isSyncing = false,
                lastSyncedAt = if (ok) System.currentTimeMillis() else _syncState.value.lastSyncedAt,
                lastSyncSucceeded = ok,
            )
        }
    }

    companion object {
        fun factory(container: AppContainer) = simpleFactory { StatsViewModel(container) }
    }
}

data class MainlineErrorPeriodSummary(
    val englishAnswers: Int = 0,
    val incorrectAnswers: Int = 0,
    val uniqueErrorWords: Int = 0,
) {
    val accuracyPercent: Int?
        get() = if (englishAnswers > 0) {
            ((englishAnswers - incorrectAnswers).coerceAtLeast(0) * 100.0 / englishAnswers).toInt()
        } else {
            null
        }
}

data class MainlineErrorReportUiState(
    val periodEnd: LocalDate = LocalDate.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val reports: Map<LocalDate, DailyMainlineErrorReport> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    val dates: List<LocalDate> get() = mainlineErrorPeriodDates(periodEnd)
    val selectedReport: DailyMainlineErrorReport? get() = reports[selectedDate]
    val summary: MainlineErrorPeriodSummary get() = summarizeMainlineErrors(reports.values)
    val canGoNext: Boolean get() = periodEnd.isBefore(LocalDate.now())
}

internal fun mainlineErrorPeriodDates(periodEnd: LocalDate): List<LocalDate> =
    (6 downTo 0).map { periodEnd.minusDays(it.toLong()) }

internal fun summarizeMainlineErrors(reports: Collection<DailyMainlineErrorReport>): MainlineErrorPeriodSummary =
    MainlineErrorPeriodSummary(
        englishAnswers = reports.sumOf { it.englishAnswers },
        incorrectAnswers = reports.sumOf { it.incorrectAnswers },
        uniqueErrorWords = reports.flatMap { it.words }.map { it.wordId }.distinct().size,
    )

class MainlineErrorReportViewModel(private val container: AppContainer) : ViewModel() {
    private val _uiState = MutableStateFlow(MainlineErrorReportUiState())
    val uiState: StateFlow<MainlineErrorReportUiState> = _uiState
    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun refresh() = load(_uiState.value.periodEnd)

    fun selectDate(date: LocalDate) {
        if (date in _uiState.value.dates) _uiState.value = _uiState.value.copy(selectedDate = date)
    }

    fun previousWeek() {
        val earliest = LocalDate.now().minusDays(364)
        load(maxOf(earliest, _uiState.value.periodEnd.minusDays(7)))
    }

    fun nextWeek() {
        if (!_uiState.value.canGoNext) return
        load(minOf(LocalDate.now(), _uiState.value.periodEnd.plusDays(7)))
    }

    private fun load(periodEnd: LocalDate) {
        loadJob?.cancel()
        val selected = if (periodEnd == LocalDate.now()) LocalDate.now() else periodEnd
        _uiState.value = _uiState.value.copy(
            periodEnd = periodEnd,
            selectedDate = selected,
            reports = emptyMap(),
            loading = true,
            error = null,
        )
        loadJob = viewModelScope.launch {
            runCatching { container.repository.syncNow() }
            val dates = mainlineErrorPeriodDates(periodEnd)
            val results = coroutineScope {
                dates.map { date -> async { date to runCatching { container.api.mainlineErrorReport(date) } } }.awaitAll()
            }
            if (_uiState.value.periodEnd != periodEnd) return@launch
            val reports = results.mapNotNull { (date, result) -> result.getOrNull()?.let { date to it } }.toMap()
            val firstError = results.firstNotNullOfOrNull { (_, result) -> result.exceptionOrNull() }
            _uiState.value = _uiState.value.copy(
                reports = reports,
                loading = false,
                error = if (reports.isEmpty() && firstError != null) {
                    (firstError as? AlanApiException)?.message ?: "易错报告加载失败，请稍后重试"
                } else {
                    null
                },
            )
        }
    }

    companion object {
        fun factory(container: AppContainer) = simpleFactory { MainlineErrorReportViewModel(container) }
    }
}

data class RepeatRecordingsUiState(
    val recordings: List<RepeatRecording> = emptyList(),
    val filter: String = "ALL",
    val loading: Boolean = true,
    val loadingAudioId: String? = null,
    val playingId: String? = null,
    val error: String? = null,
) {
    val visibleRecordings: List<RepeatRecording>
        get() = if (filter == "ALL") recordings else recordings.filter { it.mode == filter }
    val ratedRecordings: List<RepeatRecording>
        get() = recordings.filter { it.assessmentStatus == "READY" && (it.assessmentStars ?: 0) > 0 }
    val averageStars: String
        get() = if (ratedRecordings.isEmpty()) "—" else "%.1f".format(ratedRecordings.mapNotNull { it.assessmentStars }.average())
}

internal fun pronunciationAssessmentLabel(code: String): String = when (code) {
    "MATCHED" -> "与目标词高度一致"
    "CLOSE" -> "整体接近，可再注意音节"
    "UNCLEAR" -> "部分音节不够清楚"
    "DIFFERENT" -> "与目标词差异较大"
    "LOW_CONFIDENCE" -> "识别把握较低，建议回听"
    "NO_SPEECH" -> "没有识别到清晰语音"
    "UNSUPPORTED" -> "录音时的浏览器不支持评星"
    "RECOGNITION_FAILED" -> "语音识别未完成"
    "ANALYSIS_TIMEOUT" -> "语音分析超时"
    "INVALID_TARGET" -> "该词条暂不支持评星"
    else -> ""
}

class RepeatRecordingsViewModel(private val container: AppContainer) : ViewModel() {
    private val _uiState = MutableStateFlow(RepeatRecordingsUiState())
    val uiState: StateFlow<RepeatRecordingsUiState> = _uiState
    private var player: MediaPlayer? = null
    private var cachedAudio: File? = null
    private var playbackRequest = 0L

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { container.api.repeatRecordings() }
                .onSuccess { rows ->
                    _uiState.value = _uiState.value.copy(recordings = rows, loading = false)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = (error as? AlanApiException)?.message ?: "跟读记录加载失败，请稍后重试",
                    )
                }
        }
    }

    fun setFilter(filter: String) {
        if (filter in setOf("ALL", "LEARN", "REVIEW")) _uiState.value = _uiState.value.copy(filter = filter)
    }

    fun play(recording: RepeatRecording) {
        if (_uiState.value.playingId == recording.id) {
            stopPlayback()
            return
        }
        stopPlayback()
        val request = playbackRequest
        _uiState.value = _uiState.value.copy(loadingAudioId = recording.id, error = null)
        viewModelScope.launch {
            runCatching {
                val audio = container.api.repeatRecordingAudio(recording.id)
                if (request != playbackRequest) return@runCatching
                val suffix = when (audio.contentType) {
                    "audio/mp4" -> ".m4a"
                    "audio/ogg" -> ".ogg"
                    else -> ".webm"
                }
                val file = withContext(Dispatchers.IO) {
                    File.createTempFile("alan-repeat-", suffix, container.applicationContext.cacheDir)
                        .also { it.writeBytes(audio.bytes) }
                }
                if (request != playbackRequest) {
                    file.delete()
                    return@runCatching
                }
                cachedAudio = file
                MediaPlayer().also { mediaPlayer ->
                    player = mediaPlayer
                    mediaPlayer.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build(),
                    )
                    mediaPlayer.setDataSource(file.absolutePath)
                    mediaPlayer.setOnCompletionListener { stopPlayback() }
                    mediaPlayer.setOnErrorListener { _, _, _ ->
                        stopPlayback()
                        _uiState.value = _uiState.value.copy(error = "录音无法播放，请稍后重试")
                        true
                    }
                    mediaPlayer.prepare()
                    mediaPlayer.start()
                }
            }.onSuccess {
                if (request == playbackRequest) {
                    _uiState.value = _uiState.value.copy(loadingAudioId = null, playingId = recording.id)
                }
            }.onFailure { error ->
                if (request == playbackRequest) {
                    stopPlayback()
                    _uiState.value = _uiState.value.copy(
                        loadingAudioId = null,
                        error = (error as? AlanApiException)?.message ?: "录音播放失败，请稍后重试",
                    )
                }
            }
        }
    }

    fun stopPlayback() {
        playbackRequest += 1
        player?.let { mediaPlayer ->
            runCatching { mediaPlayer.stop() }
            runCatching { mediaPlayer.reset() }
            runCatching { mediaPlayer.release() }
        }
        player = null
        cachedAudio?.delete()
        cachedAudio = null
        _uiState.value = _uiState.value.copy(loadingAudioId = null, playingId = null)
    }

    override fun onCleared() {
        stopPlayback()
        super.onCleared()
    }

    companion object {
        fun factory(container: AppContainer) = simpleFactory { RepeatRecordingsViewModel(container) }
    }
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val mergedSummary = combine(
        container.repository.observeHome(),
        container.repository.observeStats(),
    ) { local, stats ->
        local.copy(
            todayTouched = stats.todayWords,
            todayReviews = stats.todayAnswers,
            accuracy = stats.todayMainlineAccuracy,
            mainlineAnswers = stats.todayMainlineAnswers,
            streakDays = stats.streakDays,
        )
    }

    val uiState: StateFlow<HomeUiState> = combine(
        mergedSummary,
        container.repository.observeBookProgress(),
        container.preferences.settings,
        container.repository.observeScopedWords(),
        combine(container.repository.progress, container.repository.sessions) { progressItems, studySessions ->
            progressItems to studySessions
        },
    ) { summary, books, settings, words, progressAndSessions ->
        val (progressItems, studySessions) = progressAndSessions
        val byId = progressItems.associateBy { it.wordId }
        val selectedWords = words.filter { word ->
            word.bookId in settings.selectedBookIds &&
                MainlineScheduler.matchesUnitScope(word, settings.selectedUnitKeys)
        }
        val now = System.currentTimeMillis()
        val freshLeft = selectedWords.any { byId[it.id]?.attempts == 0 }
        val dueLeft = selectedWords.any {
            val p = byId[it.id]
            p != null && p.attempts > 0 && !p.isMastered && p.dueAt <= now
        }
        HomeUiState(
            summary = summary,
            books = books,
            settings = settings,
            allCovered = selectedWords.isNotEmpty() && !freshLeft && !dueLeft,
            plan = MainlineScheduler.plan(
                words = words,
                progressItems = progressItems,
                settings = settings,
                touchedToday = summary.todayTouched,
                pace = MainlineScheduler.paceFromSessions(studySessions, now),
            ),
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun refresh() {
        viewModelScope.launch { container.repository.syncNow() }
    }

    companion object {
        fun factory(container: AppContainer) = simpleFactory { HomeViewModel(container) }
    }
}

data class BooksUiState(
    val books: List<BookProgress> = emptyList(),
    val settings: UserSettings = UserSettings(),
)

class BooksViewModel(private val container: AppContainer) : ViewModel() {
    private var settingsSyncJob: Job? = null
    val uiState: StateFlow<BooksUiState> = combine(
        container.repository.observeBookProgress(),
        container.preferences.settings,
    ) { books, settings -> BooksUiState(books, settings) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BooksUiState())

    init {
        viewModelScope.launch {
            val books = container.repository.observeBookProgress().first()
            val settings = container.preferences.settings.first()
            val validIds = books.map { it.book.id }.toSet()
            val stale = settings.selectedBookIds - validIds
            if (stale.isNotEmpty()) {
                container.preferences.setSelectedBooks(settings.selectedBookIds - stale)
            }
        }
    }

    fun toggleBook(id: String) {
        mutate { container.preferences.toggleBookSelection(id) }
    }

    fun toggleUnit(key: String) {
        mutate { container.preferences.toggleUnitSelection(key) }
    }

    fun toggleUnits(keys: Set<String>) {
        mutate { container.preferences.toggleUnitsSelection(keys) }
    }

    fun toggleUnitEnsuringBook(key: String, bookId: String) {
        mutate {
            val selected = container.preferences.settings.first().selectedBookIds
            if (bookId !in selected) container.preferences.toggleBookSelection(bookId)
            container.preferences.toggleUnitSelection(key)
        }
    }

    fun toggleUnitsEnsuringBook(keys: Set<String>, bookId: String) {
        mutate {
            val selected = container.preferences.settings.first().selectedBookIds
            if (bookId !in selected) container.preferences.toggleBookSelection(bookId)
            container.preferences.toggleUnitsSelection(keys)
        }
    }

    fun selectAllBooks(ids: Set<String>) {
        mutate { container.preferences.setSelectedBooks(ids) }
    }

    fun selectOnly(bookId: String) {
        mutate { container.preferences.setSelectedBooks(setOf(bookId)) }
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
            settingsSyncJob?.cancel()
            settingsSyncJob = viewModelScope.launch {
                delay(600)
                container.repository.syncNow()
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = simpleFactory { BooksViewModel(container) }
    }
}

class ReviewViewModel(private val container: AppContainer) : ViewModel() {
    val uiState: StateFlow<ReviewCollections> = container.repository.observeReviewCollections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewCollections())

    init {
        viewModelScope.launch { container.repository.syncNow() }
    }

    companion object {
        fun factory(container: AppContainer) = simpleFactory { ReviewViewModel(container) }
    }
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private var settingsSyncJob: Job? = null
    val settings: StateFlow<UserSettings> = container.preferences.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    fun setDailyGoal(value: Int) = launch { container.preferences.setDailyGoal(value) }
    fun setDailyMinutes(value: Int) = launch { container.preferences.setDailyMinutes(value) }
    fun setAutoSpeak(value: Boolean) = launch { container.preferences.setAutoSpeak(value) }
    fun setShuffle(value: Boolean) = launch { container.preferences.setShuffle(value) }
    fun setDarkMode(value: Boolean) = launch { container.preferences.setDarkMode(value) }
    fun setCompactHome(value: Boolean) = launch { container.preferences.setCompactHome(value) }
    fun setAccent(value: String) = launch { container.preferences.setAccent(value) }
    fun setStudySpeechRate(value: Float) = launch { container.preferences.setStudySpeechRate(value) }
    fun setReminderEnabled(value: Boolean) = launch {
        container.preferences.setReminderEnabled(value)
        ReminderScheduler.schedule(container.applicationContext, value, settings.value.reminderHour)
    }

    fun setReminderHour(value: Int) = launch {
        container.preferences.setReminderHour(value)
        ReminderScheduler.schedule(container.applicationContext, settings.value.reminderEnabled, value)
    }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch {
        block()
        settingsSyncJob?.cancel()
        settingsSyncJob = viewModelScope.launch {
            delay(600)
            container.repository.syncNow()
        }
    }

    companion object {
        fun factory(container: AppContainer) = simpleFactory { SettingsViewModel(container) }
    }
}

enum class StudyStage { PREVIEW, MEANING, LISTENING, SPELLING }

data class StudyUiState(
    val loading: Boolean = true,
    val mode: StudyMode = StudyMode.LEARN,
    val words: List<WordEntity> = emptyList(),
    val currentIndex: Int = 0,
    val stage: StudyStage = StudyStage.PREVIEW,
    val options: List<WordEntity> = emptyList(),
    val selectedWordId: Long? = null,
    val answerCorrect: Boolean? = null,
    val spellingInput: String = "",
    val correctAnswers: Int = 0,
    val totalAnswers: Int = 0,
    val wordHadError: Boolean = false,
    val completed: Boolean = false,
    val isFavorite: Boolean = false,
) {
    val currentWord: WordEntity? get() = words.getOrNull(currentIndex)
    val progress: Float get() = if (words.isEmpty()) 0f else currentIndex.toFloat() / words.size
}

class StudyViewModel(
    private val container: AppContainer,
    private val mode: StudyMode,
) : ViewModel() {
    private val _uiState = MutableStateFlow(StudyUiState(mode = mode))
    val uiState: StateFlow<StudyUiState> = _uiState

    init {
        viewModelScope.launch {
            container.repository.ensureSeeded()
            val settings = container.preferences.settings.first()
            val words = container.repository.getSession(
                bookIds = settings.selectedBookIds,
                unitKeys = settings.selectedUnitKeys,
                mode = mode,
                limit = settings.dailyGoal.coerceAtMost(20),
                shuffle = settings.shuffle,
            )
            _uiState.value = StudyUiState(
                loading = false,
                mode = mode,
                words = words,
                completed = words.isEmpty(),
            )
            prepareCurrentWord()
        }
    }

    fun submitConfidence(level: Int) {
        val state = _uiState.value
        val word = state.currentWord ?: return
        if (state.stage != StudyStage.PREVIEW) return
        _uiState.value = state.copy(
            stage = StudyStage.MEANING,
            wordHadError = level == 0,
            selectedWordId = null,
            answerCorrect = null,
        )
        viewModelScope.launch {
            container.repository.logAnswer(word.id, level > 0, QuestionType.CONFIDENCE)
        }
    }

    fun selectOption(wordId: Long) {
        val state = _uiState.value
        val word = state.currentWord ?: return
        if (state.answerCorrect != null || state.stage !in listOf(StudyStage.MEANING, StudyStage.LISTENING)) return
        val correct = wordId == word.id
        val type = if (state.stage == StudyStage.MEANING) QuestionType.MEANING else QuestionType.LISTENING
        _uiState.value = state.copy(
            selectedWordId = wordId,
            answerCorrect = correct,
            correctAnswers = state.correctAnswers + if (correct) 1 else 0,
            totalAnswers = state.totalAnswers + 1,
            wordHadError = state.wordHadError || !correct,
        )
        viewModelScope.launch {
            container.repository.logAnswer(word.id, correct, type)
        }
    }

    fun updateSpelling(value: String) {
        if (_uiState.value.answerCorrect == null) _uiState.value = _uiState.value.copy(spellingInput = value)
    }

    fun submitSpelling() {
        val state = _uiState.value
        val word = state.currentWord ?: return
        if (state.stage != StudyStage.SPELLING || state.answerCorrect != null || state.spellingInput.isBlank()) return
        val correct = state.spellingInput.trim().equals(word.word, ignoreCase = true)
        _uiState.value = state.copy(
            answerCorrect = correct,
            correctAnswers = state.correctAnswers + if (correct) 1 else 0,
            totalAnswers = state.totalAnswers + 1,
            wordHadError = state.wordHadError || !correct,
        )
        viewModelScope.launch {
            container.repository.logAnswer(word.id, correct, QuestionType.SPELLING)
            container.repository.completeWord(word.id, correct && !state.wordHadError)
        }
    }

    fun continueFlow() {
        val state = _uiState.value
        if (state.answerCorrect == null) return
        when (state.stage) {
            StudyStage.MEANING -> _uiState.value = state.copy(
                stage = StudyStage.LISTENING,
                selectedWordId = null,
                answerCorrect = null,
            )
            StudyStage.LISTENING -> _uiState.value = state.copy(
                stage = StudyStage.SPELLING,
                selectedWordId = null,
                answerCorrect = null,
                spellingInput = "",
            )
            StudyStage.SPELLING -> moveNext()
            StudyStage.PREVIEW -> Unit
        }
    }

    fun toggleFavorite() {
        val word = _uiState.value.currentWord ?: return
        viewModelScope.launch {
            container.repository.toggleFavorite(word.id)
            _uiState.value = _uiState.value.copy(isFavorite = !_uiState.value.isFavorite)
        }
    }

    private fun moveNext() {
        val state = _uiState.value
        val nextIndex = state.currentIndex + 1
        if (nextIndex >= state.words.size) {
            _uiState.value = state.copy(completed = true)
            return
        }
        _uiState.value = state.copy(
            currentIndex = nextIndex,
            stage = StudyStage.PREVIEW,
            options = emptyList(),
            selectedWordId = null,
            answerCorrect = null,
            spellingInput = "",
            wordHadError = false,
            isFavorite = false,
        )
        prepareCurrentWord()
    }

    private fun prepareCurrentWord() {
        val word = _uiState.value.currentWord ?: return
        viewModelScope.launch {
            val options = container.repository.getOptions(word)
            val progress = container.repository.progressFor(word.id)
            _uiState.value = _uiState.value.copy(options = options, isFavorite = progress?.isFavorite == true)
        }
    }

    companion object {
        fun factory(container: AppContainer, mode: StudyMode) = simpleFactory { StudyViewModel(container, mode) }
    }
}

private fun <T : ViewModel> simpleFactory(create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
    }
