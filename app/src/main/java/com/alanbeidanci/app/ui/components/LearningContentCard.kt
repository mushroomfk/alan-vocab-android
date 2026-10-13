package com.alanbeidanci.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alanbeidanci.app.data.LearningContent

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LearningContentCard(
    content: LearningContent,
    onSpeak: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!content.hasContent) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("词义与例句", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (content.curated) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                        Text(
                            "精选内容",
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            StaticContentRow("构词", content.structure)
            SpokenContentRow("词族", content.wordFamily, onSpeak)
            SpokenContentRow("搭配", content.collocations, onSpeak)
            ExampleBlock(content.coreExample, content.coreExampleTranslation, onSpeak)
            ExampleBlock(content.contextExample, content.contextExampleTranslation, onSpeak, isContext = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StaticContentRow(label: String, values: List<String>) {
    if (values.isEmpty()) return
    ContentLabel(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
                Text(value, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 15.sp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpokenContentRow(label: String, values: List<String>, onSpeak: (String) -> Unit) {
    if (values.isEmpty()) return
    ContentLabel(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { value ->
            Surface(
                onClick = { onSpeak(value) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(value, fontSize = 15.sp)
                    Spacer(Modifier.width(5.dp))
                    Icon(
                        Icons.AutoMirrored.Outlined.VolumeUp,
                        contentDescription = "朗读 $value",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ContentLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ExampleBlock(
    text: String,
    translation: String,
    onSpeak: (String) -> Unit,
    isContext: Boolean = false,
) {
    if (text.isBlank()) return
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isContext) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                if (translation.isNotBlank()) {
                    Text(translation, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { onSpeak(text) }) {
                Icon(
                    Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = "朗读例句",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
