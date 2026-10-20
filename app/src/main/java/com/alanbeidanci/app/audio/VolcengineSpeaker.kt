package com.alanbeidanci.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.alanbeidanci.app.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// 豆包只在「这个词还没存在本机，并且手机连着网」时请求一次。存下来之后直接播。
// https://docs.volcengine.com/docs/DoubaoVoice/unidirectional-streaming-text-to-speech-http
object VolcengineSpeaker {
    private const val ENDPOINT = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
    private const val SUCCESS_END = 20000000
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "alan-volcengine-tts")
    }
    private val active = AtomicLong(0L)
    private val main = Handler(Looper.getMainLooper())
    private data class PendingConnection(val sequence: Long, val connection: HttpURLConnection)
    private val pendingConnection = AtomicReference<PendingConnection?>(null)
    private val cancellations = Executors.newSingleThreadExecutor()
    private class SpeechRequestFailure(val reason: String) : Exception()
    private var player: MediaPlayer? = null
    private var playerSequence = 0L

    fun speak(
        context: Context,
        text: String,
        accent: String,
        rate: Float,
        sequence: Long,
        onStarted: () -> Unit = {},
        onFailure: (String) -> Unit,
    ): Boolean {
        val appContext = context.applicationContext
        val speed = rate.coerceIn(0.5f, 2f)
        active.set(sequence)
        executor.execute {
            if (active.get() != sequence) return@execute
            try {
                val file = audioFile(appContext, accent, speed, text)
                if (!file.isFile || file.length() == 0L) {
                    if (active.get() != sequence) return@execute
                    if (!isOnline(appContext)) throw SpeechRequestFailure("没有可用网络")
                    val key = SpeechCredentials.read(appContext)
                    if (key.isBlank()) throw SpeechRequestFailure(if (SpeechCredentials.isConfigured(appContext))
                        "本机 Key 无法读取，请重新填写" else "未配置在线发音 Key")
                    val bytes = requestAudio(text, accent, speed, key, sequence)
                    if (active.get() != sequence) return@execute
                    file.parentFile?.mkdirs()
                    val tmp = File(file.parentFile, "${file.name}.tmp")
                    tmp.writeBytes(bytes)
                    if (!tmp.renameTo(file)) {
                        tmp.copyTo(file, overwrite = true)
                        tmp.delete()
                    }
                }
                if (active.get() != sequence || !file.isFile) return@execute
                try {
                    play(file, sequence) {
                        main.post { if (active.get() == sequence) onFailure("在线音频播放失败") }
                    }
                    main.post { if (active.get() == sequence) onStarted() }
                } catch (error: Throwable) {
                    file.delete()
                    throw error
                }
            } catch (error: Throwable) {
                val reason = when (error) {
                    is SpeechRequestFailure -> error.reason
                    is java.net.SocketTimeoutException -> "在线语音连接超时"
                    is java.io.IOException -> "无法连接在线语音服务"
                    else -> "在线语音响应或播放失败"
                }
                main.post { if (active.get() == sequence) onFailure(reason) }
            }
        }
        return true
    }

    private fun isOnline(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return manager.activeNetwork != null
    }

    fun cancel(sequence: Long? = null) {
        if (sequence == null) active.set(-1L) else active.compareAndSet(sequence, -1L)
        pendingConnection.get()?.let { pending ->
            if ((sequence == null || pending.sequence == sequence) && pendingConnection.compareAndSet(pending, null)) {
                cancellations.execute { runCatching { pending.connection.disconnect() } }
            }
        }
        executor.execute { if (sequence == null || playerSequence == sequence) stopPlayer() }
    }

    private fun requestAudio(text: String, accent: String, speed: Float, apiKey: String, sequence: Long): ByteArray {
        val voice = if (accent == "uk") BuildConfig.VOLCENGINE_VOICE_UK else BuildConfig.VOLCENGINE_VOICE_US
        val speechRate = ((speed - 1f) * 100f).toInt().coerceIn(-50, 100)
        val body = JSONObject().put("req_params", JSONObject()
            .put("text", text)
            .put("speaker", voice)
            .put("explicit_language", "en")
            .put("audio_params", JSONObject()
                .put("format", "mp3")
                .put("sample_rate", 24000)
                .put("speech_rate", speechRate)))
        val payload = body.toString().toByteArray()
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            setFixedLengthStreamingMode(payload.size)
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("X-Api-Key", apiKey)
            setRequestProperty("X-Api-Resource-Id", "seed-tts-2.0")
            setRequestProperty("X-Api-Request-Id", UUID.randomUUID().toString())
        }
        val pending = PendingConnection(sequence, connection)
        pendingConnection.set(pending)
        try {
            if (active.get() != sequence) throw SpeechRequestFailure("请求已取消")
            connection.outputStream.use { it.write(payload) }
            val httpCode = connection.responseCode
            if (httpCode !in 200..299) throw SpeechRequestFailure(when (httpCode) {
                401, 403 -> "Key 无效或没有语音服务权限"
                429 -> "在线语音额度不足或请求过多"
                else -> "在线语音服务返回错误（HTTP $httpCode）"
            })
            val responseText = connection.inputStream
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            return readAudioChunks(responseText)
        } finally {
            pendingConnection.compareAndSet(pending, null)
            connection.disconnect()
        }
    }

    // 接口把多段 JSON 拼在一起返回。code 0 的 data 是一段 MP3，20000000 表示结束。
    private fun readAudioChunks(text: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false
        var finished = false
        for (index in text.indices) {
            val char = text[index]
            if (depth == 0) {
                if (char.isWhitespace()) continue
                require(!finished && char == '{') { "invalid audio response" }
            }
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) start = index
                    depth += 1
                }
                '}' -> {
                    if (depth == 0) continue
                    depth -= 1
                    if (depth != 0 || start < 0) continue
                    val json = JSONObject(text.substring(start, index + 1))
                    val code = json.getInt("code")
                    if (code != 0 && code != SUCCESS_END) throw SpeechRequestFailure("在线语音请求失败（服务码 $code）")
                    val encoded = if (json.isNull("data")) "" else json.optString("data")
                    if (encoded.isNotBlank()) output.write(Base64.decode(encoded, Base64.DEFAULT))
                    if (code == SUCCESS_END) finished = true
                    start = -1
                }
            }
        }
        require(finished && depth == 0 && !inString && start == -1) { "incomplete audio response" }
        val bytes = output.toByteArray()
        if (bytes.isEmpty()) error("empty audio")
        return bytes
    }

    private fun audioFile(context: Context, accent: String, speed: Float, text: String): File {
        val voice = if (accent == "uk") BuildConfig.VOLCENGINE_VOICE_UK else BuildConfig.VOLCENGINE_VOICE_US
        val raw = "$voice|$speed|$text"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "tts-online"), "$name.mp3")
    }

    private fun play(file: File, sequence: Long, onFailure: () -> Unit) {
        val next = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setDataSource(file.absolutePath)
            setVolume(1f, 1f)
            prepare()
        }
        stopPlayer()
        player = next
        playerSequence = sequence
        next.setOnErrorListener { failed, _, _ ->
            executor.execute { if (player === failed) stopPlayer() }
            onFailure()
            true
        }
        next.setOnCompletionListener { completed ->
            executor.execute { if (player === completed) stopPlayer() }
        }
        next.start()
    }

    private fun stopPlayer() {
        val current = player ?: return
        player = null
        runCatching { current.stop() }
        runCatching { current.release() }
    }
}
