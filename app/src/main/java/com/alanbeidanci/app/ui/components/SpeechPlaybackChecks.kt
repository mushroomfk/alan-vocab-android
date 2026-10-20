package com.alanbeidanci.app.ui.components

import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.alanbeidanci.app.audio.PronunciationPlayer

@Composable
fun SpeechPlaybackChecks(accent: String, rate: Float) {
    val context = LocalContext.current
    val player = remember(context) { PronunciationPlayer(context) }
    DisposableEffect(player) { onDispose { player.release() } }
    val status by player.status.collectAsState()
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    fun volume() = "媒体音量：${audio.getStreamVolume(AudioManager.STREAM_MUSIC)}/${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"
    var volumeText by remember { mutableStateOf(volume()) }
    Column {
        Text("发音试听", style = MaterialTheme.typography.titleMedium)
        Text("试听不修改学习记录。离线英文不使用 Key；中文使用手机的文字转语音服务。", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { volumeText = volume(); player.speak("apple", accent, rate) },
            modifier = Modifier.testTag("speech-test-online")) { Text("英文试听（联网优先）") }
        TextButton(onClick = { volumeText = volume(); player.speak("apple", accent, rate, offlineOnly = true) },
            modifier = Modifier.testTag("speech-test-offline")) { Text("离线英文试听") }
        TextButton(onClick = { volumeText = volume(); player.speak("你好，欢迎学习英语。", chinese = true) },
            modifier = Modifier.testTag("speech-test-chinese")) { Text("中文试听") }
        Text(volumeText, style = MaterialTheme.typography.bodySmall)
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("speech-playback-status"))
        Text("音量为 0 时请按手机音量键调高。连接耳机或蓝牙时，声音可能从对应设备输出。", style = MaterialTheme.typography.bodySmall)
    }
}
