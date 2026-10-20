package com.alanbeidanci.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.data.TypingPurpose
import com.alanbeidanci.app.data.UserSettings
import com.alanbeidanci.app.data.WordEntity
import com.alanbeidanci.app.data.LearningContentPresentation
import com.alanbeidanci.app.data.MainlineScheduler
import com.alanbeidanci.app.data.PracticeSession
import com.alanbeidanci.app.data.PracticeSnapshot
import com.alanbeidanci.app.data.QuestionType
import com.alanbeidanci.app.data.RetentionGrade
import com.alanbeidanci.app.data.WordPresentation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import com.alanbeidanci.app.data.MemorySource
import com.alanbeidanci.app.data.MemorySkill
import com.alanbeidanci.app.data.SkillPracticePlan
import kotlinx.coroutines.Job

data class PracticeChoice(val wordId: Long, val label: String)

data class TypingUiState(
    val loading: Boolean = true,
    val purpose: TypingPurpose = TypingPurpose.MAINLINE_LEARN,
    val words: List<WordEntity> = emptyList(),
    val originalWordCount: Int = 0,
    val round: Int = 1,
    val started: Boolean = false,
    val scopeLabel: String = "",
    val listPracticeLabel: String = "",
    val currentIndex: Int = 0,
    val input: String = "",
    val currentWrong: Int = 0,
    val totalWrong: Int = 0,
    val completedWords: Int = 0,
    val completedOriginalWords: Int = 0,
    val wrongWords: Int = 0,
    val typedChars: Int = 0,
    val correctChars: Int = 0,
    val currentError: Boolean = false,
    val locked: Boolean = false,
    val isFavorite: Boolean = false,
    val completed: Boolean = false,
    val confirmed: Boolean = false,
    val problemCount: Int = 0,
    val noBooks: Boolean = false,
    val practiceForm: PracticeForm = PracticeForm.STUDY,
    val cleanRecalls: Int = 0,
    val skillProgress: String = "",
    val answerVisible: Boolean = false,
    val learnMissed: Boolean = false,
    val graduatedCount: Int = 0,
    val parkedCount: Int = 0,
    val cardSerial: Int = 0,
    val choices: List<PracticeChoice> = emptyList(),
    val fadeHint: String = "",
    val cloze: String = "",
    val feedback: String = "",
    val holdResult: Boolean = false,
    val assisted: Boolean = false,
    val deferredCount: Int = 0,
    val scheduledDays: List<Int> = emptyList(),
    val cleanFinishes: Int = 0,
    val lapseFinishes: Int = 0,
    val recheckFinishes: Int = 0,
    val canContinueLearning: Boolean = false,
) {
    val currentWord: WordEntity? get() = words.getOrNull(currentIndex)
    val skipAvailable: Boolean get() = currentWrong >= 4 && !locked
    val isConsolidation: Boolean get() = purpose == TypingPurpose.MAINLINE_CONSOLIDATE || purpose == TypingPurpose.RELEARN
    val isLearnRecall: Boolean get() = purpose == TypingPurpose.MAINLINE_LEARN || isConsolidation
    val progress: Float
        get() = if (isLearnRecall) {
            if (originalWordCount == 0) 0f else graduatedCount.toFloat() / originalWordCount
        } else if (words.isEmpty()) {
            0f
        } else {
            (currentIndex + 1).toFloat().coerceAtMost(words.size.toFloat()) / words.size
        }
    val isReview: Boolean get() = purpose == TypingPurpose.MAINLINE_REVIEW || purpose == TypingPurpose.AUTONOMOUS_REVIEW
    val isMainlineReview: Boolean get() = purpose == TypingPurpose.MAINLINE_REVIEW
    val canConfirmReview: Boolean get() = isMainlineReview && completedOriginalWords >= originalWordCount && originalWordCount > 0
}

