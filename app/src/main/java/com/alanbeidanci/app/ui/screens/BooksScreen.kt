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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alanbeidanci.app.AppContainer
import com.alanbeidanci.app.data.BookProgress
import com.alanbeidanci.app.ui.BooksViewModel
import com.alanbeidanci.app.ui.components.PageHeading

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BooksScreen(
    container: AppContainer,
    viewModel: BooksViewModel = viewModel(factory = BooksViewModel.factory(container)),
) {
    val state by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }

    val categoryOrder = listOf("中国考试", "国际考试", "青少年英语", "其他词库", "自建词库")
    val categories = remember(state.books) {
        val present = state.books.map { it.book.category }.distinct().toMutableSet()
        (categoryOrder.filter { it in present } + present.filterNot { it in categoryOrder }.sorted()).distinct()
    }
    val filtered = remember(state.books, query, category) {
        state.books.filter { item ->
            (category.isEmpty() || item.book.category == category) &&
                (query.isBlank() || item.book.name.contains(query, ignoreCase = true) ||
                    item.book.category.contains(query, ignoreCase = true) ||
                    item.book.description.contains(query, ignoreCase = true))
        }
    }
    val visibleIds = remember(filtered) { filtered.map { it.book.id }.toSet() }
    val allVisibleSelected = remember(filtered, state.settings.selectedBookIds) {
        filtered.isNotEmpty() && filtered.all { it.book.id in state.settings.selectedBookIds }
    }

    LazyColumn(
        modifier = Modifier.padding(horizontal = 16.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 22.dp, bottom = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            PageHeading(
                eyebrow = "Library",
                title = "选择主线词库与单元",
                subtitle = "可多选词库与单元；首页主线只在当前范围内自动排课。",
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索词库") },
                placeholder = { Text("按名称、分类或描述搜索") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
            )
        }
        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(
                    selected = category.isEmpty(),
                    onClick = { category = "" },
                    label = { Text("全部") },
                    colors = FilterChipDefaults.filterChipColors(),
                )
                categories.forEach { label ->
                    FilterChip(
                        selected = category == label,
                        onClick = { category = if (category == label) "" else label },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
        }
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("已选择 ${state.settings.selectedBookIds.size} 个词库", style = MaterialTheme.typography.titleMedium)
                        Text("今日主线会从所选范围按顺序学习和双测", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(
                        onClick = { viewModel.selectAllBooks(if (allVisibleSelected) emptySet() else visibleIds) },
                        enabled = visibleIds.isNotEmpty(),
                    ) {
                        Text(if (allVisibleSelected) "清空当前筛选" else "全选当前筛选")
                    }
                }
            }
        }
        item {
            Text(
                if (filtered.size == state.books.size) "共 ${state.books.size} 套词库" else "筛选出 ${filtered.size} / ${state.books.size} 套",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        items(filtered, key = { it.book.id }) { item ->
            WordBookCard(
                item = item,
                selected = item.book.id in state.settings.selectedBookIds,
                onSelect = { viewModel.toggleBook(item.book.id) },
                onSelectOnly = { viewModel.selectOnly(item.book.id) },
                selectedUnits = state.settings.selectedUnitKeys,
                onToggleUnit = { key -> viewModel.toggleUnitEnsuringBook(key, item.book.id) },
                onToggleUnits = { keys -> viewModel.toggleUnitsEnsuringBook(keys, item.book.id) },
            )
        }
    }
}

@Composable
private fun WordBookCard(
    item: BookProgress,
    selected: Boolean,
    onSelect: () -> Unit,
    onSelectOnly: () -> Unit,
    selectedUnits: Set<String>,
    onToggleUnit: (String) -> Unit,
    onToggleUnits: (Set<String>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val progress = item.learned.toFloat() / item.total.coerceAtLeast(1)
    val accent = Color(item.book.colorHex)
    Surface(
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(24.dp)) else Modifier),
        shape = RoundedCornerShape(24.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
        tonalElevation = if (selected) 2.dp else 0.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onSelect() },
                    colors = CheckboxDefaults.colors(),
                )
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(15.dp)).background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.book.name, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${item.learned}/${item.total}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("掌握 ${item.mastered}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.weight(1f).height(7.dp),
                    color = accent,
                    trackColor = accent.copy(alpha = 0.13f),
                    strokeCap = StrokeCap.Round,
                )
                if (item.units.isNotEmpty()) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "单元 ▴" else "单元 ▾", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (item.units.isNotEmpty() && !selected) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onSelectOnly) {
                        Text("仅选这套", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (expanded && item.units.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("选择 册 / 单元（不选表示整本）", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val rows = buildList {
                    item.units.groupBy { it.folderId }.forEach { (folderId, units) ->
                        val folderName = units.firstOrNull { it.folderName.isNotBlank() }?.folderName ?: "全部"
                        add(UnitRowData("folder:$folderId", folderName, units.size, true, units.map { it.key }))
                        units.forEach { add(UnitRowData(it.key, it.name, it.count, false)) }
                    }
                }
                rows.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { unit ->
                            if (unit.isFolder) {
                                FilterChip(
                                    selected = unit.childKeys.all { it in selectedUnits },
                                    onClick = { onToggleUnits(unit.childKeys.toSet()) },
                                    label = { Text("${unit.label} · 全选") },
                                    modifier = Modifier.weight(1f),
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    ),
                                )
                            } else {
                                FilterChip(
                                    selected = unit.key in selectedUnits,
                                    onClick = { onToggleUnit(unit.key) },
                                    label = { Text("${unit.label} · ${unit.count}") },
                                    modifier = Modifier.weight(1f),
                                    colors = FilterChipDefaults.filterChipColors(),
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private data class UnitRowData(
    val key: String,
    val label: String,
    val count: Int,
    val isFolder: Boolean = false,
    val childKeys: List<String> = emptyList(),
)
