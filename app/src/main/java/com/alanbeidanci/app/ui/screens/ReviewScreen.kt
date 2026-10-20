package com.alanbeidanci.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.audio.PronunciationPlayer
import com.alanbeidanci.app.data.MainlineScheduler
import com.alanbeidanci.app.data.UserSettings
import com.alanbeidanci.app.data.WordWithProgress
import com.alanbeidanci.app.ui.ReviewViewModel
import com.alanbeidanci.app.ui.components.AppCard
import com.alanbeidanci.app.ui.components.PronunciationNotice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReviewScreen(
    container: AppContainer,
    onStartReview: () -> Unit,
    onStartForgotten: () -> Unit,
    onPracticeList: (String) -> Unit,
    viewModel: ReviewViewModel = viewModel(factory = ReviewViewModel.factory(container)),
) {
    val state by viewModel.uiState.collectAsState()
    val settings by container.preferences.settings.collectAsState(initial = UserSettings())
    val context = LocalContext.current
    val pronunciation = remember(context) { PronunciationPlayer(context) }
    DisposableEffect(pronunciation) { onDispose { pronunciation.release() } }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        "待复习" to (state.forgotten + state.pending),
        "已掌握" to state.mastered,
        "已牢记" to state.fixed,
        "收藏" to state.favorite,
    )
    val selectedItems = tabs[selectedTab].second
    val selectedScope = listOf("pending", "mastered", "fixed", "favorite")[selectedTab]
    val remedyTotal = state.pending.size
    val now = System.currentTimeMillis()
    val dueForgotten = state.forgotten.filter { item ->
        MainlineScheduler.isDueOnOrBefore(item.progress?.dueAt ?: 0L, now)
    }

    LazyColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text("复习中心", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(16.dp))
            if (dueForgotten.isNotEmpty()) {
                Button(onClick = onStartForgotten, shape = RoundedCornerShape(16.dp)) {
                    Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("查上次没想起来的 ${dueForgotten.size} 个词", fontWeight = FontWeight.Bold)
                }
            }
            if (state.pending.isNotEmpty()) {
                if (dueForgotten.isNotEmpty()) Spacer(Modifier.height(10.dp))
                if (dueForgotten.isEmpty()) {
                    Button(onClick = onStartReview, shape = RoundedCornerShape(16.dp)) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("自己再看一遍旧错词", fontWeight = FontWeight.Bold)
                    }
                } else {
                    OutlinedButton(onClick = onStartReview, shape = RoundedCornerShape(16.dp)) {
                        Text("自己再看一遍旧错词", fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (dueForgotten.isEmpty() && state.pending.isEmpty()) {
                Button(onClick = {}, enabled = false, shape = RoundedCornerShape(16.dp)) {
                    Text(if (state.forgotten.isNotEmpty()) "没想起来的词明天再查" else "今天没有要查的词", fontWeight = FontWeight.Bold)
                }
            }
            PronunciationNotice(pronunciation)
        }
        item {
            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("和今天的任务", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.8.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("旧错词可以在这里再看一遍。上次巩固没想起来、并且已经到复习日的词，直接点上面的按钮查。还没到日子的，明天再查。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ReviewMainlineMetric("没记住", state.wrong.size, urgent = state.wrong.isNotEmpty(), modifier = Modifier.weight(1f))
                        ReviewMainlineMetric("还不准", state.weak.size, urgent = state.weak.isNotEmpty(), modifier = Modifier.weight(1f))
                        ReviewMainlineMetric("要再看", remedyTotal, urgent = remedyTotal > 0, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp)).padding(4.dp),
            ) {
                tabs.forEachIndexed { index, item ->
                    Surface(
                        onClick = { selectedTab = index },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = if (selectedTab == index) MaterialTheme.colorScheme.surface else Color.Transparent,
                        tonalElevation = if (selectedTab == index) 1.dp else 0.dp,
                    ) {
                        Text(
                            item.first,
                            modifier = Modifier.padding(vertical = 10.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selectedTab == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (selectedItems.isEmpty()) {
            item { EmptyReviewState(selectedTab) }
        } else {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("共 ${selectedItems.size} 个单词", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { onPracticeList(selectedScope) }, shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("去练习", fontWeight = FontWeight.Bold)
                    }
                }
            }
            items(selectedItems, key = { it.word.id }) { item ->
                ReviewWordRow(item, showDue = false) {
                    pronunciation.speak(item.word.word, accent = settings.accent, rate = settings.studySpeechRate)
                }
            }
        }
    }
}

@Composable
private fun ReviewMainlineMetric(label: String, value: Int, urgent: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(
                if (urgent) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(14.dp),
            )
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value.toString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReviewWordRow(item: WordWithProgress, showDue: Boolean, onSpeak: () -> Unit) {
    val progress = item.progress ?: return
    AppCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(item.word.word.take(1).uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onSpeak)
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(item.word.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = "朗读 ${item.word.word}",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(item.word.translation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("熟悉度 ${progress.familiarity}/5", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Row {
                    if (progress.wrongCount > 0) {
                        Text("错 ${progress.wrongCount} 次", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (showDue) {
                        Text(
                            " · ${SimpleDateFormat("M/d", Locale.CHINA).format(Date(progress.dueAt))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyReviewState(tab: Int) {
    val messages = listOf(
        "今天没有要复习的旧错词" to "今天刚测错的，明天会排到这里。",
        "还没有已经会的词" to "新词听写和默写都通过后，会出现在这里。",
        "还没有记得特别牢的词" to "已经会的词，隔几天再测过几次，会记到这里。",
        "还没有收藏" to "学习时可以点右上角的心形收藏。",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(26.dp))
            .padding(vertical = 46.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(messages[tab].first, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            messages[tab].second,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
