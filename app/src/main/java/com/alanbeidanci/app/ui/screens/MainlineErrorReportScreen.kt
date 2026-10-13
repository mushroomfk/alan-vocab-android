package com.alanbeidanci.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.ReportProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.network.DailyMainlineErrorReport
import com.alanbeidanci.app.network.MainlineErrorWordSummary
import com.alanbeidanci.app.ui.MainlineErrorReportUiState
import com.alanbeidanci.app.ui.MainlineErrorReportViewModel
import com.alanbeidanci.app.ui.components.AppCard
import com.alanbeidanci.app.ui.components.PageHeading
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun MainlineErrorReportScreen(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: MainlineErrorReportViewModel = viewModel(
        key = "mainline-error-report",
        factory = MainlineErrorReportViewModel.factory(container),
    )
    val state by viewModel.uiState.collectAsState()

    MainlineErrorReportContent(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onSelectDate = viewModel::selectDate,
        onPreviousWeek = viewModel::previousWeek,
        onNextWeek = viewModel::nextWeek,
    )
}

@Composable
internal fun MainlineErrorReportContent(
    state: MainlineErrorReportUiState,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onSelectDate: (LocalDate) -> Unit = {},
    onPreviousWeek: () -> Unit = {},
    onNextWeek: () -> Unit = {},
) {
    val report = state.selectedReport
    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag("mainline-error-report-list").padding(horizontal = 18.dp),
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
                PageHeading("Mainline Review", "近期易错知识点", "只分析五步主线双测中的英文拼写，不改变主线顺序和错弱词分类。")
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReportMetric("英文作答", "${state.summary.englishAnswers}", Modifier.weight(1f))
                    ReportMetric("错误次数", "${state.summary.incorrectAnswers}", Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ReportMetric("易错单词", "${state.summary.uniqueErrorWords}", Modifier.weight(1f))
                    ReportMetric("近 7 天正确率", state.summary.accuracyPercent?.let { "$it%" } ?: "—", Modifier.weight(1f))
                }
            }
        }

        item {
            AppCard {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onPreviousWeek) {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null)
                            Text("上一周")
                        }
                        Text(
                            "${state.dates.first().format(monthDayFormatter)}—${state.dates.last().format(monthDayFormatter)}",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        OutlinedButton(onClick = onNextWeek, enabled = state.canGoNext) {
                            Text("下一周")
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.dates, key = { it.toString() }) { date ->
                            val selected = date == state.selectedDate
                            Surface(
                                onClick = { onSelectDate(date) },
                                shape = RoundedCornerShape(15.dp),
                                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Column(
                                    Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(weekdayLabel(date), style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(date.format(monthDayFormatter), style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
            }
        }

        when {
            state.loading -> item {
                AppCard {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在同步并分析近期主线记录…")
                    }
                }
            }
            state.error != null -> item {
                AppCard {
                    Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.ReportProblem, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        Text(state.error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = onRefresh) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("重新加载")
                        }
                    }
                }
            }
            report == null || report.englishAnswers == 0 -> item {
                ReportEmptyState("这一天没有主线英文拼写记录", "学习界面的自由听写、键盘练习和自主练习不会进入这里。")
            }
            report.incorrectAnswers == 0 -> item {
                ReportEmptyState("这一天主线英文拼写全部正确", "很好，继续按原五步主线完成后续任务。", success = true)
            }
            else -> {
                item { DailySummary(report) }
                item { Text("易错原因", style = MaterialTheme.typography.titleLarge) }
                items(report.errorTypes, key = { it.code }) { type ->
                    AppCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(type.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("${type.count} 次", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            }
                            if (type.examples.isNotEmpty()) Text("涉及：${type.examples.joinToString("、")}", style = MaterialTheme.typography.bodyMedium)
                            Text(type.suggestion, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item { Text("易错单词", style = MaterialTheme.typography.titleLarge) }
                items(report.words, key = { it.wordId }) { word -> ErrorWordCard(word, report) }
            }
        }

        item {
            Text(
                "报告仅用于事后检查。当天新产生的错词和薄弱词仍延至次日主线处理，不会回环重启今日任务。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun ReportMetric(label: String, value: String, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Column(Modifier.padding(15.dp)) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DailySummary(report: DailyMainlineErrorReport) {
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("${report.date} 当日概览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("英文作答 ${report.englishAnswers} 次 · 错误 ${report.incorrectAnswers} 次 · 易错 ${report.uniqueErrorWords} 词")
            Text("正确率 ${report.accuracyPercent ?: 0}%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ErrorWordCard(word: MainlineErrorWordSummary, report: DailyMainlineErrorReport) {
    val typeLabels = word.errorTypes.map { code -> report.errorTypes.firstOrNull { it.code == code }?.label ?: code }
    val stageLabels = word.stages.map(::mainlineStageLabel)
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(word.word, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("错 ${word.errorCount} 次", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            }
            Text("最近输入：${word.latestInput.ifBlank { "（空白）" }}", style = MaterialTheme.typography.bodyLarge)
            if (typeLabels.isNotEmpty()) Text("原因：${typeLabels.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (stageLabels.isNotEmpty()) Text("阶段：${stageLabels.joinToString(" · ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReportEmptyState(title: String, subtitle: String, success: Boolean = false) {
    AppCard {
        Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (success) Icons.Outlined.CheckCircle else Icons.Outlined.ReportProblem,
                contentDescription = null,
                tint = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(34.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

private val monthDayFormatter = DateTimeFormatter.ofPattern("M/d")

private fun weekdayLabel(date: LocalDate): String = when (date.dayOfWeek.value) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    else -> "周日"
}

private fun mainlineStageLabel(stage: String): String = when (stage) {
    "REMEDY" -> "补漏双测"
    "CONSOLIDATION" -> "巩固双测"
    "NEW_WORD" -> "新词双测"
    else -> "主线双测"
}
