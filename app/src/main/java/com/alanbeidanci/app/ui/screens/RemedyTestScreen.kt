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
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.audio.PronunciationPlayer
import com.alanbeidanci.app.data.UserSettings
import com.alanbeidanci.app.data.LearningContentPresentation
import com.alanbeidanci.app.data.WordPresentation
import com.alanbeidanci.app.data.MainlineScheduler
import com.alanbeidanci.app.data.MainlineTestKind
import com.alanbeidanci.app.ui.RemedyRound
import com.alanbeidanci.app.ui.RemedyTestViewModel
import com.alanbeidanci.app.ui.RemedyUiState
import com.alanbeidanci.app.ui.components.AppCard
import com.alanbeidanci.app.ui.components.LetterBoxInput
import com.alanbeidanci.app.ui.components.PronunciationNotice
import com.alanbeidanci.app.ui.components.WordInputBar
import com.alanbeidanci.app.ui.formatElapsed
import kotlinx.coroutines.delay

@Composable
fun RemedyTestScreen(
    container: AppContainer,
    settings: UserSettings,
    kind: MainlineTestKind,
    onClose: () -> Unit,
    viewModel: RemedyTestViewModel = viewModel(factory = RemedyTestViewModel.factory(container, kind)),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val pronunciation = remember(context) { PronunciationPlayer(context) }
    fun speak(text: String, chinese: Boolean = false) {
        pronunciation.speak(text, accent = settings.accent, rate = settings.studySpeechRate, chinese = chinese)
    }
    DisposableEffect(pronunciation) { onDispose { pronunciation.release() } }
    LaunchedEffect(state.currentIndex, state.round, state.started, settings.autoSpeak, settings.accent, settings.studySpeechRate) {
        if (!state.started) return@LaunchedEffect
        state.currentWord?.let { word ->
            if (settings.autoSpeak) {
                if (state.round == RemedyRound.ENGLISH) speak(word.word) else speak(WordPresentation.cleanTranslation(word.translation), chinese = true)
            }
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
    BackHandler(onBack = close)

    when {
        state.loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        state.noBooks -> {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("请先选择词库", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text("进入学习前需要先选择至少一个词库。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                Button(onClick = close, shape = RoundedCornerShape(14.dp)) { Text("返回") }
            }
            return
        }
        state.words.isEmpty() -> {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("当前主线步骤已经完成", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text("返回首页后，系统会自动进入下一步。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                Button(onClick = close, shape = RoundedCornerShape(14.dp)) { Text("返回首页") }
            }
            return
        }
        state.completed -> {
            RemedyComplete(state, onClose = close)
            return
        }
        !state.started -> {
            RemedyStartGate(state, onStart = viewModel::start, onClose = close)
            return
        }
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
    val shuffleTransition = rememberInfiniteTransition(label = "remedy-shake")
    val shakeOffset by shuffleTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(70, easing = LinearEasing), RepeatMode.Reverse),
        label = "remedy-shake-offset",
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
                Text("${state.questionIndex}/${state.words.size * 2}", modifier = Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("${state.title} · ${if (state.round == RemedyRound.ENGLISH) "听音轮" else "中文轮"}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, letterSpacing = 2.sp)
            Spacer(Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(12.dp), color = if (state.round == RemedyRound.CHINESE) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    if (state.round == RemedyRound.ENGLISH) "听英文发音" else "听中文释义",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (state.round == RemedyRound.CHINESE) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            PronunciationNotice(pronunciation)
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        onClick = {
                            if (state.round == RemedyRound.ENGLISH) speak(word.word) else speak(wordTranslation, chinese = true)
                        },
                        shape = CircleShape,
                        color = if (state.round == RemedyRound.CHINESE) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(96.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (state.round == RemedyRound.ENGLISH) Icons.AutoMirrored.Outlined.VolumeUp else Icons.Outlined.GraphicEq,
                                contentDescription = "播放",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(42.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    if (state.round == RemedyRound.CHINESE) {
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                            Text(
                                wordTranslation,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else if (state.holdResult && !state.holdCorrect) {
                        Text("正确拼写", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(displayWord, fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        if (wordTranslation.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(wordTranslation, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        }
                    } else {
                        Text("请根据发音写出单词", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (state.round == RemedyRound.CHINESE && state.holdResult && !state.holdCorrect) {
                        Spacer(Modifier.height(16.dp))
                        Text("正确拼写", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(8.dp))
                        Text(displayWord, fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
            }
            if (
                state.round == RemedyRound.ENGLISH &&
                MainlineScheduler.hasEnglishHomophoneConflict(word, state.words, state.accent)
            ) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("同音词提示", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(
                            listOf(wordPartOfSpeech, wordTranslation).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        MainlineScheduler.blankExample(word.example, displayWord).takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
            }
        }
        Column(Modifier.navigationBarsPadding().imePadding()) {
        if (state.holdResult) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp)) {
                Text(
                    when {
                        state.holdCorrect -> "对了"
                        state.input.isBlank() -> "不会。正确拼写在上面"
                        else -> "错了。红字是没写对的字母，正确拼写在上面"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = if (state.holdCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
                if (!state.holdCorrect) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        displayWord,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (wordTranslation.isNotBlank()) {
                        Text(
                            wordTranslation,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.input.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    LetterBoxInput(
                        target = displayWord,
                        value = state.input,
                        enabled = false,
                        autoFocus = state.currentIndex to "result",
                        markCorrectness = true,
                        onInput = {},
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = viewModel::acknowledgeResult,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text("下一个", fontWeight = FontWeight.Bold)
                }
            }
        } else WordInputBar(
            hint = "写完整个词再判断",
            hintError = state.currentError,
            wrongCount = state.currentWrong,
            skipAvailable = state.skipAvailable,
            onSkip = viewModel::skip,
            giveUpAvailable = true,
            onGiveUp = viewModel::giveUp,
        ) {
            LetterBoxInput(
                target = displayWord,
                value = state.input,
                enabled = true,
                autoFocus = state.currentIndex to state.round,
                markCorrectness = false,
                modifier = Modifier.fillMaxWidth().graphicsLayer {
                    translationX = if (state.currentError) shakeOffset else 0f
                },
                onInput = viewModel::updateInput,
                onEnter = {
                    if (state.round == RemedyRound.ENGLISH) speak(word.word) else speak(wordTranslation, chinese = true)
                },
            )
        }
        }
    }
}

@Composable
private fun RemedyStartGate(
    state: RemedyUiState,
    onStart: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "退出学习") }
                Text("共 ${state.words.size} 个单词", modifier = Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 22.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.title, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (state.kind) {
                            MainlineTestKind.REMEDY -> "本次共 ${state.words.size} 个单词；英听和中听均全对算 1 次，连续 2 次全对才重新进入已掌握"
                            MainlineTestKind.CONSOLIDATION -> "本次共 ${state.words.size} 个单词；按 3→7→15→30 天巩固，第 5 次全对升级为已牢记"
                            MainlineTestKind.NEW_WORDS -> "本次共 ${state.words.size} 个新词；听音与中文两轮完成后归类为已掌握、薄弱或错题"
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
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
private fun RemedyComplete(state: RemedyUiState, onClose: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(84.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text("✓", color = MaterialTheme.colorScheme.primary, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        }
        Text("${state.title}完成", style = MaterialTheme.typography.headlineLarge)
        Text(
            "听音 ${state.listenScore}/${state.words.size} · 中文 ${state.chineseScore}/${state.words.size}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text("双轮全对 ${state.bothCorrect}/${state.words.size}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.headlineSmall)
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("本次单词", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                state.words.forEach { word ->
                    val partOfSpeech = WordPresentation.partOfSpeech(context, word)
                    val translation = WordPresentation.cleanTranslation(word.translation)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(word.word, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(
                            listOf(partOfSpeech, translation).filter { it.isNotBlank() }.joinToString(" · "),
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) { Text("返回首页", fontWeight = FontWeight.Bold) }
        Spacer(Modifier.height(12.dp))
    }
}
