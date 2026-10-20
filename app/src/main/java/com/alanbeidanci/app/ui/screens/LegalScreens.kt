package com.alanbeidanci.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alanbeidanci.app.ui.components.AppCard

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    LegalPage(title = "隐私说明", onBack = onBack) {
        LegalSection("本机使用") {
            LegalPoint("这一版不注册、不登录，学习记录和设置保存在本机，不向 Alan 服务器同步。英文发音可能访问火山引擎。")
            LegalPoint("不收集手机号、邮箱或账号密码。")
        }
        LegalSection("保存的数据") {
            LegalPoint("词书、每日目标、主题和提醒会进入系统备份。")
            LegalPoint("词库与学习数据库不进入标准云备份，内置示例词库可重新导入。学习进度请在「我的」里导出；换机传输可以带走本机数据库。")
            LegalPoint("应用不包含广告或第三方行为分析 SDK。")
        }
        LegalSection("查词、拍照识词与系统能力") {
            LegalPoint("中文或英文查词只查询本机词库，不会把查询内容发出去。")
            LegalPoint("相机仅在你主动打开拍照识词时申请权限；图像在设备上识别，不上传图像。")
            LegalPoint("你在设置中填写自己的火山引擎 API Key 后，有网时未缓存的英文单词会发给火山引擎合成发音。Key 仅在本机加密保存，不进入 JSON 或系统备份。未配置、断网或请求失败时使用安装包里的 Piper；已有发音缓存仍可使用。中文释义由设备语音引擎朗读。")
            LegalPoint("通知权限仅用于按你设置的时间发送本地学习提醒，可随时在应用或系统设置中关闭。")
        }
        LegalSection("导出与删除") {
            LegalPoint("你可以从“我的”导出或恢复 JSON 备份，里面包含设置、进度、作答记录和学习时长。主动分享后的文件由所选目标应用处理。")
            LegalPoint("清除应用数据或卸载即可删除本机记录。")
        }
        Text(
            "更新日期：2026 年 10 月 17 日",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegalPage(
    title: String,
    onBack: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Text(title, style = MaterialTheme.typography.headlineMedium)
        }
        content()
    }
}

@Composable
private fun LegalSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    AppCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun LegalPoint(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(9.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
    }
}
