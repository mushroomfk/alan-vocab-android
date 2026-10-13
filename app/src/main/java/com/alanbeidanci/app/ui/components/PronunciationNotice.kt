package com.alanbeidanci.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.alanbeidanci.app.audio.PronunciationPlayer

@Composable
fun PronunciationNotice(player: PronunciationPlayer, modifier: Modifier = Modifier) {
    val notice by player.notice.collectAsState()
    val message = notice ?: return
    Text(
        message,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
    )
}
