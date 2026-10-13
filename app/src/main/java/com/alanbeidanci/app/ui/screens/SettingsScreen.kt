package com.alanbeidanci.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.alanbeidanci.app.data.RetentionProfile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.ui.SettingsViewModel
import com.alanbeidanci.app.ui.components.AppCard
import com.alanbeidanci.app.ui.components.PageHeading
import com.alanbeidanci.app.notifications.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    container: AppContainer,
    onAbout: () -> Unit,
    onPrivacy: () -> Unit,
    onBooks: () -> Unit,
    onStats: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container)),
) {
    val settings by viewModel.settings.collectAsState()
    val personalProfile by container.retentionPersonalization.profile.collectAsState(initial = null)
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val content = withContext(Dispatchers.IO) { container.backupManager.exportJson() }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(content) }
                            ?: error("无法创建备份文件")
                    }
                }.onSuccess {
                    snackbar.showSnackbar("学习数据已导出")
                }.onFailure {
                    snackbar.showSnackbar("导出失败：${it.message ?: "无法写入文件"}")
                }
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching {
                    val content = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("无法读取备份文件")
                    }
                    container.backupManager.restoreJson(content)
                    val restoredSettings = container.preferences.snapshot()
                    ReminderScheduler.schedule(
                        context,
                        restoredSettings.reminderEnabled,
                        restoredSettings.reminderHour,
                    )
                }.onSuccess {
                    snackbar.showSnackbar("学习数据已恢复")
                }.onFailure {
                    snackbar.showSnackbar("恢复失败：${it.message ?: "文件格式错误"}")
                }
            }
        }
    }

    Box {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PageHeading("Preferences", "我的学习设置", "学习偏好和进度都保存在这台手机上。")

            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("学习节奏", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("每天想认识多少个词？", style = MaterialTheme.typography.titleLarge)
                    Text("这是新词上限。到期的词多，或今天时间不够时，新词会少一些。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    OptionRow(options = listOf(10, 20, 30, 50), selected = settings.dailyGoal, label = { "$it 个" }, onSelect = viewModel::setDailyGoal)
                    Spacer(Modifier.height(18.dp))
                    Text("每天大概学多久？", style = MaterialTheme.typography.titleLarge)
                    Text("先保证到期和没记住的词，剩下的时间再学新词。还没有学习记录时，20 分钟大约学 5 个新词；有记录后按你实际用时算。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    OptionRow(options = listOf(20, 30, 45, 90), selected = settings.dailyMinutes, label = { "$it 分钟" }, onSelect = viewModel::setDailyMinutes)
                    Spacer(Modifier.height(18.dp))
                    SettingSwitchRow(Icons.AutoMirrored.Outlined.VolumeUp, "自动发音", "看词和听写时播放。凭中文默写时不自动播放，点喇叭算一次提示。", settings.autoSpeak, viewModel::setAutoSpeak)
                    SettingSwitchRow(Icons.Outlined.DarkMode, "深色模式", "降低暗光环境中的界面亮度。", settings.darkMode, viewModel::setDarkMode)
                    SettingSwitchRow(Icons.Outlined.Notifications, "每日提醒", "每天按设定时间提醒学习。", settings.reminderEnabled, viewModel::setReminderEnabled)
                }
            }

            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    val profile = personalProfile ?: RetentionProfile()
                    SettingSwitchRow(Icons.Outlined.AutoGraph, "自动调整复习", "根据长期学习记录，在手机充电且空闲时检查。所有计算留在本机。", profile.autoEnabled) { enabled ->
                        scope.launch { container.retentionPersonalization.setEnabled(enabled) }
                    }
                    Text(profile.description(), style = MaterialTheme.typography.bodySmall)
                    if (profile.lastAttemptAt > 0) {
                        val date = java.time.Instant.ofEpochMilli(profile.lastAttemptAt)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                        Text("上次检查：$date", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("调整只影响后续复习，不会集中改动已排好的日期。", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { scope.launch { container.retentionPersonalization.restorePrevious() } }, enabled = profile.revision > 0) {
                            Text("恢复上一组")
                        }
                        TextButton(onClick = { scope.launch { container.retentionPersonalization.useDefaults() } }) {
                            Text("使用默认参数")
                        }
                    }
                }
            }

            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Text("发音偏好", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text("默认口音", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(14.dp))
                    OptionRow(
                        options = listOf("us", "uk"),
                        selected = settings.accent,
                        label = { if (it == "us") "美式发音" else "英式发音" },
                        onSelect = viewModel::setAccent,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text("全局英语语速", style = MaterialTheme.typography.titleMedium)
                    Text("查词、学习、复习和主线双测统一使用此语速。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    OptionRow(
                        options = listOf(0.8f, 0.95f, 1.1f),
                        selected = settings.studySpeechRate,
                        label = { when (it) { 0.8f -> "慢"; 1.1f -> "快"; else -> "标准" } },
                        onSelect = viewModel::setStudySpeechRate,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text("提醒时间", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(10.dp))
                    OptionRow(
                        options = listOf(8, 12, 20, 22),
                        selected = settings.reminderHour,
                        label = { "%02d:00".format(it) },
                        onSelect = viewModel::setReminderHour,
                    )
                }
            }

            AppCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(8.dp)) {
                    SettingsAction(Icons.AutoMirrored.Outlined.MenuBook, "主线词库与单元", "选择首页主线使用的词库范围", onBooks)
                    SettingsAction(Icons.Outlined.AutoGraph, "学习统计", "查看今日与近 7 天主线数据", onStats)
                    SettingsAction(Icons.Outlined.Backup, "导出学习数据", "生成可保存的 JSON 备份文件") {
                        runCatching {
                            exportLauncher.launch("Alan背单词-学习数据.json")
                        }.onFailure {
                            scope.launch { snackbar.showSnackbar("当前系统没有可用的文件保存器") }
                        }
                    }
                    SettingsAction(Icons.Outlined.FileOpen, "恢复学习数据", "从 Alan背单词 JSON 备份恢复") {
                        runCatching {
                            restoreLauncher.launch("application/json")
                        }.onFailure {
                            scope.launch { snackbar.showSnackbar("当前系统没有可用的文件选择器") }
                        }
                    }
                    SettingsAction(Icons.Outlined.Policy, "隐私说明", "本地数据、通知与权限使用方式", onPrivacy)
                    SettingsAction(Icons.Outlined.Info, "关于应用", "版本、产品与数据说明", onAbout)
                }
            }
        }
        SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}

@Composable
private fun <T> OptionRow(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val active = option == selected
            Surface(
                onClick = { onSelect(option) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(15.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
            ) {
                Text(
                    label(option),
                    modifier = Modifier.padding(vertical = 13.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(icon: ImageVector, title: String, description: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun SettingsAction(icon: ImageVector, title: String, description: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(19.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
