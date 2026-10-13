package com.alanbeidanci.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.audio.PronunciationPlayer
import com.alanbeidanci.app.data.UserSettings
import com.alanbeidanci.app.network.WordLookupItem
import com.alanbeidanci.app.ui.SearchViewModel
import com.alanbeidanci.app.ui.components.AppCard
import com.alanbeidanci.app.ui.components.PageHeading
import com.alanbeidanci.app.ui.components.PronunciationNotice

@Composable
fun SearchScreen(
    container: AppContainer,
    viewModel: SearchViewModel = viewModel(factory = SearchViewModel.factory(container)),
) {
    val state by viewModel.uiState.collectAsState()
    val settings by container.preferences.settings.collectAsState(initial = UserSettings())
    val context = LocalContext.current
    val pronunciation = remember(context) { PronunciationPlayer(context) }
    fun speak(text: String) {
        pronunciation.speak(text, accent = settings.accent, rate = settings.studySpeechRate)
    }
    DisposableEffect(pronunciation) { onDispose { pronunciation.release() } }
    var scanning by remember { mutableStateOf(false) }
    if (scanning) {
        QrScannerScreen(
            onResult = {
                scanning = false
                viewModel.scan(it)
            },
            onClose = { scanning = false },
        )
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.padding(top = 16.dp)) {
                PageHeading("Dictionary", "查单词", "输入英文单词或中文释义，或对准英文单词拍照识别。")
                Spacer(Modifier.height(18.dp))
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth().testTag("word_query"),
                    label = { Text("英文或中文") },
                    placeholder = { Text("例如 achieve、实现") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = viewModel::search,
                        enabled = !state.loading,
                        modifier = Modifier.weight(1f).height(50.dp).testTag("word_search_submit"),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        if (state.loading) CircularProgressIndicator(strokeWidth = 2.dp) else Text("查询")
                    }
                    OutlinedButton(
                        onClick = { scanning = true },
                        enabled = !state.loading,
                        modifier = Modifier.weight(1f).height(50.dp).testTag("word_scan"),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                        Text("拍照查询", modifier = Modifier.padding(start = 7.dp))
                    }
                }
                PronunciationNotice(pronunciation)
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
                }
                if (state.searched && state.results.isEmpty() && !state.loading) {
                    Text("暂时没有找到“${state.resolvedQuery}”，可以换个关键词。", modifier = Modifier.padding(vertical = 20.dp))
                }
                if (state.results.isNotEmpty()) {
                    Text(
                        "找到 ${state.results.size} 个结果",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                }
            }
        }
        items(state.results, key = { it.id }) { WordResultCard(it, onSpeak = { speak(it.word) }) }
        if (state.aiLoading || state.aiAnswer.isNotBlank()) {
            item {
                AiResultCard(
                    loading = state.aiLoading,
                    answer = state.aiAnswer,
                    word = state.results.firstOrNull()?.word.orEmpty(),
                    phoneticUs = state.results.firstOrNull()?.phoneticUs.orEmpty(),
                    phoneticUk = state.results.firstOrNull()?.phoneticUk.orEmpty(),
                    onSpeak = { speak(state.results.firstOrNull()?.word.orEmpty()) },
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun AiResultCard(
    loading: Boolean,
    answer: String,
    word: String,
    phoneticUs: String,
    phoneticUk: String,
    onSpeak: () -> Unit,
) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AI 扩展解释" + if (word.isNotBlank()) " · $word" else "", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    if (phoneticUs.isNotBlank() || phoneticUk.isNotBlank()) {
                        Text(
                            listOfNotNull(
                                phoneticUs.takeIf { it.isNotBlank() }?.let { "美 $it" },
                                phoneticUk.takeIf { it.isNotBlank() }?.let { "英 $it" },
                            ).joinToString("　"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                if (word.isNotBlank() && answer.isNotBlank()) {
                    IconButton(onClick = onSpeak, modifier = Modifier.testTag("ai_speak")) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = "朗读单词", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (loading) {
                Text(
                    "正在生成 AI 扩展解释…",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(answer, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                Text(
                    "AI 内容仅供学习参考，不会自动写入词库。",
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WordResultCard(item: WordLookupItem, onSpeak: () -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onSpeak, modifier = Modifier.testTag("speak_${item.word}")) {
                        Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = "朗读 ${item.word}", tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (item.partOfSpeech.isNotBlank()) {
                    Text(item.partOfSpeech, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                }
            }
            val phonetic = listOfNotNull(
                item.phoneticUs.takeIf { it.isNotBlank() }?.let { "美 $it" },
                item.phoneticUk.takeIf { it.isNotBlank() }?.let { "英 $it" },
            ).joinToString("　")
            if (phonetic.isNotBlank()) Text(phonetic, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(item.translation, style = MaterialTheme.typography.titleMedium)
            if (item.example.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(item.example, style = MaterialTheme.typography.bodyMedium)
                if (item.exampleTranslation.isNotBlank()) {
                    Text(item.exampleTranslation, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
