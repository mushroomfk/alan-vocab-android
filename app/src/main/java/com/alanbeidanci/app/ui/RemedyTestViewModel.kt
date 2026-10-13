package com.alanbeidanci.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.data.MainlineTestKind
import com.alanbeidanci.app.data.LearningContentPresentation
import com.alanbeidanci.app.data.QuestionType
import com.alanbeidanci.app.data.WordEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class RemedyRound { ENGLISH, CHINESE }

data class RemedyUiState(
    val loading: Boolean = true,
    val kind: MainlineTestKind = MainlineTestKind.REMEDY,
    val words: List<WordEntity> = emptyList(),
    val accent: String = "us",
    val started: Boolean = false,
    val round: RemedyRound = RemedyRound.ENGLISH,
    val currentIndex: Int = 0,
    val input: String = "",
    val currentWrong: Int = 0,
    val totalWrong: Int = 0,
    val typedChars: Int = 0,
    val correctChars: Int = 0,
    val completedQuestions: Int = 0,
    val currentError: Boolean = false,
    val locked: Boolean = false,
    val holdResult: Boolean = false,
    val holdCorrect: Boolean = false,
    val completed: Boolean = false,
    val noBooks: Boolean = false,
    val listenScore: Int = 0,
    val chineseScore: Int = 0,
    val bothCorrect: Int = 0,
) {
    val currentWord: WordEntity? get() = words.getOrNull(currentIndex)
    val skipAvailable: Boolean get() = currentWrong >= 4 && !locked
    val progress: Float get() = ((completedQuestions + 1).toFloat() / (words.size * 2).coerceAtLeast(1)).coerceIn(0f, 1f)
    val questionIndex: Int get() = (completedQuestions + 1).coerceAtMost((words.size * 2).coerceAtLeast(1))
    val title: String get() = when (kind) {
        MainlineTestKind.REMEDY -> "补漏双测"
        MainlineTestKind.CONSOLIDATION -> "巩固双测"
        MainlineTestKind.NEW_WORDS -> "新词主线双测"
    }
}

