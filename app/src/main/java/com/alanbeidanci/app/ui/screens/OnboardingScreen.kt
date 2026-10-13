package com.alanbeidanci.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.data.WordBookEntity
import com.alanbeidanci.app.ui.theme.AlanAmber
import com.alanbeidanci.app.ui.theme.AlanHero
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(container: AppContainer) {
    val settings by container.preferences.settings.collectAsState(initial = com.alanbeidanci.app.data.UserSettings())
    val books by container.repository.books.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var selectedId by remember(settings.currentBookId) { mutableStateOf(settings.currentBookId) }
    var category by remember { mutableStateOf("中国考试") }
    val categoryOrder = listOf("中国考试", "国际考试", "青少年英语", "美剧口语", "其他词库")
    val categories = remember(books) {
        val present = books.map { it.category }.distinct().toSet()
        (categoryOrder.filter { it in present } + present.filterNot { it in categoryOrder }.sorted()).distinct()
    }
    val visibleBooks = remember(books, category) {
        books.filter { category.isEmpty() || it.category == category }
    }
    val selectedBook = books.firstOrNull { it.id == selectedId }
    Column(Modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(34.dp))
        Box(Modifier.size(78.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(26.dp)), contentAlignment = Alignment.Center) {
            Text("A", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(18.dp))
        Text("Alan背单词", style = MaterialTheme.typography.headlineLarge)
        Text("听得懂，记得住，用得出。", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(30.dp))

        Column(
            Modifier.fillMaxWidth().background(AlanHero, RoundedCornerShape(30.dp)).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("从今天开始，建立自己的词汇节奏", style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Text("学习、辨义、听音和拼写会形成完整闭环，并自动安排下一次复习。", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.72f))
            BenefitRow(Icons.Outlined.CheckCircle, "专注词义和记忆，不做速度训练")
            BenefitRow(Icons.Outlined.Headphones, "系统发音与听音辨词")
            BenefitRow(Icons.Outlined.Spellcheck, "拼写验收与薄弱词追踪")
            BenefitRow(Icons.Outlined.Refresh, "根据表现自动安排复习")
        }

        Spacer(Modifier.height(22.dp))
        Text("每天新词上限是多少？", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
        Text("到期的词多时，今天的新词会少一些。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 20, 30, 50).forEach { goal ->
                val active = goal == settings.dailyGoal
                Surface(
                    onClick = { scope.launch { container.preferences.setDailyGoal(goal) } },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(15.dp),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                ) {
                    Text(
                        "$goal 个",
                        modifier = Modifier.padding(vertical = 13.dp),
                        textAlign = TextAlign.Center,
                        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Text("今天先学哪一套？", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth())
        Text(
            "先选一套就好，之后可以在首页更换。",
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (books.isEmpty()) {
            Text("词库还在准备，请稍等。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(selected = category.isEmpty(), onClick = { category = "" }, label = { Text("全部") })
                categories.forEach { label ->
                    FilterChip(
                        selected = category == label,
                        onClick = { category = if (category == label) "" else label },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                "已选：${selectedBook?.name ?: "还没选"}",
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.titleMedium,
            )
            visibleBooks.forEach { book ->
                BookChoice(book, selected = book.id == selectedId, onClick = { selectedId = book.id })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Button(
            onClick = {
                scope.launch {
                    if (selectedId.isNotBlank()) container.preferences.selectOnlyBook(selectedId)
                    container.preferences.completeOnboarding()
                }
            },
            enabled = selectedBook != null,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            Text("开始使用 Alan背单词", fontWeight = FontWeight.Bold)
        }
        Text(
            "学习记录保存在这台手机上。系统换机可能带走本机数据；标准云备份放不下完整词库，完整进度请在「我的」里导出。朗读有时会联网。",
            modifier = Modifier.padding(14.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        }
    }
}

@Composable
private fun BookChoice(book: WordBookEntity, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(16.dp),
            ),
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(book.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(book.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BenefitRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = AlanAmber, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(text, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
    }
}
