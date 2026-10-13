package com.alanbeidanci.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.audio.PronunciationPlayer
import com.alanbeidanci.app.network.RepeatRecording
import com.alanbeidanci.app.ui.RepeatRecordingsUiState
import com.alanbeidanci.app.ui.RepeatRecordingsViewModel
import com.alanbeidanci.app.ui.components.AppCard
import com.alanbeidanci.app.ui.components.PageHeading
import com.alanbeidanci.app.ui.pronunciationAssessmentLabel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun RepeatRecordingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: RepeatRecordingsViewModel = viewModel(
        key = "repeat-recordings",
        factory = RepeatRecordingsViewModel.factory(container),
    )
    val state by viewModel.uiState.collectAsState()
    val pronunciationPlayer = remember { PronunciationPlayer(container.applicationContext) }
    DisposableEffect(Unit) {
        onDispose {
            pronunciationPlayer.release()
            viewModel.stopPlayback()
        }
    }

    RepeatRecordingsContent(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onFilter = viewModel::setFilter,
        onPlayModel = { pronunciationPlayer.speak(it.word, it.accent.lowercase()) },
        onPlayRecording = viewModel::play,
    )
}

@Composable
internal fun RepeatRecordingsContent(
    state: RepeatRecordingsUiState,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onFilter: (String) -> Unit = {},
    onPlayModel: (RepeatRecording) -> Unit = {},
    onPlayRecording: (RepeatRecording) -> Unit = {},
) {
    val grouped = state.visibleRecordings.groupBy { it.recordedDate }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag("repeat-recordings-list").padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.padding(top = 18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onBack).padding(vertical = 6.dp),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("返回", color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(8.dp))
                PageHeading("Repeat History", "跟读记录", "最近 7 天 · 网页学习与复习录音 · 星级仅作事后检查")
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RecordingMetric("近期录音", "${state.recordings.size}", Modifier.weight(1f))
                RecordingMetric("跟读单词", "${state.recordings.map { it.word.lowercase() }.distinct().size}", Modifier.weight(1f))
                RecordingMetric("平均星级", state.averageStars, Modifier.weight(1f))
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL" to "全部", "LEARN" to "学习", "REVIEW" to "复习").forEach { (value, label) ->
                    FilterChip(
                        selected = state.filter == value,
                        onClick = { onFilter(value) },
                        label = { Text(label) },
                    )
                }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = onRefresh, enabled = !state.loading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("刷新")
                }
            }
        }

        if (state.error != null) {
            item {
                AppCard {
                    Text(
                        state.error,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        when {
            state.loading -> item {
                AppCard {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在加载最近 7 天录音…")
                    }
                }
            }
            grouped.isEmpty() -> item {
                AppCard {
                    Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Headphones, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("最近 7 天还没有${if (state.filter == "ALL") "" else if (state.filter == "LEARN") "学习" else "复习"}跟读录音", textAlign = TextAlign.Center)
                        Text("在网页版学习或复习页面完成跟读后，会同步显示在这里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
            }
            else -> grouped.forEach { (date, recordings) ->
                item(key = "date-$date") {
                    Text(recordingDateLabel(date), style = MaterialTheme.typography.titleLarge)
                }
                items(recordings, key = { it.id }) { recording ->
                    RecordingCard(
                        recording = recording,
                        loading = state.loadingAudioId == recording.id,
                        playing = state.playingId == recording.id,
                        onPlayModel = { onPlayModel(recording) },
                        onPlayRecording = { onPlayRecording(recording) },
                    )
                }
            }
        }

        item {
            Text(
                "星级来自录音当时浏览器的英文语音识别，录音结束后约 1.5 秒内保存；旧录音不会补评。它不影响主线、错弱词、复习轮数或学习正确率。录音超过 7 天由服务器自动删除。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun RecordingMetric(label: String, value: String, modifier: Modifier) {
    AppCard(modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 14.dp)) {
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RecordingCard(
    recording: RepeatRecording,
    loading: Boolean,
    playing: Boolean,
    onPlayModel: () -> Unit,
    onPlayRecording: () -> Unit,
) {
    val mode = if (recording.mode == "REVIEW") "复习" else "学习"
    val accent = if (recording.accent == "UK") "英式" else "美式"
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(recording.word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        if (recording.partOfSpeech.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(recording.partOfSpeech, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text(recording.translation.ifBlank { "暂无释义" }, style = MaterialTheme.typography.bodyMedium)
                }
                Text(String.format(Locale.CHINA, "%.1f秒", recording.durationMs / 1000f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("$mode · $accent · ${recordingTimeLabel(recording.recordedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AssessmentLine(recording)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPlayModel) {
                    Icon(Icons.AutoMirrored.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(5.dp))
                    Text("标准发音")
                }
                OutlinedButton(onClick = onPlayRecording, enabled = !loading) {
                    when {
                        loading -> CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        playing -> Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(17.dp))
                        else -> Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(17.dp))
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(if (playing) "停止" else if (loading) "加载中" else "我的录音")
                }
            }
        }
    }
}

@Composable
private fun AssessmentLine(recording: RepeatRecording) {
    when (recording.assessmentStatus) {
        "READY" -> {
            val stars = (recording.assessmentStars ?: 0).coerceIn(0, 5)
            Column {
                Text("★".repeat(stars) + "☆".repeat(5 - stars), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                Text(pronunciationAssessmentLabel(recording.assessmentCode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        "UNAVAILABLE" -> Text("暂未评分 · ${pronunciationAssessmentLabel(recording.assessmentCode)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Text("录音时未开启事后评星，旧录音不会补评", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun recordingDateLabel(date: String): String {
    val today = java.time.LocalDate.now()
    return when (date) {
        today.toString() -> "今天"
        today.minusDays(1).toString() -> "昨天"
        else -> date
    }
}

private fun recordingTimeLabel(raw: String): String = runCatching {
    timeFormatter.format(Instant.parse(raw).atZone(ZoneId.systemDefault()))
}.getOrDefault("")

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
