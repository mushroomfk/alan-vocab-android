package com.alanbeidanci.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.alanbeidanci.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

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
    private var ttsFailed = false
    private var released = false
    private val main = Handler(Looper.getMainLooper())
    private var onlineTimeout: Runnable? = null
    private var systemTimeout: Runnable? = null
    private var onlineProblem: String? = null
    private var pendingSystemSpeech: SystemSpeech? = null
    private var playbackSequence = 0L
    private var noticeKey: String? = null
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    private val _status = MutableStateFlow("点击试听，检查手机声音。")
    val status: StateFlow<String> = _status

    init {
        tts = TextToSpeech(applicationContext) { status ->
            main.post {
                if (!released) {
                    ttsReady = status == TextToSpeech.SUCCESS
                    ttsFailed = !ttsReady
                    if (ttsReady) dispatchPending()
                    else pendingSystemSpeech?.let { speechUnavailable(it) }
                }
            }
        }
        BundledEnglishSpeaker.warmUp(applicationContext)
    }

    fun speak(text: String, accent: String = "us", rate: Float = 0.95f, chinese: Boolean = false, offlineOnly: Boolean = false) {
        val normalized = if (chinese) text.trim() else PronunciationAudio.normalize(text)
        if (normalized.isBlank() || released) return
        stopCurrent()
        clearNotice()
        onlineProblem = null
        val sequence = nextSequence.incrementAndGet()
        playbackSequence = sequence
        val request = SystemSpeech(normalized, accent, rate.coerceIn(0.5f, 2f), chinese, sequence)
        if (!request.chinese) {
            if (offlineOnly) { speakBundled(request); return }
            _status.value = "正在准备英文发音…"
            val timeout = Runnable {
                if (sequence == playbackSequence) {
                    VolcengineSpeaker.cancel(sequence)
                    onlineTimeout = null
                    onlineProblem = "在线发音等待超过 3.5 秒"
                    speakBundled(request)
                }
            }
            onlineTimeout = timeout
            main.postDelayed(timeout, ONLINE_WAIT_MS)
            if (VolcengineSpeaker.speak(applicationContext, request.text, request.accent, request.rate, sequence,
                onStarted = { if (sequence == playbackSequence) { clearOnlineTimeout(); playbackStarted("在线或缓存英文") } },
                onFailure = { reason -> if (sequence == playbackSequence) {
                    clearOnlineTimeout(); onlineProblem = reason; speakBundled(request)
                } })) {
                return
            }
            clearOnlineTimeout()
            speakBundled(request)
            return
        }
        _status.value = "正在准备中文语音…"
        deliverWithoutBundledVoice(request)
    }

    private fun speakBundled(request: SystemSpeech) {
        _status.value = onlineProblem?.let { "$it，正在准备离线英文…" } ?: "正在准备离线英文…"
        BundledEnglishSpeaker.speak(applicationContext, request.text, request.accent, request.rate, request.sequence,
            onStarted = { if (request.sequence == playbackSequence) {
                playbackStarted("离线英文")
                onlineProblem?.let { _status.value = "$it；离线英文已开始播放。" }
            } },
            onFailure = { if (request.sequence == playbackSequence) deliverWithoutBundledVoice(request) })
    }

    private fun deliverWithoutBundledVoice(request: SystemSpeech) {
        if (request.chinese || BuildConfig.LOCAL_ONLY) {
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
            player.setDataSource(BuildConfig.API_BASE_URL + PronunciationAudio.path(request.text, request.accent))
            player.setOnPreparedListener { prepared ->
                if (request.sequence != playbackSequence) {
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
                if (request.sequence == playbackSequence) speakWithSystem(request)
                true
            }
            player.prepareAsync()
        } catch (_: Exception) {
            releasePlayer(player)
            if (request.sequence == playbackSequence) speakWithSystem(request)
        }
    }

    fun release() {
        stopCurrent()
        playbackSequence = nextSequence.incrementAndGet()
        released = true
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    fun stop() {
        stopCurrent()
        playbackSequence = nextSequence.incrementAndGet()
    }

    private fun dispatchPending() {
        val request = pendingSystemSpeech ?: return
        if (engineFor() == null) return
        pendingSystemSpeech = null
        speakWithSystem(request)
    }

    private fun engineFor(): TextToSpeech? = if (ttsReady) tts else null

    private fun speakWithSystem(request: SystemSpeech) {
        if (request.sequence != playbackSequence) return
        val engine = engineFor()
        if (engine == null) {
            if (ttsFailed) { speechUnavailable(request); return }
            _status.value = "正在启动${if (request.chinese) "中文" else "系统英文"}语音…"
            pendingSystemSpeech = request
            armSystemTimeout(request)
            return
        }
        clearSystemTimeout()
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
            _status.value = _notice.value.orEmpty()
            return
        } else if (!usedFallback) {
            clearNotice()
        }
        val chosenVoice = if (request.chinese) null else applyBestVoice(engine, requested, offlineOnly = false)
        engine.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        engine.setSpeechRate(request.rate)
        engine.setPitch(1f)
        engine.setOnUtteranceProgressListener(utteranceListener(engine, request, requested, chosenVoice))
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        val utteranceId = "alan-pronunciation-${request.sequence}"
        val spoken = engine.speak(request.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        armSystemTimeout(request)
        if (spoken == TextToSpeech.ERROR && languageResult >= TextToSpeech.LANG_AVAILABLE) {
            systemFailed(request)
        }
    }

    private fun utteranceListener(
        engine: TextToSpeech,
        request: SystemSpeech,
        locale: Locale,
        usedVoiceId: String?,
    ) = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            main.post { if (request.sequence == playbackSequence) {
                clearSystemTimeout()
                playbackStarted(if (request.chinese) "中文语音" else "系统英文")
            } }
        }

        override fun onDone(utteranceId: String?) {
            main.post { if (request.sequence == playbackSequence) { clearSystemTimeout(); _status.value = "播放结束。" } }
        }

        @Deprecated("使用带错误码的 onError")
        override fun onError(utteranceId: String?) {
            onError(utteranceId, TextToSpeech.ERROR)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            main.post { handleError(errorCode) }
        }

        private fun handleError(errorCode: Int) {
            if (request.sequence != playbackSequence) return
            clearSystemTimeout()
            if (request.chinese) { systemFailed(request); return }
            val offline = applyBestVoice(engine, locale, offlineOnly = true)
            if (offline == null || offline == usedVoiceId) {
                systemFailed(request)
                return
            }
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = Unit
                @Deprecated("使用带错误码的 onError")
                override fun onError(utteranceId: String?) {
                    main.post { if (request.sequence == playbackSequence) systemFailed(request) }
                }
                override fun onError(utteranceId: String?, errorCode: Int) { onError(utteranceId) }
            })
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
            engine.speak(request.text, TextToSpeech.QUEUE_FLUSH, params, "alan-pronunciation-${request.sequence}-local")
        }
    }

    private fun applyBestVoice(engine: TextToSpeech, locale: Locale, offlineOnly: Boolean): String? {
        val chosen = runCatching {
            val voices = engine.voices.orEmpty().map { voice ->
                PronunciationAudio.VoiceCandidate(
                    id = voice.name,
                    language = voice.locale?.language.orEmpty(),
                    country = voice.locale?.country.orEmpty(),
                    quality = voice.quality,
                    latency = voice.latency,
                    requiresNetwork = voice.isNetworkConnectionRequired,
                )
            }.filter { voice -> !offlineOnly || !voice.requiresNetwork }
            val language = if (locale.language.isNullOrEmpty()) Locale.US.language else locale.language
            PronunciationAudio.chooseVoice(voices, language, locale.country.orEmpty())
        }.getOrNull() ?: return null
        runCatching {
            engine.voices?.firstOrNull { it.name == chosen.id }?.let { engine.voice = it }
        }
        return chosen.id
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
        clearOnlineTimeout()
        clearSystemTimeout()
        pendingSystemSpeech = null
        VolcengineSpeaker.cancel(playbackSequence)
        BundledEnglishSpeaker.cancel(playbackSequence)
        tts?.stop()
        mediaPlayer?.let(::releasePlayer)
    }

    private fun clearOnlineTimeout() { onlineTimeout?.let(main::removeCallbacks); onlineTimeout = null }
    private fun clearSystemTimeout() { systemTimeout?.let(main::removeCallbacks); systemTimeout = null }

    private fun armSystemTimeout(request: SystemSpeech) {
        clearSystemTimeout()
        systemTimeout = Runnable {
            if (request.sequence == playbackSequence) {
                pendingSystemSpeech = null
                tts?.stop()
                systemFailed(request)
            }
        }.also { main.postDelayed(it, 8000) }
    }

    private fun playbackStarted(source: String) { clearNotice(); _status.value = "$source 已开始播放。" }
    private fun speechUnavailable(request: SystemSpeech) {
        pendingSystemSpeech = null
        clearSystemTimeout()
        val message = if (request.chinese) "中文语音服务未能启动，请在手机系统的文字转语音设置中启用或安装中文语音。"
            else "这台手机的语音服务未能启动，请检查系统文字转语音设置。"
        publishNotice("engine", message)
        _status.value = message
    }
    private fun systemFailed(request: SystemSpeech) {
        clearSystemTimeout()
        val message = if (request.chinese) "中文语音没有播放成功，请在系统文字转语音设置中试听，并检查中文语音包是否已下载。"
            else "英文语音没有播放成功，请检查媒体音量及手机的语音服务。"
        publishNotice("speak", message)
        _status.value = message
    }

    private fun releasePlayer(player: MediaPlayer) {
        if (mediaPlayer === player) mediaPlayer = null
        runCatching { player.stop() }
        runCatching { player.reset() }
        runCatching { player.release() }
    }

    private companion object {
        val nextSequence = AtomicLong(0L)
        const val ONLINE_WAIT_MS = 3500L
        const val RECORDED_RATE = 0.95f
    }
}