class TypingViewModel(
    private val container: AppContainer,
    private val purpose: TypingPurpose,
    private val reviewScope: String = "",
    private val beyondPlan: Boolean = false,
) : ViewModel() {
    private val _uiState = MutableStateFlow(TypingUiState(purpose = purpose))
    val uiState: StateFlow<TypingUiState> = _uiState

    private var originalWords: List<WordEntity> = emptyList()
    private val completedOriginalIds = mutableSetOf<Long>()
    private val roundProblemIds = mutableSetOf<Long>()
    private var sessionSaved = false
    private val studyClock = StudyClock()
    private var savedPractice: Map<Long, SavedPractice> = emptyMap()
    private var practiceQueue: MutableList<MemoryState> = mutableListOf()
    private var memory: MemoryState? = null
    private var pendingEffect: PracticeEffect? = null
    private var pendingAdvance: (() -> Unit)? = null
    private var advanceGeneration = 0
    private var evidenceWrite: Job? = null

    init {
        viewModelScope.launch {
            container.repository.ensureSeeded()
            val settings = container.preferences.settings.first()
            val scheduledWords = when {
                reviewScope.isNotBlank() -> container.repository.reviewListWords(settings, reviewScope)
                beyondPlan -> container.repository.moreLearnWords(settings, emptySet())
                else -> container.repository.getTypingWords(settings, purpose)
            }
            originalWords = scheduledWords.distinctBy { it.id }
            if (purpose == TypingPurpose.MAINLINE_LEARN || purpose == TypingPurpose.MAINLINE_CONSOLIDATE || purpose == TypingPurpose.RELEARN) {
                savedPractice = container.repository.practiceSnapshots(originalWords.map { it.id }.toSet())
                    .mapValues { (_, snapshot) -> snapshot.toSaved() }
            }
            _uiState.value = TypingUiState(
                loading = false,
                purpose = purpose,
                words = scheduledWords,
                originalWordCount = originalWords.size,
                scopeLabel = buildScopeLabel(settings),
                listPracticeLabel = reviewListLabel(reviewScope),
                completed = scheduledWords.isEmpty() && settings.selectedBookIds.isNotEmpty(),
                noBooks = settings.selectedBookIds.isEmpty(),
            )
            refreshFavorite()
        }
    }

    fun start() {
        studyClock.start(System.currentTimeMillis())
        if (purpose != TypingPurpose.MAINLINE_LEARN && purpose != TypingPurpose.MAINLINE_CONSOLIDATE && purpose != TypingPurpose.RELEARN) {
            _uiState.value = _uiState.value.copy(started = true)
            return
        }
        val phase = if (purpose == TypingPurpose.MAINLINE_CONSOLIDATE || purpose == TypingPurpose.RELEARN) {
            PracticePhase.REVIEW
        } else {
            PracticePhase.LEARN
        }
        val examples = originalWords.associate { it.id to (LearningContentPresentation.clozeFor(container.applicationContext, it) != null) }
        val session = MemoryPractice.begin(originalWords.map { it.id }, savedPractice, phase, examples)
        practiceQueue = session.pending
        val first = session.showing
        if (first == null) {
            holdStudy()
            _uiState.value = _uiState.value.copy(started = true, completed = true)
            return
        }
        showMemory(first, _uiState.value.copy(started = true, graduatedCount = 0, parkedCount = 0))
    }

    /** 计划时长内的词学完后，再取一批。计时不截断，学到自己停为止。 */
    fun continueLearning() {
        if (purpose != TypingPurpose.MAINLINE_LEARN) return
        viewModelScope.launch {
            val settings = container.preferences.settings.first()
            val more = container.repository.moreLearnWords(settings, originalWords.map { it.id }.toSet())
            if (more.isEmpty()) {
                _uiState.value = _uiState.value.copy(canContinueLearning = false)
                return@launch
            }
            val snapshots = container.repository.practiceSnapshots(more.map { it.id }.toSet())
                .mapValues { (_, snapshot) -> snapshot.toSaved() }
            savedPractice = savedPractice + snapshots
            originalWords = originalWords + more
            val examples = more.associate { word ->
                word.id to (LearningContentPresentation.clozeFor(container.applicationContext, word) != null)
            }
            val session = MemoryPractice.begin(more.map { it.id }, snapshots, PracticePhase.LEARN, examples)
            practiceQueue = session.pending
            val first = session.showing ?: return@launch
            studyClock.start(System.currentTimeMillis())
            showMemory(
                first,
                _uiState.value.copy(
                    started = true,
                    completed = false,
                    canContinueLearning = false,
                    originalWordCount = originalWords.size,
                    locked = false,
                    holdResult = false,
                    learnMissed = false,
                    answerVisible = false,
                ),
            )
        }
    }

    fun acknowledgeStudy() {
        noteStudy()
        val state = _uiState.value
        val card = memory ?: return
        if (!state.isLearnRecall || card.form != PracticeForm.STUDY || state.locked) return
        val effect = MemoryPractice.afterStudy(card)
        showMemory(effect.state, state)
    }

    fun choose(wordId: Long) {
        noteStudy()
        val state = _uiState.value
        val word = state.currentWord ?: return
        if (!state.isLearnRecall || state.locked || state.learnMissed) return
        if (state.practiceForm != PracticeForm.PICK_MEANING && state.practiceForm != PracticeForm.PICK_SPELLING) return
        if (wordId == word.id) onPracticeCorrect(state, 0, 0) else onPracticeWrong(state, word, "")
    }

    fun continueAfterMiss() = continueAfterResult()

    fun continueAfterResult() {
        noteStudy()
        val advance = pendingAdvance ?: return
        pendingAdvance = null
        pendingEffect = null
        advanceGeneration++
        advance()
    }

    fun notePronunciationHint() {
        val card = memory ?: return
        val state = _uiState.value
        if (!state.isLearnRecall || state.answerVisible || state.learnMissed || card.hinted) return
        if (state.practiceForm == PracticeForm.LISTEN || state.practiceForm == PracticeForm.STUDY) return
        val hinted = card.copy(hinted = true)
        memory = hinted
        _uiState.value = state.copy(assisted = true)
        val requestedAt = System.currentTimeMillis()
        viewModelScope.launch {
            withContext(NonCancellable) {
                container.repository.logRetrieval(
                    wordId = card.wordId,
                    correct = false,
                    type = questionType(state.practiceForm),
                    form = state.practiceForm.name,
                    firstAttempt = card.firstResult == FirstResult.NONE,
                    hinted = true,
                    eventKind = "hint",
                    mainline = true,
                )
                container.repository.savePracticeSession(hinted.toSession(), requestedAt)
            }
        }
    }

    /** 听写、默写、填空里完全不会：先看正确拼写，点「下一个」再按写错继续。 */
    fun giveUp() {
        noteStudy()
        val state = _uiState.value
        val word = state.currentWord ?: return
        val card = memory ?: return
        if (!state.isLearnRecall || !state.started || state.completed || state.holdResult || state.locked) return
        if (card.form != PracticeForm.RECALL && card.form != PracticeForm.LISTEN &&
            card.form != PracticeForm.CLOZE && card.form != PracticeForm.FADED
        ) {
            return
        }
        onPracticeWrong(state, word, "", gaveUp = true)
    }

    fun skipLearnWord() {
        noteStudy()
        val state = _uiState.value
        val card = memory ?: return
        if (!state.isLearnRecall || !state.started || state.completed) return
        if (state.locked && !state.learnMissed) return
        advanceGeneration++
        pendingEffect = null
        val park = MemoryPractice.afterSkip(card)
        memory = null
        roundProblemIds += card.wordId
        val requestedAt = System.currentTimeMillis()
        viewModelScope.launch {
            withContext(NonCancellable) {
                container.repository.logRetrieval(
                    wordId = card.wordId,
                    correct = false,
                    type = questionType(card.form),
                    form = card.form.name,
                    firstAttempt = card.firstResult == FirstResult.NONE,
                    hinted = card.hinted,
                    eventKind = "skip",
                    mainline = true,
                )
                container.repository.savePracticeSession(park.state.toSession(), requestedAt)
            }
        }
        advancePractice(graduated = false, parked = true)
    }

    fun finish() {
        persistCurrentSession()
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
                    mode = when (purpose) {
                        TypingPurpose.MAINLINE_LEARN -> "LEARN"
                        TypingPurpose.MAINLINE_CONSOLIDATE, TypingPurpose.RELEARN -> "CONSOLIDATE"
                        else -> "REVIEW"
                    },
                    wordCount = if (closing) state.originalWordCount else 0,
                    completed = if (closing) completedOriginalIds.size else 0,
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

    fun onStudyBackground() {
        studyClock.background(System.currentTimeMillis())
        persistCurrentSession()
    }

    fun tickStudyClock() = studyClock.tick(System.currentTimeMillis())

    fun activeElapsedMs(): Long = studyClock.elapsed(System.currentTimeMillis())

    private fun noteStudy() = studyClock.interact(System.currentTimeMillis())

    private fun holdStudy() = studyClock.hold(System.currentTimeMillis())

    private suspend fun buildScopeLabel(settings: UserSettings): String {
        val books = container.repository.books.first()
        val names = books.filter { it.id in settings.selectedBookIds }.map { it.name }
        val unitCount = settings.selectedUnitKeys.size
        return when {
            names.isEmpty() -> "未选择词库"
            names.size == 1 && unitCount == 0 -> "《${names.first()}》整本"
            names.size == 1 -> "《${names.first()}》 · $unitCount 个单元"
            else -> "${names.size} 本词书 · $unitCount 个单元"
        }
    }

    fun updateInput(value: String) {
        noteStudy()
        val state = _uiState.value
        val word = state.currentWord ?: return
        if (state.locked) return
        if (state.isLearnRecall) {
            if (state.practiceForm == PracticeForm.STUDY ||
                state.practiceForm == PracticeForm.PICK_MEANING ||
                state.practiceForm == PracticeForm.PICK_SPELLING
            ) {
                return
            }
            updatePracticeInput(state, word, value)
            return
        }
        val target = letterCharacters(canonical(word.word))
        val decision = LetterInput.apply(state.input, value, target, judgeWhenComplete = false)
        if (decision.accepted) {
            val added = (decision.text.length - state.input.length).coerceAtLeast(0)
            finishWord(state, added, target.length)
            return
        }
        if (decision.text == state.input && !decision.mistake) return
        if (decision.mistake) roundProblemIds += word.id
        _uiState.value = state.copy(
            input = decision.text,
            currentWrong = state.currentWrong + if (decision.mistake) 1 else 0,
            totalWrong = state.totalWrong + if (decision.mistake) 1 else 0,
            typedChars = state.typedChars + (decision.text.length - state.input.length).coerceAtLeast(0),
            currentError = decision.mistake,
            problemCount = roundProblemIds.size,
        )
        if (decision.mistake) {
            viewModelScope.launch {
                delay(280)
                _uiState.value = _uiState.value.copy(currentError = false)
            }
        }
    }

    fun skip() {
        noteStudy()
        val state = _uiState.value
        val word = state.currentWord ?: return
        if (!state.skipAvailable) return
        roundProblemIds += word.id
        moveNext(state.copy(problemCount = roundProblemIds.size))
    }

    fun repeatProblems() {
        val problems = originalWords.filter { it.id in roundProblemIds }
        startRound(if (problems.isEmpty()) originalWords else problems)
    }

    fun repeatAll() = startRound(originalWords)

    fun confirmReview() {
        val state = _uiState.value
        if (!state.canConfirmReview || state.confirmed) return
        viewModelScope.launch {
            container.repository.confirmMainlineReview(originalWords.map { it.id })
            _uiState.value = _uiState.value.copy(confirmed = true)
        }
    }

    fun toggleFavorite() {
        val word = _uiState.value.currentWord ?: return
        viewModelScope.launch {
            container.repository.toggleFavorite(word.id)
            refreshFavorite()
        }
    }

    private fun updatePracticeInput(state: TypingUiState, word: WordEntity, value: String) {
        if (state.answerVisible) return
        val target = letterCharacters(canonical(word.word))
        val decision = LetterInput.apply(state.input, value, target, judgeWhenComplete = true)
        if (decision.accepted) {
            onPracticeCorrect(state, (decision.text.length - state.input.length).coerceAtLeast(0), target.length)
            return
        }
        if (decision.rejected) {
            onPracticeWrong(state, word, letterCharacters(value))
            return
        }
        if (decision.text == state.input) return
        _uiState.value = state.copy(
            input = decision.text,
            typedChars = state.typedChars + (decision.text.length - state.input.length).coerceAtLeast(0),
        )
    }

    private fun onPracticeCorrect(state: TypingUiState, typedAdded: Int, targetLength: Int) {
        val card = memory ?: return
        val typedState = state.copy(
            input = letterCharacters(canonical(state.currentWord?.word.orEmpty())),
            typedChars = state.typedChars + typedAdded,
            correctChars = state.correctChars + targetLength,
        )
        logAttempt(card, state.practiceForm, correct = true)
        when (val effect = MemoryPractice.afterCorrect(card, practiceQueue.size)) {
            is PracticeEffect.Graduate -> finishPractice(effect, typedState)
            is PracticeEffect.Defer -> {
                memory = null
                val requestedAt = System.currentTimeMillis()
                viewModelScope.launch {
                    withContext(NonCancellable) {
                        container.repository.savePracticeSession(effect.state.toSession(), requestedAt)
                    }
                }
                holdResult(
                    typedState.copy(cleanRecalls = effect.state.cleanRecalls),
                    correct = true,
                    feedback = "对了。这个词先停在这里，下次再写",
                ) { advancePractice(graduated = false, parked = false, deferred = true) }
            }
            is PracticeEffect.Show -> {
                memory = effect.state
                launchSessionSave(effect.state)
                val next = effect
                holdResult(
                    typedState.copy(cleanRecalls = effect.state.cleanRecalls),
                    correct = true,
                    feedback = "对了。${feedbackText(effect.move)}",
                ) {
                    if (next.gap == 0) {
                        showMemory(next.state, _uiState.value)
                    } else {
                        MemoryPractice.place(practiceQueue, next.state, next.gap)
                        advancePractice(graduated = false, parked = false)
                    }
                }
            }
            is PracticeEffect.Park -> Unit
        }
    }

    private fun onPracticeWrong(state: TypingUiState, word: WordEntity, attempt: String, gaveUp: Boolean = false) {
        val card = memory ?: return
        val effect = MemoryPractice.afterWrong(card, gaveUp)
        logAttempt(card, state.practiceForm, correct = false)
        roundProblemIds += word.id
        val spellingMiss = attempt.isNotEmpty()
        val missText = when {
            gaveUp -> "不会。正确拼写在上面"
            spellingMiss -> "错了。红字是没写对的字母，上面是正确拼写"
            else -> "选错了。上面是正确答案"
        }
        val reveal = state.copy(
            input = attempt,
            typedChars = state.typedChars + attempt.length.coerceAtLeast(1),
            currentWrong = state.currentWrong + 1,
            totalWrong = state.totalWrong + 1,
            problemCount = roundProblemIds.size,
        )
        when (effect) {
            is PracticeEffect.Show -> {
                memory = effect.state
                launchSessionSave(effect.state)
                val next = effect
                holdResult(reveal, correct = false, feedback = missText) {
                    if (next.gap > 0) MemoryPractice.place(practiceQueue, next.state, next.gap)
                    advancePractice(graduated = false, parked = false)
                }
            }
            is PracticeEffect.Park -> {
                memory = null
                if (isRetentionReview() && effect.reason == StopReason.FAILED) {
                    launchRetention(card.wordId, RetentionGrade.LAPSE, effect.state, state.practiceForm.name, card)
                } else {
                    launchSessionSave(effect.state)
                }
                val message = if (isRetentionReview() && effect.reason == StopReason.FAILED) {
                    "$missText。明天再查一次"
                } else if (gaveUp || spellingMiss) {
                    "$missText。这词先放下，下次从更容易的题开始"
                } else {
                    missText
                }
                holdResult(reveal, correct = false, feedback = message) {
                    advancePractice(graduated = false, parked = true)
                }
            }
            is PracticeEffect.Graduate -> {
                completedOriginalIds += card.wordId
                memory = null
                launchRetention(card.wordId, effect.grade, card.copy(abilities = effect.abilities), state.practiceForm.name, card)
                val grade = effect.grade
                holdResult(reveal, correct = false, feedback = "$missText。${gradeFeedback(grade)}") {
                    advancePractice(graduated = true, parked = false, grade = grade)
                }
            }
            is PracticeEffect.Defer -> Unit
        }
    }

    private fun finishPractice(effect: PracticeEffect.Graduate, typedState: TypingUiState) {
        val card = memory ?: return
        memory = null
        completedOriginalIds += card.wordId
        holdResult(
            typedState.copy(cleanRecalls = card.cleanRecalls + 1),
            correct = true,
            feedback = gradeFeedback(effect.grade),
        ) { advancePractice(graduated = true, parked = false, grade = effect.grade) }
        viewModelScope.launch {
            val days = withContext(NonCancellable) {
                evidenceWrite?.join()
                if (isRetentionReview()) {
                    container.repository.recordRetention(
                        wordId = card.wordId,
                        grade = effect.grade,
                        meaning = effect.abilities.meaning,
                        listening = effect.abilities.listening,
                        spelling = effect.abilities.spelling,
                        context = effect.abilities.context,
                        practiceForm = card.form.name,
                        firstAttempt = card.firstResult == FirstResult.NONE,
                        hinted = card.hinted,
                        independentEvidence = card.firstResult == FirstResult.CORRECT || card.firstResult == FirstResult.WRONG ||
                            (card.firstResult == FirstResult.NONE && !card.hinted),
                    )
                } else {
                    container.repository.recordTypingCompletion(
                        wordId = card.wordId,
                        purpose = purpose,
                        shaky = effect.shaky,
                        meaning = effect.abilities.meaning,
                        listening = effect.abilities.listening,
                        spelling = effect.abilities.spelling,
                        context = effect.abilities.context,
                        logSummary = false,
                    )
                    0
                }
            }
            val nextPlan = container.repository.nextSkillReviewDescription(card.wordId)
            if (days > 0 || nextPlan.isNotBlank()) {
                val remembered = if (effect.grade == RetentionGrade.GOOD) _uiState.value.scheduledDays + days else _uiState.value.scheduledDays
                _uiState.value = _uiState.value.copy(
                    feedback = if (_uiState.value.currentWord?.id == card.wordId && _uiState.value.holdResult)
                        listOf(gradeFeedback(effect.grade, days), nextPlan).filter { it.isNotBlank() }.joinToString("\n")
                        else _uiState.value.feedback,
                    scheduledDays = remembered,
                )
            }
        }
    }

    private fun advancePractice(
        graduated: Boolean,
        parked: Boolean,
        deferred: Boolean = false,
        grade: RetentionGrade? = null,
    ) {
        val state = _uiState.value
        val base = state.copy(
            graduatedCount = state.graduatedCount + if (graduated) 1 else 0,
            parkedCount = state.parkedCount + if (parked) 1 else 0,
            deferredCount = state.deferredCount + if (deferred) 1 else 0,
            cleanFinishes = state.cleanFinishes + if (grade == RetentionGrade.GOOD) 1 else 0,
            lapseFinishes = state.lapseFinishes + if (grade == RetentionGrade.LAPSE) 1 else 0,
            recheckFinishes = state.recheckFinishes + if (grade == RetentionGrade.RECHECK) 1 else 0,
            completedWords = state.graduatedCount + if (graduated) 1 else 0,
            completedOriginalWords = completedOriginalIds.size,
        )
        val next = MemoryPractice.popNext(practiceQueue)
        if (next == null) {
            memory = null
            pendingEffect = null
            holdStudy()
            _uiState.value = base.copy(
                completed = true,
                canContinueLearning = false,
                locked = false,
                holdResult = false,
                answerVisible = false,
                learnMissed = false,
                problemCount = roundProblemIds.size,
                wrongWords = roundProblemIds.size,
            )
            refreshContinueAvailability()
            return
        }
        showMemory(next, base)
    }

    private fun refreshContinueAvailability() {
        if (purpose != TypingPurpose.MAINLINE_LEARN) return
        viewModelScope.launch {
            val settings = container.preferences.settings.first()
            val more = container.repository.moreLearnWords(settings, originalWords.map { it.id }.toSet())
            if (_uiState.value.completed) {
                _uiState.value = _uiState.value.copy(canContinueLearning = more.isNotEmpty())
            }
        }
    }

    private fun showMemory(state: MemoryState, base: TypingUiState) {
        val word = originalWords.firstOrNull { it.id == state.wordId }
        val clozeQuestion = word?.let { LearningContentPresentation.clozeFor(container.applicationContext, it) }
        val hasExample = clozeQuestion != null
        val form = MemoryPractice.presentedForm(state.form, hasExample)
        val shown = state.copy(
            form = form,
            hasExample = hasExample,
            abilities = if (hasExample || state.abilities.context != 0) state.abilities else state.abilities.copy(context = -1),
        )
        memory = shown
        pendingEffect = null
        val serial = base.cardSerial + 1
        _uiState.value = base.copy(
            words = listOfNotNull(word),
            currentIndex = 0,
            input = "",
            currentWrong = 0,
            currentError = false,
            locked = true,
            isFavorite = false,
            practiceForm = form,
            cleanRecalls = shown.cleanRecalls,
            skillProgress = shown.plan?.targets?.joinToString(" · ") {
                "${it.skill.label} ${it.passed}/${it.required}"
            }.orEmpty(),
            answerVisible = false,
            learnMissed = false,
            holdResult = false,
            assisted = shown.hinted,
            cardSerial = serial,
            choices = emptyList(),
            fadeHint = if (form == PracticeForm.FADED) MemoryPractice.letterHint(canonical(word?.word.orEmpty())) else "",
            cloze = if (form == PracticeForm.CLOZE) {
                clozeQuestion?.prompt.orEmpty()
            } else {
                ""
            },
            feedback = "",
            started = true,
        )
        refreshFavorite()
        if (word != null) {
            val previous = evidenceWrite
            evidenceWrite = viewModelScope.launch {
                val access = withContext(NonCancellable) {
                    previous?.join()
                    container.repository.prepareRetrieval(word.id, form.name)
                }
                if (_uiState.value.cardSerial != serial || memory?.wordId != word.id) return@launch
                memory = shown.copy(exposed = shown.exposed || access.exposed)
                _uiState.value = _uiState.value.copy(locked = false)
            }
        }
        if ((form == PracticeForm.PICK_MEANING || form == PracticeForm.PICK_SPELLING) && word != null) {
            loadChoices(word, form, serial)
        }
    }

    private fun loadChoices(word: WordEntity, form: PracticeForm, serial: Int) {
        viewModelScope.launch {
            val options = container.repository.getOptions(word, 4)
            if (_uiState.value.cardSerial != serial) return@launch
            _uiState.value = _uiState.value.copy(
                choices = options.map { option ->
                    val label = if (form == PracticeForm.PICK_MEANING) {
                        WordPresentation.cleanTranslation(option.translation).ifBlank { option.word }
                    } else {
                        LearningContentPresentation.canonicalSpelling(option.word)
                    }
                    PracticeChoice(option.id, label)
                },
            )
        }
    }

    private fun feedbackText(move: PracticeMove): String = when (move) {
        PracticeMove.HARDER -> "对了，继续本词需要练习的分项"
        PracticeMove.AGAIN -> "对了，等会儿再考一次"
        PracticeMove.INTRODUCE -> "先看，再写"
        PracticeMove.EASIER -> "看答案，继续练习刚才没通过的分项"
    }

    private fun gradeFeedback(grade: RetentionGrade, days: Int = 0): String = when (grade) {
        RetentionGrade.GOOD -> if (days > 0) "首次检查通过，下次分项检查再隔 $days 天" else "首次检查通过"
        RetentionGrade.LAPSE -> "有分项没独立想起来，明天再查"
        RetentionGrade.RECHECK -> "看过或听过答案，本次作为练习，明天再查"
        RetentionGrade.PRACTICE -> "这次练习通过了"
    }

    private fun logAttempt(card: MemoryState, form: PracticeForm, correct: Boolean) {
        val previous = evidenceWrite
        evidenceWrite = viewModelScope.launch {
            withContext(NonCancellable) {
                previous?.join()
                container.repository.logRetrieval(
                    wordId = card.wordId,
                    correct = correct,
                    type = questionType(form),
                    form = form.name,
                    firstAttempt = MemorySkill.forForm(form.name)?.let { card.plan?.first(it) } ?: (card.firstResult == FirstResult.NONE),
                    hinted = card.hinted,
                    eventKind = "answer",
                    mainline = true,
                    source = when (purpose) {
                        TypingPurpose.RELEARN -> MemorySource.REPAIR
                        TypingPurpose.MAINLINE_CONSOLIDATE -> MemorySource.REVIEW
                        else -> MemorySource.LEARN
                    },
                    exposedBeforeQuestion = card.exposed,
                )
            }
        }
    }

    private fun launchSessionSave(state: MemoryState) {
        val requestedAt = System.currentTimeMillis()
        viewModelScope.launch {
            withContext(NonCancellable) { container.repository.savePracticeSession(state.toSession(), requestedAt) }
        }
    }

    private fun launchRetention(
        wordId: Long,
        grade: RetentionGrade,
        state: MemoryState,
        formName: String,
        card: MemoryState,
    ) {
        viewModelScope.launch {
            val days = withContext(NonCancellable) {
                evidenceWrite?.join()
                container.repository.recordRetention(
                    wordId = wordId,
                    grade = grade,
                    meaning = state.abilities.meaning,
                    listening = state.abilities.listening,
                    spelling = state.abilities.spelling,
                    context = state.abilities.context,
                    practiceForm = formName,
                    firstAttempt = card.firstResult == FirstResult.NONE,
                    hinted = card.hinted,
                    independentEvidence = card.firstResult == FirstResult.CORRECT || card.firstResult == FirstResult.WRONG ||
                        (card.firstResult == FirstResult.NONE && !card.hinted),
                )
            }
            val nextPlan = container.repository.nextSkillReviewDescription(wordId)
            if (days > 0) {
                _uiState.value = _uiState.value.copy(
                    feedback = if (_uiState.value.currentWord?.id == wordId && _uiState.value.holdResult)
                        listOf(gradeFeedback(grade, days), nextPlan).filter { it.isNotBlank() }.joinToString("\n")
                        else _uiState.value.feedback,
                    scheduledDays = if (grade == RetentionGrade.GOOD) _uiState.value.scheduledDays + days else _uiState.value.scheduledDays,
                )
            }
        }
    }

    private fun isRetentionReview(): Boolean =
        purpose == TypingPurpose.MAINLINE_CONSOLIDATE || purpose == TypingPurpose.RELEARN

    private fun persistCurrentSession() {
        val card = memory ?: return
        launchSessionSave(card)
    }

    private fun questionType(form: PracticeForm): QuestionType = when (form) {
        PracticeForm.PICK_MEANING -> QuestionType.MEANING
        PracticeForm.LISTEN -> QuestionType.LISTENING
        else -> QuestionType.SPELLING
    }

    private fun MemoryState.toSession() = PracticeSession(
        wordId = wordId,
        formName = form.name,
        hits = cleanRecalls,
        misses = misses,
        hinted = hinted,
        firstResult = firstResult.name,
        meaning = abilities.meaning,
        listening = abilities.listening,
        spelling = abilities.spelling,
        context = abilities.context,
        plan = plan?.encode().orEmpty(),
    )

    private fun PracticeSnapshot.toSaved(): SavedPractice {
        val legacy = formName.isBlank() && attempts == 0 && !learned && level > 0
        val form = formName.takeIf { it.isNotBlank() }?.let { runCatching { PracticeForm.valueOf(it) }.getOrNull() }
            ?: if (legacy) MemoryPractice.formForRung(level) else null
        val first = runCatching { FirstResult.valueOf(firstResult) }.getOrDefault(FirstResult.NONE)
        return SavedPractice(
            form = form,
            hits = hits,
            misses = misses,
            hinted = hinted,
            firstResult = first,
            abilities = AbilityMarks(meaning, listening, spelling, context),
            hasSession = formName.isNotBlank() || legacy,
            skillMemory = skillMemory,
            plan = SkillPracticePlan.decode(plan),
        )
    }

    private fun holdResult(state: TypingUiState, correct: Boolean, feedback: String, advance: () -> Unit) {
        pendingAdvance = advance
        _uiState.value = state.copy(
            locked = true,
            answerVisible = true,
            learnMissed = !correct,
            holdResult = true,
            currentError = !correct,
            feedback = feedback,
        )
    }

    private fun finishWord(state: TypingUiState, typed: Int, correct: Int) {
        val word = state.currentWord ?: return
        val hadWrong = state.currentWrong > 0
        viewModelScope.launch { container.repository.recordTypingCompletion(word.id, purpose, shaky = hadWrong) }
        completedOriginalIds += word.id
        val shown = state.copy(
            input = letterCharacters(canonical(word.word)),
            typedChars = state.typedChars + typed,
            correctChars = state.correctChars + correct,
        )
        holdResult(shown, correct = !hadWrong, feedback = if (hadWrong) "这个词写对了，但刚才有字母改过" else "对了") {
            moveNext(
                _uiState.value.copy(
                    completedWords = _uiState.value.completedWords + 1,
                    completedOriginalWords = completedOriginalIds.size,
                    wrongWords = _uiState.value.wrongWords + if (hadWrong) 1 else 0,
                    holdResult = false,
                    answerVisible = false,
                    locked = false,
                ),
            )
        }
    }

    private fun moveNext(state: TypingUiState) {
        if (state.currentIndex + 1 >= state.words.size) {
            holdStudy()
            _uiState.value = state.copy(completed = true, locked = false, problemCount = roundProblemIds.size)
            return
        }
        _uiState.value = state.copy(
            currentIndex = state.currentIndex + 1,
            input = "",
            currentWrong = 0,
            currentError = false,
            locked = false,
            isFavorite = false,
        )
        refreshFavorite()
    }

    private fun startRound(words: List<WordEntity>) {
        if (words.isEmpty()) return
        studyClock.start(System.currentTimeMillis())
        roundProblemIds.clear()
        _uiState.value = _uiState.value.copy(
            words = words,
            round = _uiState.value.round + 1,
            currentIndex = 0,
            input = "",
            currentWrong = 0,
            currentError = false,
            locked = false,
            completed = false,
            problemCount = 0,
            started = true,
        )
        refreshFavorite()
    }

    private fun refreshFavorite() {
        val wordId = _uiState.value.currentWord?.id ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isFavorite = container.repository.progressFor(wordId)?.isFavorite == true)
        }
    }

    private fun canonical(value: String): String = LearningContentPresentation.canonicalSpelling(value).replace("…", "..")
    private fun letterCharacters(value: String): String = LetterInput.letters(value)

    companion object {
        fun factory(
            container: AppContainer,
            purpose: TypingPurpose,
            reviewScope: String = "",
            beyondPlan: Boolean = false,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    TypingViewModel(container, purpose, reviewScope, beyondPlan) as T
            }

        fun reviewListLabel(scope: String): String = when (scope) {
            "pending" -> "待复习"
            "mastered" -> "已掌握"
            "fixed" -> "已牢记"
            "favorite" -> "收藏"
            else -> ""
        }
    }
}
