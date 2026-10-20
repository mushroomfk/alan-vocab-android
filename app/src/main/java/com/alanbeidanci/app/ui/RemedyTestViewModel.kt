package com.alanbeidanci.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.data.MainlineTestKind
import com.alanbeidanci.app.data.LearningContentPresentation
import com.alanbeidanci.app.data.QuestionType
import com.alanbeidanci.app.data.WordEntity
import com.alanbeidanci.app.data.DualTestCheckpoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
    val skipAvailable: Boolean get() = false
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
    private var checkpoint: DualTestCheckpoint? = null
    private var checkpointWrite: Job? = null
    private var initialTypedChars = 0
    private var initialCorrectChars = 0
    private var initialBothCorrect = 0

    init {
        viewModelScope.launch {
            container.repository.ensureSeeded()
            loadBatch()
        }
    }

    private suspend fun loadBatch() {
        val settings = container.preferences.settings.first()
        val batch = container.repository.dualTests.load(settings, kind)
        checkpoint = batch?.checkpoint
        _uiState.value = if (batch == null) RemedyUiState(loading = false, kind = kind, accent = settings.accent,
            completed = settings.selectedBookIds.isNotEmpty(), noBooks = settings.selectedBookIds.isEmpty())
        else stateFor(batch.checkpoint, RemedyUiState(loading = false, kind = kind, words = batch.words, accent = settings.accent))
        initialTypedChars = _uiState.value.typedChars
        initialCorrectChars = _uiState.value.correctChars
        initialBothCorrect = _uiState.value.bothCorrect
    }

    private fun stateFor(cp: DualTestCheckpoint, base: RemedyUiState): RemedyUiState = base.copy(
        round = if (cp.round == 0) RemedyRound.ENGLISH else RemedyRound.CHINESE, currentIndex = cp.index,
        input = cp.input, currentWrong = cp.currentWrong, totalWrong = cp.totalWrong,
        typedChars = cp.typedChars, correctChars = cp.correctChars, completedQuestions = cp.completedQuestions,
        currentError = cp.holdResult && !cp.holdCorrect, holdResult = cp.holdResult, holdCorrect = cp.holdCorrect,
        locked = cp.holdResult, listenScore = cp.listening.values.count { it },
        chineseScore = cp.spelling.values.count { it }, bothCorrect = cp.bothCorrect,
    )

    fun start() {
        if (_uiState.value.started || checkpoint == null) return
        studyClock.start(System.currentTimeMillis())
        _uiState.value = _uiState.value.copy(started = true, locked = true)
        prepareQuestion()
    }

    private fun prepareQuestion() {
        val captured = checkpoint ?: return
        viewModelScope.launch {
            val prepared = withContext(NonCancellable) {
                checkpointWrite?.join()
                container.repository.dualTests.prepare(captured)
            }
            if (checkpoint?.sessionId != captured.sessionId || checkpoint?.round != captured.round || checkpoint?.index != captured.index) return@launch
            checkpoint = prepared
            _uiState.value = stateFor(prepared, _uiState.value)
        }
    }

    private fun captureCheckpoint(answered: Boolean = false): DualTestCheckpoint? {
        val cp = checkpoint ?: return null
        val state = _uiState.value
        if (cp.preparedAt == 0L || state.completed || (state.locked && !state.holdResult)) return null
        return cp.copy(revision = cp.revision + 1, input = state.input, holdResult = state.holdResult,
            holdCorrect = state.holdCorrect, currentWrong = state.currentWrong, totalWrong = state.totalWrong,
            typedChars = state.typedChars, correctChars = state.correctChars,
            answeredAt = if (answered) System.currentTimeMillis() else cp.answeredAt).also { checkpoint = it }
    }

    private fun persistState(answered: Boolean = false) {
        val saved = captureCheckpoint(answered) ?: return
        val previous = checkpointWrite
        checkpointWrite = viewModelScope.launch {
            withContext(NonCancellable) { previous?.join(); container.repository.dualTests.save(saved) }
        }
    }

    fun finish() {
        persistState()
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
                    completed = if (closing) (state.bothCorrect - initialBothCorrect).coerceAtLeast(0) else 0,
                    durationMs = slice.durationMs,
                    typedChars = if (closing) (state.typedChars - initialTypedChars).coerceAtLeast(0) else 0,
                    correctChars = if (closing) (state.correctChars - initialCorrectChars).coerceAtLeast(0) else 0,
                    startedAt = slice.startedAt,
                    finishedAt = slice.finishedAt,
                )
            }
        }
    }

    fun onStudyForeground() = studyClock.foreground(System.currentTimeMillis())

    fun onStudyBackground() { studyClock.background(System.currentTimeMillis()); persistState() }

    fun tickStudyClock() = studyClock.tick(System.currentTimeMillis())

    fun activeElapsedMs(): Long = studyClock.elapsed(System.currentTimeMillis())

    fun updateInput(value: String) {
        studyClock.interact(System.currentTimeMillis())
        val state = _uiState.value
        val word = state.currentWord ?: return
        if (!state.started || state.locked) return
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
            persistState(answered = true)
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
            persistState(answered = true)
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
            answerCurrent(correct = true)
            return
        }
        answerCurrent(correct = false)
    }

    /** 听不出来或不认识，先看正确答案，再记为没通过并进入下一题。 */
    fun giveUp() {
        studyClock.interact(System.currentTimeMillis())
        val state = _uiState.value
        if (!state.started || state.locked || state.holdResult || state.completed) return
        _uiState.value = state.copy(
            locked = true,
            holdResult = true,
            holdCorrect = false,
            currentError = true,
            currentWrong = state.currentWrong + 1,
            totalWrong = state.totalWrong + 1,
        )
        persistState(answered = true)
    }

    fun skip() = giveUp()

    private fun answerCurrent(correct: Boolean) {
        val state = _uiState.value
        val captured = checkpoint ?: return
        if (!state.holdResult) return
        val both = state.round == RemedyRound.CHINESE && captured.listening[captured.wordId] == true && correct
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
            val result = withContext(NonCancellable) {
                checkpointWrite?.join()
                container.repository.dualTests.answer(captured)
            }
            if (!result.accepted) { loadBatch(); return@launch }
            checkpoint = result.next
            echoText = ""
            if (result.next == null) {
                studyClock.hold(System.currentTimeMillis())
                _uiState.value = _uiState.value.copy(completed = true, locked = false)
            } else {
                _uiState.value = stateFor(result.next, _uiState.value).copy(locked = true)
                prepareQuestion()
            }
        }
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
