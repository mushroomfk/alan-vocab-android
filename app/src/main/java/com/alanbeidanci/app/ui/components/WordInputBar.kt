package com.alanbeidanci.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun WordInputBar(
    hint: String,
    hintError: Boolean,
    wrongCount: Int,
    skipAvailable: Boolean,
    onSkip: () -> Unit,
    input: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                hint,
                modifier = Modifier.weight(1f),
                color = if (hintError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (skipAvailable) {
                TextButton(onClick = onSkip) { Text("跳过") }
            } else if (wrongCount > 0) {
                Text(
                    "已错 $wrongCount 次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        input()
    }
}
