package com.alanbeidanci.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.alanbeidanci.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

class PronunciationPlayer(context: Context) {
    private data class SystemSpeech(
        val text: String,
        val accent: String,
        val rate: Float,
        val chinese: Boolean,
        val sequence: Long,
    )

    private val applicationContext = context.applicationContext
    private var mediaPlayer: MediaPlayer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSystemSpeech: SystemSpeech? = null
    private var playbackSequence = 0L
    private var noticeKey: String? = null
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    init {
        tts = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) {
                pendingSystemSpeech = null
                publishNotice("engine", "这台手机的语音引擎没有准备好，暂时无法朗读。")
                return@TextToSpeech
            }
            pendingSystemSpeech?.also {
                pendingSystemSpeech = null
                speakWithSystem(it)
            }
        }
    }

    fun speak(text: String, accent: String = "us", rate: Float = 0.95f, chinese: Boolean = false) {
        val normalized = if (chinese) text.trim() else PronunciationAudio.normalize(text)
        if (normalized.isBlank()) return
        stopCurrent()
        val sequence = ++playbackSequence
        val request = SystemSpeech(normalized, accent, rate.coerceIn(0.5f, 2f), chinese, sequence)
        if (chinese || BuildConfig.LOCAL_ONLY) {
            speakWithSystem(request)
            return
        }

        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build(),
            )
            player.setDataSource(BuildConfig.API_BASE_URL + PronunciationAudio.path(normalized, accent))
            player.setOnPreparedListener { prepared ->
                if (sequence != playbackSequence) {
                    releasePlayer(prepared)
                    return@setOnPreparedListener
                }
                runCatching {
                    prepared.playbackParams = prepared.playbackParams
                        .setPitch(1f)
                        .setSpeed((request.rate / RECORDED_RATE).coerceIn(0.5f, 2f))
                }
                prepared.start()
            }
            player.setOnCompletionListener { completed -> releasePlayer(completed) }
            player.setOnErrorListener { failed, _, _ ->
                releasePlayer(failed)
                if (sequence == playbackSequence) speakWithSystem(request)
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            releasePlayer(player)
            if (sequence == playbackSequence) speakWithSystem(request)
        }
    }

    fun release() {
        playbackSequence += 1
        stopCurrent()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    private fun speakWithSystem(request: SystemSpeech) {
        if (request.sequence != playbackSequence) return
        val engine = tts
        if (!ttsReady || engine == null) {
            pendingSystemSpeech = request
            return
        }
        val requested = when {
            request.chinese -> Locale.SIMPLIFIED_CHINESE
            request.accent == "uk" -> Locale.UK
            else -> Locale.US
        }
        var languageResult = engine.setLanguage(requested)
        var usedFallback = false
        if (languageResult < TextToSpeech.LANG_AVAILABLE && !request.chinese) {
            val fallback = if (request.accent == "uk") Locale.US else Locale.UK
            val fallbackResult = engine.setLanguage(fallback)
            if (fallbackResult >= TextToSpeech.LANG_AVAILABLE) {
                languageResult = fallbackResult
                usedFallback = true
                publishNotice(
                    "accent-${request.accent}",
                    if (request.accent == "uk") "这台手机没有英式语音，已改用美式朗读。" else "这台手机没有美式语音，已改用英式朗读。",
                )
            }
        }
        if (languageResult < TextToSpeech.LANG_AVAILABLE) {
            publishNotice(
                if (request.chinese) "zh" else "en",
                if (request.chinese) {
                    "这台手机没有中文语音，中文释义暂时读不出来。请到系统设置安装文字转语音。"
                } else {
                    "这台手机没有英语语音，听写暂时听不到发音。请到系统设置安装文字转语音。"
                },
            )
        } else if (!usedFallback) {
            clearNotice()
        }
        engine.setSpeechRate(request.rate)
        engine.setPitch(1f)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        val spoken = engine.speak(request.text, TextToSpeech.QUEUE_FLUSH, params, "alan-pronunciation-${request.sequence}")
        if (spoken == TextToSpeech.ERROR && languageResult >= TextToSpeech.LANG_AVAILABLE) {
            publishNotice("speak", "刚才没有读出来。可以再点一次喇叭，或到系统设置检查文字转语音。")
        }
    }

    private fun publishNotice(key: String, message: String) {
        if (noticeKey == key && _notice.value == message) return
        noticeKey = key
        _notice.value = message
    }

    private fun clearNotice() {
        if (_notice.value == null) return
        noticeKey = null
        _notice.value = null
    }

    private fun stopCurrent() {
        pendingSystemSpeech = null
        tts?.stop()
        mediaPlayer?.let(::releasePlayer)
    }

    private fun releasePlayer(player: MediaPlayer) {
        if (mediaPlayer === player) mediaPlayer = null
        runCatching { player.stop() }
        runCatching { player.reset() }
        runCatching { player.release() }
    }

    private companion object {
        const val RECORDED_RATE = 0.95f
    }
}
