package com.alanbeidanci.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.data.MainlinePlan
import com.alanbeidanci.app.data.MainlineScheduler
import com.alanbeidanci.app.data.MainlineStep
import com.alanbeidanci.app.ui.HomeViewModel
import com.alanbeidanci.app.ui.components.AppCard
import com.alanbeidanci.app.ui.theme.AlanAmber
import com.alanbeidanci.app.ui.theme.AlanHero

@Composable
fun HomeScreen(
    container: AppContainer,
    onStartMainline: () -> Unit,
    onBooks: () -> Unit,
    mainlinePreparing: Boolean = false,
    mainlineEnabled: Boolean = true,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(container)),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.refresh() }
    val plan = state.plan
    val selectedBooks = state.selectedBooks
    val currentBook = state.currentBook
    val bookProgress = plan.learnedWords.toFloat() / plan.totalWords.coerceAtLeast(1)
    val todayCap = plan.newWordCap.coerceAtLeast(0)
    val goalProgress = if (todayCap == 0) 0f else plan.learnedToday.toFloat() / todayCap
    val task = if (!mainlineEnabled) {
        Triple(
            "今天先不能开始",
            "请重新打开应用。如果还是这样，回到首页确认已经选好词书。",
            "暂时不能开始",
        )
    } else when (plan.step) {
        MainlineStep.REVIEW -> Triple(
            "今天先复习 ${plan.reviewWords.size} 个没记住的词",
            "这些是以前听写或看中文默写没通过的。复习完，再测一次。",
            "开始复习",
        )
        MainlineStep.REMEDY_TEST -> Triple(
            "今天要把 ${plan.remedyTestWords.size} 个没记住的词再测一遍",
            "听英文写一次，看中文再写一次。两次都对，才算记住。",
            "开始测验",
        )
        MainlineStep.CONSOLIDATION_TEST -> Triple(
            "今天要查 ${plan.consolidationWords.size} 个到了时间的词",
            "先凭中文写，不放发音。第一次就写对，下次才隔更久；没想起来的明天再查，不会拖很久。",
            "开始巩固",
        )
        MainlineStep.NEW_WORD_TEST -> Triple(
            "今天的新词还要测一遍，共 ${plan.pendingTestWords.size} 个",
            "先听英文写出来，再看中文写出来。测完，今天的新词才算过关。",
            "开始测验",
        )
        MainlineStep.LEARN -> Triple(
            "今天先学 ${plan.learnWords.size} 个新词",
            "先看，再凭中文默写，并分开确认词义、听写和例句。写对 3 次只算这次练习通过，第二天再查一次才开始算记住。",
            "开始学今天的词",
        )
        MainlineStep.DONE -> Triple(
            "今天的学习做完了",
            "今天要做的都做完了${if (plan.deferredRemedyCount > 0) "。有 ${plan.deferredRemedyCount} 个刚没记住的词，明天再测" else ""}。也可以到复习页自己再看一遍。",
            "今天做完了",
        )
    }

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text("ALAN · 学习主页", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, letterSpacing = 1.8.sp)
            Text("今天，按主线学。", style = MaterialTheme.typography.headlineLarge)
        }

        Column(
            modifier = Modifier.fillMaxWidth().background(AlanHero, RoundedCornerShape(30.dp)).padding(24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(16.dp))
                Text("今天要做的事", color = Color.White.copy(alpha = 0.82f), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(18.dp))
            Text(task.first, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(task.second, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.84f))
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onStartMainline,
                enabled = mainlineEnabled && !mainlinePreparing && plan.step != MainlineStep.DONE && plan.currentWords.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = AlanAmber, contentColor = Color(0xFF24342F)),
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(if (plan.step == MainlineStep.DONE) Icons.Outlined.CheckCircle else Icons.Outlined.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (mainlinePreparing) "正在进入…" else task.third, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(22.dp))
            Text(
                when {
                    todayCap == 0 && plan.totalWords == 0 -> "今日新学 ${plan.learnedToday} 词"
                    todayCap == 0 -> "今天先不学新词，时间留给要复习的词"
                    todayCap < state.settings.dailyGoal -> "今日新学 ${plan.learnedToday}/$todayCap 词 · 上限 ${state.settings.dailyGoal}，先复习到期的词"
                    else -> "今日新学 ${plan.learnedToday}/$todayCap 词"
                },
                color = Color.White.copy(alpha = 0.85f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { goalProgress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(9.dp),
                color = AlanAmber,
                trackColor = Color.White.copy(alpha = 0.22f),
                strokeCap = StrokeCap.Round,
            )
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(22.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(50.dp).background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(17.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Book, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("正在学习", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.8.sp)
                        Text(
                            if (selectedBooks.size > 1) "${selectedBooks.size} 套词库混合学习" else currentBook?.book?.name ?: "还未选择词书",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("词书进度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${plan.learnedWords}/${plan.totalWords}", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { bookProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(9.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.primaryContainer,
                    strokeCap = StrokeCap.Round,
                )
                Spacer(Modifier.height(14.dp))
                Text("已掌握 ${plan.masteredCount} · 已牢记 ${plan.fixedCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Surface(onClick = onBooks, color = Color.Transparent) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("选择词库与单元", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("今天的顺序", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.8.sp)
                MainlineTimelineRow(1, "复习没记住的", plan, MainlineStep.REVIEW)
                MainlineTimelineRow(2, "再测一遍", plan, MainlineStep.REMEDY_TEST)
                MainlineTimelineRow(3, "巩固已经会的", plan, MainlineStep.CONSOLIDATION_TEST)
                MainlineTimelineRow(4, "学新词", plan, MainlineStep.LEARN)
                MainlineTimelineRow(5, "测今天的新词", plan, MainlineStep.NEW_WORD_TEST)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric(Icons.Outlined.LocalFireDepartment, "连续学习", "${state.summary.streakDays} 天", Modifier.weight(1f))
            Metric(Icons.Outlined.Flag, "今日接触", "${plan.touchedToday} 词", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric(Icons.Outlined.CheckCircle, "今日作答", "${state.summary.todayReviews} 次", Modifier.weight(1f))
            Metric(Icons.Outlined.CheckCircle, "主线正确率", if (state.summary.mainlineAnswers == 0) "—" else "${state.summary.accuracy}%", Modifier.weight(1f))
        }
    }
}

@Composable
private fun MainlineTimelineRow(number: Int, label: String, plan: MainlinePlan, rowStep: MainlineStep) {
    val active = plan.step == rowStep
    val state = MainlineScheduler.stepStatus(plan, rowStep)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(10.dp))
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        Text(state, style = MaterialTheme.typography.bodySmall, color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Metric(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}