class RemedyTestViewModel(
    private val container: AppContainer,
    private val kind: MainlineTestKind,
) : ViewModel() {
    private val _uiState = MutableStateFlow(RemedyUiState(kind = kind))
    val uiState: StateFlow<RemedyUiState> = _uiState

    private var sessionSaved = false
    private val studyClock = StudyClock()
    private var echoText: String = ""
    private var echoUntilMs: Long = 0
    private val listenResults = mutableMapOf<Long, Boolean>()

    init {
        viewModelScope.launch {
            container.repository.ensureSeeded()
            val settings = container.preferences.settings.first()
            val words = container.repository.getDualTestWords(settings, kind)
            _uiState.value = RemedyUiState(
                loading = false,
                kind = kind,
                words = words,
                accent = settings.accent,
                completed = settings.selectedBookIds.isNotEmpty() && words.isEmpty(),
                noBooks = settings.selectedBookIds.isEmpty(),
            )
        }
    }

    fun start() {
        studyClock.start(System.currentTimeMillis())
        _uiState.value = _uiState.value.copy(started = true)
    }

    fun finish() {
        if (sessionSaved) return
        sessionSaved = true
        val state = _uiState.value
        val slices = studyClock.finish(System.currentTimeMillis())
        if (slices.isEmpty()) return
        viewModelScope.launch {
            val last = slices.lastIndex
            slices.forEachIndexed { index, slice ->
                val closing = index == last
                container.repository.saveSession(
                    mode = "TEST",
                    wordCount = if (closing) state.words.size else 0,
                    completed = if (closing) state.bothCorrect else 0,
                    durationMs = slice.durationMs,
                    typedChars = if (closing) state.typedChars else 0,
                    correctChars = if (closing) state.correctChars else 0,
                    startedAt = slice.startedAt,
                    finishedAt = slice.finishedAt,
                )
            }
        }
    }

    fun onStudyForeground() = studyClock.foreground(System.currentTimeMillis())

    fun onStudyBackground() = studyClock.background(System.currentTimeMillis())

    fun tickStudyClock() = studyClock.tick(System.currentTimeMillis())

    fun activeElapsedMs(): Long = studyClock.elapsed(System.currentTimeMillis())

    fun updateInput(value: String) {
        studyClock.interact(System.currentTimeMillis())
        val state = _uiState.value
        val word = state.currentWord ?: return
        if (state.locked) return
        val normalized = LetterInput.letters(value)
        if (normalized.isNotEmpty() && normalized == echoText && System.currentTimeMillis() < echoUntilMs) return
        val target = letterCharacters(canonical(word.word))
        val decision = LetterInput.apply(state.input, value, target, judgeWhenComplete = true)
        if (decision.rejected) {
            echoText = normalized
            echoUntilMs = System.currentTimeMillis() + 150
            _uiState.value = state.copy(
                input = normalized,
                currentWrong = state.currentWrong + 1,
                totalWrong = state.totalWrong + 1,
                typedChars = state.typedChars + normalized.length,
                currentError = true,
                locked = true,
                holdResult = true,
                holdCorrect = false,
            )
            return
        }
        if (decision.accepted) {
            _uiState.value = state.copy(
                input = decision.text,
                typedChars = state.typedChars + target.length,
                correctChars = state.correctChars + target.length,
                locked = true,
                holdResult = true,
                holdCorrect = true,
                currentError = false,
            )
            return
        }
        if (decision.text == state.input) return
        _uiState.value = state.copy(input = decision.text, currentError = false)
    }

    fun acknowledgeResult() {
        studyClock.interact(System.currentTimeMillis())
        val state = _uiState.value
        if (!state.holdResult) return
        if (state.holdCorrect) {
            answerCurrent(
                correct = state.currentWrong == 0,
                spellingQuality = if (state.currentWrong == 0) "exact" else "unknown",
            )
            return
        }
        _uiState.value = state.copy(holdResult = false, holdCorrect = false, locked = false, input = "", currentError = false)
    }

    fun skip() {
        studyClock.interact(System.currentTimeMillis())
        if (!_uiState.value.skipAvailable) return
        answerCurrent(correct = false, spellingQuality = "unknown")
    }

    private fun answerCurrent(correct: Boolean, spellingQuality: String) {
        val state = _uiState.value
        val word = state.currentWord ?: return
        if (state.locked) return
        val listenPass = if (state.round == RemedyRound.ENGLISH) correct else listenResults[word.id] ?: false
        val chinesePass = if (state.round == RemedyRound.CHINESE) correct else null
        if (state.round == RemedyRound.ENGLISH) listenResults[word.id] = correct
        val both = state.round == RemedyRound.CHINESE && listenPass && correct
        _uiState.value = state.copy(
            input = "",
            currentWrong = 0,
            currentError = false,
            locked = true,
            holdResult = false,
            holdCorrect = false,
            completedQuestions = state.completedQuestions + 1,
            listenScore = state.listenScore + if (state.round == RemedyRound.ENGLISH && correct) 1 else 0,
            chineseScore = state.chineseScore + if (state.round == RemedyRound.CHINESE && correct) 1 else 0,
            bothCorrect = state.bothCorrect + if (both) 1 else 0,
        )
        viewModelScope.launch {
            container.repository.recordDualAnswer(
                wordId = word.id,
                correct = correct,
                type = if (state.round == RemedyRound.ENGLISH) QuestionType.LISTENING else QuestionType.MEANING,
                listenPass = listenPass,
                chinesePass = chinesePass,
                kind = kind,
                spellingQuality = spellingQuality,
            )
            advanceAfter(_uiState.value.copy(locked = false))
        }
    }

    private fun advanceAfter(state: RemedyUiState) {
        echoText = ""
        if (state.round == RemedyRound.ENGLISH && state.currentIndex + 1 >= state.words.size) {
            _uiState.value = state.copy(
                round = RemedyRound.CHINESE,
                currentIndex = 0,
                input = "",
                currentWrong = 0,
                currentError = false,
            )
            return
        }
        if (state.currentIndex + 1 >= state.words.size) {
            studyClock.hold(System.currentTimeMillis())
            _uiState.value = state.copy(completed = true, locked = false)
            return
        }
        _uiState.value = state.copy(
            currentIndex = state.currentIndex + 1,
            input = "",
            currentWrong = 0,
            currentError = false,
        )
    }

    private fun canonical(value: String): String = LearningContentPresentation.canonicalSpelling(value).replace("…", "..")
    private fun letterCharacters(value: String): String = LetterInput.letters(value)

    companion object {
        fun factory(container: AppContainer, kind: MainlineTestKind): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = RemedyTestViewModel(container, kind) as T
            }
    }
}
