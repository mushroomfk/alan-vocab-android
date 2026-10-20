package com.alanbeidanci.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alanbeidanci.app.BuildConfig
import com.alanbeidanci.app.ui.components.AppCard

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回") }
            Text("关于应用", style = MaterialTheme.typography.headlineMedium)
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(70.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)), contentAlignment = Alignment.Center) {
                    Text("A", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.size(14.dp))
                Text("Alan背单词", style = MaterialTheme.typography.titleLarge)
                Text("版本 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.size(12.dp))
                Text("听得懂，记得住，用得出。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        AppCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("产品与数据说明", style = MaterialTheme.typography.titleLarge)
                AboutPoint("专为记单词设计的原生 Android 应用。开源代码与示例词书采用 MIT 许可。")
                AboutPoint("开源版附带 40 词原创示例，体验学习、双测与复习流程；不含原版第三方词库。")
                AboutPoint("这一版不登录、不连服务器。词库、学习进度和设置都保存在本机。英文发音优先用本机缓存；在设置里填写自己的火山引擎 Key 后，有网时联网合成并缓存；否则用安装包里的 Piper 离线语音。")
                AboutPoint("当前不包含广告或第三方行为分析 SDK。")
            }
        }
    }
}

@Composable
private fun AboutPoint(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(9.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}
