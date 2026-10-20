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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.data.BookMasteryGroup
import com.alanbeidanci.app.data.DayStat
import com.alanbeidanci.app.data.MasteryMetric
import com.alanbeidanci.app.data.QuestionType
import com.alanbeidanci.app.data.StatsReport
import com.alanbeidanci.app.ui.StatsSyncUiState
import com.alanbeidanci.app.ui.StatsViewModel
import com.alanbeidanci.app.ui.MainlineErrorReportUiState
import com.alanbeidanci.app.ui.MainlineErrorReportViewModel
import com.alanbeidanci.app.ui.components.AppCard
import com.alanbeidanci.app.ui.components.PageHeading

@Composable
fun StatsScreen(
    container: AppContainer,
    onBack: () -> Unit = {},
) {
    val viewModel: StatsViewModel = viewModel(
        key = "stats",
        factory = StatsViewModel.factory(container),
    )
    val report by viewModel.uiState.collectAsState()

    StatsReportContent(
        report = report,
        onBack = onBack,
    )
}

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun StatsReportContent(
    report: StatsReport,
    syncState: StatsSyncUiState = StatsSyncUiState(),
    errorReportState: MainlineErrorReportUiState = MainlineErrorReportUiState(loading = false),
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onErrorReport: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag("stats-report-list").padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(Modifier.padding(top = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp).clip(RoundedCornerShape(12.dp)).clickable { onBack() }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("返回", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                PageHeading("Learning Report", "学习效果报告", "今日学习 · 近 7 天明细 · 单元掌握度。数据来自这台手机。")
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TodayCard(
                    label = "今日学习时长",
                    value = formatDuration(report.todayDurationMs),
                    note = if (report.streakDays > 0) "连续学习 ${report.streakDays} 天" else "今日尚未开始",
                    modifier = Modifier.fillMaxWidth(),
                )
                TodayCard(
                    label = "今日学习(词)",
                    value = "${report.todayWords} 词",
                    note = if (report.todayAnswers > 0) "作答 ${report.todayAnswers} 次 · 正确率 ${report.todayAccuracy}%" else "今日尚未开始",
                    modifier = Modifier.fillMaxWidth(),
                )
                TodayCard(
                    label = "今日主线双测",
                    value = if (report.todayMainlineAnswers > 0) "${report.todayMainlineAnswers} 题" else "—",
                    note = if (report.todayMainlineAnswers > 0) {
                        "听音与中文题合计 · 正确率 ${report.todayMainlineAccuracy}%"
                    } else {
                        "今日尚未开展主线双测"
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            Text("分项练习与独立回忆", style = MaterialTheme.typography.titleLarge)
        }
        item {
            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MasteryDimensionRow("听写练习正确率", "听英文后写词", report.listeningMastery)
                    MasteryDimensionRow("词义练习正确率", "英文词选择中文意思", report.meaningMastery)
                    MasteryDimensionRow("拼写练习正确率", "凭中文写英文；含旧双测中文轮", report.spellingStability)
                    report.independentRecall.forEach { (skill, metric) ->
                        MasteryDimensionRow("${skill.label}跨日首次回忆", "间隔至少24小时，首次且未暴露答案", metric)
                    }
                    Text(
                        "练习正确率包含修正练习；跨日首次回忆只统计新版合格证据。旧日志不会被改成独立回忆。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Text("近 7 天", style = MaterialTheme.typography.titleLarge)
        }
        item {
            AppCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DayTableHeader()
                    report.days.forEach { day -> DayRow(day) }
                }
            }
        }
        item {
            Text("单元掌握度", style = MaterialTheme.typography.titleLarge)
        }
        if (report.books.isEmpty()) {
            item { EmptyState() }
        } else {
            items(report.books, key = { it.book.id }) { group -> BookMasteryCard(group) }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun MasteryDimensionRow(
    title: String,
    subtitle: String,
    metric: MasteryMetric,
    showNear: Boolean = false,
) {
    val percent = metric.percent
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(percent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { ((percent ?: 0) / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
        )
        val near = if (showNear && metric.near > 0) " · 近似错误 ${metric.near}" else ""
        Text(
            if (metric.total > 0) "准确 ${metric.correct}/${metric.total}$near" else "暂无对应记录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TodayCard(label: String, value: String, note: String, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            Box(
                Modifier.size(42.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(15.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AutoGraph, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DayTableHeader() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HeaderCell("日期", Modifier.width(76.dp))
        HeaderCell("时长", Modifier.width(48.dp))
        Text("学习单词", style = labelStyle(), textAlign = TextAlign.Start, modifier = Modifier.weight(1f))
        HeaderCell("作答", Modifier.width(44.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier = Modifier, textAlign: TextAlign = TextAlign.Start) {
    Text(text, style = labelStyle(), textAlign = textAlign, modifier = modifier)
}

@Composable
private fun labelStyle() = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun DayRow(day: DayStat) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(day.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(76.dp))
            Text(formatDuration(day.durationMs), style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(48.dp))
            val parts = buildList {
                add("${day.words} 词")
                add("新${day.newWords}·复${day.reviewWords}·掌握${day.masteredWords}")
                if (day.methodDist.isNotBlank()) add(day.methodDist)
            }
            Text(
                parts.joinToString("  "),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            Text(if (day.answers > 0) "${day.answers}" else "-", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(44.dp), textAlign = TextAlign.End)
        }
        if (day.words > 0) {
            LinearProgressIndicator(
                progress = { if (day.words > 0) day.masteredWords.toFloat() / day.words else 0f },
                modifier = Modifier.fillMaxWidth().height(3.dp).padding(top = 2.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun BookMasteryCard(group: BookMasteryGroup) {
    var expanded by remember(group.book.id) { mutableStateOf(false) }
    val levelSummary = masteryLevelSummary(group)
    AppCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(group.book.name, style = MaterialTheme.typography.titleMedium)
                    if (levelSummary.isNotBlank()) {
                        Text(
                            "年级/册次：$levelSummary",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        "已学 ${group.learned}/${group.total} · 掌握 ${group.mastered}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).let { if (expanded) it.rotate(90f) else it },
                )
            }
            if (expanded) {
                group.units.forEach { unit ->
                    val levelLabel = masteryLevelLabel(group.book.name, unit.folderName)
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            listOf(levelLabel, unit.unitName).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "已学 ${unit.learned}/${unit.total} · 掌握 ${unit.mastered}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            LinearProgressIndicator(
                                progress = { if (unit.total > 0) unit.mastered.toFloat() / unit.total else 0f },
                                modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                strokeCap = StrokeCap.Round,
                            )
                            Text(
                                if (unit.total > 0) "${unit.mastered * 100 / unit.total}%" else "0%",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.width(38.dp),
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                }
            }
        }
    }
}

internal fun normalizeMasteryLevelLabel(value: String): String {
    val text = value.trim()
    if (text.isBlank()) return ""
    return text.replace(Regex("(年级)\\s*([上下])$"), "$1$2册")
}

internal fun masteryLevelLabel(bookName: String, folderName: String): String {
    normalizeMasteryLevelLabel(folderName).takeIf { it.isNotBlank() }?.let { return it }

    val source = bookName.trim()
    val schoolTerm = Regex("([一二三四五六七八九十\\d]+年级)\\s*([上下])(?:册)?").find(source)
    if (schoolTerm != null) return "${schoolTerm.groupValues[1]}${schoolTerm.groupValues[2]}册"
    return Regex("(第?[一二三四五六七八九十\\d]+册)").find(source)?.groupValues?.get(1).orEmpty()
}

internal fun masteryLevelSummary(group: BookMasteryGroup): String {
    val levels = group.units
        .map { masteryLevelLabel(group.book.name, it.folderName) }
        .filter { it.isNotBlank() }
        .distinct()
    return when (levels.size) {
        0 -> ""
        1 -> levels.first()
        else -> "${levels.size} 个年级/册次"
    }
}

@Composable
private fun EmptyState() {
    AppCard {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text("暂无学习数据，去首页开始学习吧", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalMinutes = ms / 60_000
    if (totalMinutes <= 0) return if (ms > 0) "<1分" else "0分"
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}时${m}分" else "${m}分"
}
