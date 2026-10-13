package com.alanbeidanci.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.alanbeidanci.app.ui.formatElapsed
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.audio.PronunciationPlayer
import com.alanbeidanci.app.data.TypingPurpose
import com.alanbeidanci.app.data.UserSettings
import com.alanbeidanci.app.data.LearningContentPresentation
import com.alanbeidanci.app.data.WordPresentation
import com.alanbeidanci.app.ui.LetterInput
import com.alanbeidanci.app.ui.MemoryPractice
import com.alanbeidanci.app.ui.PracticeForm
import com.alanbeidanci.app.ui.TypingUiState
import com.alanbeidanci.app.ui.TypingViewModel
import com.alanbeidanci.app.ui.components.AppCard
import com.alanbeidanci.app.ui.components.LearningContentCard
import com.alanbeidanci.app.ui.components.LetterBoxInput
import com.alanbeidanci.app.ui.components.PronunciationNotice
import com.alanbeidanci.app.ui.components.WordInputBar

@Composable
fun TypingStudyScreen(
    container: AppContainer,
    purpose: TypingPurpose,
    settings: UserSettings,
    onClose: () -> Unit,
    onSelectBooks: () -> Unit,
    viewModel: TypingViewModel = viewModel(factory = TypingViewModel.factory(container, purpose)),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val pronunciation = remember(context) { PronunciationPlayer(context) }
    fun speak(text: String) {
        pronunciation.speak(text, accent = settings.accent, rate = settings.studySpeechRate)
    }
    DisposableEffect(pronunciation) { onDispose { pronunciation.release() } }
    fun requestSpeak() {
        val form = state.practiceForm
        val leaksAnswer = state.isLearnRecall && !state.answerVisible && !state.learnMissed &&
            form in setOf(PracticeForm.RECALL, PracticeForm.FADED, PracticeForm.PICK_SPELLING, PracticeForm.CLOZE)
        if (leaksAnswer) viewModel.notePronunciationHint()
        state.currentWord?.let { speak(it.word) }
    }
    LaunchedEffect(state.currentWord?.id, state.cardSerial, state.started, state.practiceForm, settings.autoSpeak, settings.accent, settings.studySpeechRate) {
        if (!state.started || state.learnMissed) return@LaunchedEffect
        val form = state.practiceForm
        val shouldSpeak = when {
            state.isLearnRecall && form == PracticeForm.LISTEN -> true
            state.isLearnRecall && form in setOf(PracticeForm.RECALL, PracticeForm.FADED, PracticeForm.PICK_SPELLING, PracticeForm.CLOZE) -> false
            else -> settings.autoSpeak
        }
        if (shouldSpeak) state.currentWord?.let { speak(it.word) }
    }
    LaunchedEffect(state.practiceForm, state.cardSerial) {
        if (state.isLearnRecall && state.practiceForm in setOf(PracticeForm.STUDY, PracticeForm.PICK_MEANING, PracticeForm.PICK_SPELLING)) {
            keyboard?.hide()
        }
    }
    val close = { viewModel.finish(); onClose() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onStudyForeground()
                Lifecycle.Event.ON_STOP -> viewModel.onStudyBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.confirmed) {
        if (state.confirmed) close()
    }
    BackHandler(onBack = close)

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (state.noBooks) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("请先选择词库", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text("进入学习前需要先选择至少一个词库。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Button(onClick = onSelectBooks, shape = RoundedCornerShape(14.dp)) { Text("去选择词库") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = close, shape = RoundedCornerShape(14.dp)) { Text("返回") }
        }
        return
    }
    if (state.completed) {
        TypingComplete(
            state = state,
            onConfirmReview = viewModel::confirmReview,
            onRepeatProblems = viewModel::repeatProblems,
            onRepeatAll = viewModel::repeatAll,
            onClose = close,
        )
        return
    }
    if (!state.started) {
        TypingStartGate(state, onStart = viewModel::start, onClose = close)
        return
    }

    var elapsedMs by remember { mutableStateOf(0L) }
    LaunchedEffect(state.started, state.completed) {
        if (!state.started || state.completed) return@LaunchedEffect
        while (true) {
            viewModel.tickStudyClock()
            elapsedMs = viewModel.activeElapsedMs()
            delay(1000)
        }
    }

    val word = state.currentWord ?: return
    val wordPartOfSpeech = remember(word.id, word.partOfSpeech, word.translation) {
        WordPresentation.partOfSpeech(context, word)
    }
    val wordTranslation = remember(word.id, word.translation) {
        WordPresentation.cleanTranslation(word.translation)
    }
    val displayWord = remember(word.id, word.word) {
        LearningContentPresentation.canonicalSpelling(word.word)
    }
    var phoneticLine by remember(word.id) {
        mutableStateOf(WordPresentation.phoneticLine(word.phoneticUs, word.phoneticUk))
    }
    LaunchedEffect(word.id) {
        if (phoneticLine.isNotBlank()) return@LaunchedEffect
        val found = container.repository.phoneticFor(word)
        phoneticLine = WordPresentation.phoneticLine(found.first, found.second)
    }
    val learningContent = remember(word.id, word.bookId, word.word, word.example) {
        LearningContentPresentation.forWord(context, word)
    }

    val wordPop = remember { Animatable(1f) }
    LaunchedEffect(state.cardSerial, state.currentWord?.id) {
        wordPop.snapTo(1.08f)
        wordPop.animateTo(1f, animationSpec = tween(240))
    }
    val shakeTransition = rememberInfiniteTransition(label = "typing-shake")
    val shakeOffset by shakeTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(70, easing = LinearEasing), RepeatMode.Reverse),
        label = "typing-shake-offset",
    )

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = close) { Icon(Icons.Outlined.Close, contentDescription = "退出学习") }
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer,
                )
                Text(formatElapsed(elapsedMs), modifier = Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    if (state.isLearnRecall) "${state.graduatedCount}/${state.originalWordCount}" else "${state.currentIndex + 1}/${state.words.size}",
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                IconButton(onClick = viewModel::toggleFavorite) {
                    Icon(if (state.isFavorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = "收藏", tint = if (state.isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                when {
                    state.isReview -> "错弱词复习 · 直接输入"
                    state.isConsolidation -> "巩固"
                    else -> "新词"
                },
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = if (state.isReview) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    when {
                        state.isReview -> "复习模式 · 第 ${state.round} 轮"
                        state.learnMissed -> "看正确答案"
                        else -> practiceLabel(state)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.isReview) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            PronunciationNotice(pronunciation)
            AppCard(Modifier.fillMaxWidth().graphicsLayer {
                scaleX = wordPop.value
                scaleY = wordPop.value
            }) {
                val showSpelling = !state.isLearnRecall || state.answerVisible || state.practiceForm == PracticeForm.STUDY || state.practiceForm == PracticeForm.PICK_MEANING
                val showMeaning = !state.isLearnRecall || state.answerVisible || state.practiceForm in setOf(
                    PracticeForm.STUDY, PracticeForm.PICK_SPELLING, PracticeForm.FADED, PracticeForm.RECALL, PracticeForm.CLOZE,
                )
                val showDetails = !state.isLearnRecall || state.practiceForm == PracticeForm.STUDY || state.learnMissed
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (showSpelling) {
                        Text(displayWord, fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        if (phoneticLine.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                phoneticLine,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 18.sp,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    } else if (state.practiceForm == PracticeForm.LISTEN) {
                        Text("听发音，写出单词", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    } else if (state.practiceForm == PracticeForm.CLOZE && state.cloze.isNotBlank()) {
                        Text(state.cloze, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                    } else if (state.practiceForm == PracticeForm.FADED) {
                        Text(state.fadeHint, fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, letterSpacing = 2.sp)
                        Spacer(Modifier.height(8.dp))
                    } else {
                        Text("写出这个词", style = MaterialTheme.typography.titleMedium)
                    }
                    if (state.practiceForm != PracticeForm.CLOZE || state.answerVisible || !state.isLearnRecall) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { requestSpeak() }) {
                                Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = "播放发音", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                    if (showMeaning) {
                        Spacer(Modifier.height(14.dp))
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Column(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                if (wordPartOfSpeech.isNotBlank()) {
                                    Text(
                                        wordPartOfSpeech,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                                Text(
                                    wordTranslation,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                    if (showDetails && learningContent.hasContent) {
                        Spacer(Modifier.height(18.dp))
                        LearningContentCard(content = learningContent, onSpeak = ::speak)
                    }
                }
            }
        }
        Column(Modifier.navigationBarsPadding().imePadding()) {
        when {
            state.isLearnRecall && state.practiceForm == PracticeForm.STUDY && !state.answerVisible -> LearnActionBar(
                primary = "看完了，开始写",
                onPrimary = viewModel::acknowledgeStudy,
                onSkip = viewModel::skipLearnWord,
            )
            state.holdResult -> Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp)) {
                Text(
                    state.feedback,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = if (state.learnMissed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                LetterBoxInput(
                    target = displayWord,
                    value = state.input,
                    enabled = false,
                    autoFocus = state.cardSerial to "result",
                    markCorrectness = true,
                    onInput = {},
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = viewModel::continueAfterResult,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("下一个", fontWeight = FontWeight.Bold)
                }
            }
            state.isLearnRecall && state.learnMissed -> LearnActionBar(
                primary = "继续",
                onPrimary = viewModel::continueAfterMiss,
                onSkip = viewModel::skipLearnWord,
            )
            state.isLearnRecall && state.answerVisible -> Text(
                state.feedback,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            state.isLearnRecall && (state.practiceForm == PracticeForm.PICK_MEANING || state.practiceForm == PracticeForm.PICK_SPELLING) -> PracticeChoices(
                choices = state.choices,
                onChoose = viewModel::choose,
                onSkip = viewModel::skipLearnWord,
            )
            else -> WordInputBar(
                hint = when {
                    state.isLearnRecall -> "写完整词后再判断，写错可以删掉重写"
                    state.input.length > LetterInput.letters(displayWord).length -> "多输入了，删掉多余的字母"
                    state.currentError -> "这个字母不对，删掉再改"
                    else -> "点错了可以直接删除"
                },
                hintError = !state.isLearnRecall && (state.currentError || state.input.length > LetterInput.letters(displayWord).length),
                wrongCount = state.currentWrong,
                skipAvailable = if (state.isLearnRecall) true else state.skipAvailable,
                onSkip = if (state.isLearnRecall) viewModel::skipLearnWord else viewModel::skip,
            ) {
                LetterBoxInput(
                    target = displayWord,
                    value = state.input,
                    enabled = !state.isLearnRecall || !state.locked,
                    autoFocus = if (state.isLearnRecall) state.cardSerial else state.currentWord?.id,
                    markCorrectness = !state.isLearnRecall,
                    modifier = Modifier.fillMaxWidth().graphicsLayer {
                        translationX = if (state.currentError) shakeOffset else 0f
                    },
                    onInput = viewModel::updateInput,
                    onEnter = { requestSpeak() },
                )
            }
        }
        }
    }
}

@Composable
private fun practiceLabel(state: TypingUiState): String = when (state.practiceForm) {
    PracticeForm.STUDY -> "先看这个词"
    PracticeForm.PICK_MEANING -> "选出中文意思"
    PracticeForm.PICK_SPELLING -> "选出正确拼写"
    PracticeForm.FADED -> "补全字母"
    PracticeForm.RECALL -> recallLabel(state)
    PracticeForm.LISTEN -> productionLabel(state, "听写")
    PracticeForm.CLOZE -> productionLabel(state, "例句填空")
}

private fun recallLabel(state: TypingUiState): String {
    val assisted = if (state.assisted) " · 已听过，不算独立回忆" else ""
    return if (state.isConsolidation) {
        "凭中文默写，不放发音$assisted"
    } else {
        "凭中文默写 · 已通过 ${state.cleanRecalls}/${MemoryPractice.PRODUCTION_GOAL}$assisted"
    }
}

private fun productionLabel(state: TypingUiState, name: String): String =
    if (state.isConsolidation) name else "$name · 已通过 ${state.cleanRecalls}/${MemoryPractice.PRODUCTION_GOAL}"

@Composable
private fun LearnActionBar(
    primary: String,
    onPrimary: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp)) {
        Button(
            onClick = onPrimary,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(primary, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("这个词下次再学")
        }
    }
}

@Composable
private fun PracticeChoices(
    choices: List<com.alanbeidanci.app.ui.PracticeChoice>,
    onChoose: (Long) -> Unit,
    onSkip: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (choices.isEmpty()) {
            Text("正在出选项…", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            choices.forEach { choice ->
                OutlinedButton(
                    onClick = { onChoose(choice.wordId) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(choice.label, textAlign = TextAlign.Center)
                }
            }
        }
        TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("这个词下次再学")
        }
    }
}

@Composable
private fun TypingStartGate(
    state: TypingUiState,
    onStart: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "退出学习") }
                Text(
                    if (state.isReview) "${state.originalWordCount} 个词 · ${state.words.size} 次复习" else "共 ${state.words.size} 个单词",
                    modifier = Modifier.padding(horizontal = 10.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (state.isConsolidation) "开始巩固" else if (state.isReview) "开始复习" else "开始学习",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (state.isReview) {
                            "本次 ${state.originalWordCount} 个错弱词，共安排 ${state.words.size} 次复习${if (state.isMainlineReview) "；只含昨日及更早的主线错题与薄弱词" else ""}"
                        } else if (state.isConsolidation) {
                            "本次 ${state.words.size} 个到了复习时间的词。先凭中文写，不放发音。第一次就写对，下次才隔更久；没想起来就明天再查。"
                        } else {
                            "本次 ${state.words.size} 个新词。先看，再凭中文默写，并分开确认词义、听写和例句。写对 3 次只算这次练习通过。只剩一个词时，不会连着让你再写。"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (state.scopeLabel.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                "范围：${state.scopeLabel}",
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text("开始", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TypingComplete(
    state: TypingUiState,
    onConfirmReview: () -> Unit,
    onRepeatProblems: () -> Unit,
    onRepeatAll: () -> Unit,
    onClose: () -> Unit,
) {
    val accuracy = if (state.typedChars == 0) 0 else state.correctChars * 100 / state.typedChars
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(84.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(20.dp))
        Text(
            when {
                state.isMainlineReview -> "本轮复习完成"
                state.isConsolidation -> "这次巩固查完了"
                state.isReview -> "自主复习完成"
                state.deferredCount > 0 && state.graduatedCount == 0 -> "这轮先停一下"
                else -> "这次练习做完了"
            },
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            if (state.isMainlineReview) {
                "第 ${state.round} 轮 · 已完成原批次 ${state.completedOriginalWords}/${state.originalWordCount} 词 · 本轮需加强 ${state.problemCount} 词"
            } else if (state.isConsolidation) {
                val days = state.scheduledDays
                val next = when {
                    days.isEmpty() -> "自己想起来的，下次按算好的天数再出现。"
                    days.min() == days.max() -> "自己想起来的，下次再隔 ${days.min()} 天。"
                    else -> "自己想起来的，下次再隔 ${days.min()} 到 ${days.max()} 天。"
                }
                "自己想起来 ${state.cleanFinishes} 个，没想起来 ${state.lapseFinishes} 个，听过再写对 ${state.recheckFinishes} 个。$next 其余明天再查。跳过不算忘。"
            } else if (state.isLearnRecall) {
                "练习通过 ${state.graduatedCount}/${state.originalWordCount} 个，字符正确率 $accuracy%。这只是这次练习通过。回到首页还要听写和看中文各测一次，通过后明天再查，才开始算记住。" +
                    (if (state.deferredCount > 0) "还有 ${state.deferredCount} 个下次接着写，不用现在连着输入。" else "") +
                    if (state.parkedCount > 0) "还有 ${state.parkedCount} 个没写稳，下次从更容易的题开始。" else ""
            } else {
                "完成 ${state.completedWords} 个单词，字符正确率 $accuracy%，需加强 ${state.wrongWords} 个。"
            },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        if (state.isMainlineReview) {
            Button(
                onClick = onConfirmReview,
                enabled = state.canConfirmReview,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
            ) {
                Text(
                    if (state.canConfirmReview) "确认复习完成，进入补漏双测" else "还有 ${state.originalWordCount - state.completedOriginalWords} 词未完成",
                    fontWeight = FontWeight.Bold,
                )
            }
            if (state.problemCount > 0) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onRepeatProblems, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(18.dp)) {
                    Text("再复习本轮需加强词（${state.problemCount}）")
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onRepeatAll, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(18.dp)) {
                Text("全部再复习一遍")
            }
            Spacer(Modifier.height(10.dp))
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(18.dp)) {
            Text("返回首页")
        }
    }
}
