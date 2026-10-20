package com.alanbeidanci.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

// 没网或火山没有读过这个词时，用安装包里的 Piper。美式 Kristin，英式 Cori，都是单人女声。
// 模型约 18MB。第一次复制到本机后再加载，避免每次点击都从安装包里读。
object BundledEnglishSpeaker {
    private const val VERSION = "piper-1"
    private const val TARGET_PEAK = 0.97f
    private const val MEMORY_CACHE_LIMIT = 48
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "alan-bundled-tts")
    }
    private val main = Handler(Looper.getMainLooper())
    private val active = AtomicLong(0L)
    private val memoryCache = object : LinkedHashMap<String, CachedAudio>(MEMORY_CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedAudio>?) = size > MEMORY_CACHE_LIMIT
    }
    private val engines = HashMap<String, OfflineTts>()
    private var espeakReady: File? = null
    private var track: AudioTrack? = null
    private var trackSequence = 0L

    fun warmUp(context: Context) {
        val appContext = context.applicationContext
        executor.execute {
            runCatching { engine(appContext, "us") }
        }
    }

    fun speak(
        context: Context,
        text: String,
        accent: String,
        rate: Float,
        sequence: Long,
        onStarted: () -> Unit = {},
        onFailure: () -> Unit,
    ) {
        val appContext = context.applicationContext
        val speed = rate.coerceIn(0.5f, 2f)
        active.set(sequence)
        executor.execute {
            if (active.get() != sequence) return@execute
            try {
                val cached = cachedAudio(appContext, accent, speed, text)
                if (cached != null) {
                    if (active.get() != sequence) return@execute
                    play(cached.samples, cached.sampleRate, sequence)
                    main.post { if (active.get() == sequence) onStarted() }
                    return@execute
                }
                val audio = engine(appContext, accent).generate(text, 0, speed)
                if (active.get() != sequence) return@execute
                if (audio.samples.isEmpty()) error("empty audio")
                val loud = normalizeLoudness(audio.samples)
                remember(appContext, accent, speed, text, loud, audio.sampleRate)
                play(loud, audio.sampleRate, sequence)
                main.post { if (active.get() == sequence) onStarted() }
            } catch (_: Throwable) {
                if (active.get() == sequence) main.post(onFailure)
            }
        }
    }

    fun cancel(sequence: Long? = null) {
        if (sequence == null) active.set(-1L) else active.compareAndSet(sequence, -1L)
        executor.execute { if (sequence == null || trackSequence == sequence) stopTrack() }
    }

    private fun engine(context: Context, accent: String): OfflineTts {
        val key = if (accent == "uk") "uk" else "us"
        engines[key]?.let { return it }
        val voiceDir = prepareVoice(context, key)
        val vits = OfflineTtsVitsModelConfig().apply {
            model = File(voiceDir, "model.onnx").absolutePath
            tokens = File(voiceDir, "tokens.txt").absolutePath
            dataDir = prepareEspeak(context).absolutePath
            noiseScale = 0.667f
            noiseScaleW = 0.8f
            lengthScale = 1f
        }
        val model = OfflineTtsModelConfig().apply {
            this.vits = vits
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            debug = false
            provider = "cpu"
        }
        // 音素目录是普通文件路径时，不能再把 AssetManager 传进去，否则每次合成都会多走一层资源查找。
        val created = OfflineTts(null, OfflineTtsConfig().apply { this.model = model })
        engines[key] = created
        return created
    }

    private fun prepareVoice(context: Context, key: String): File {
        val assetName = if (key == "uk") "en-GB" else "en-US"
        val root = File(context.filesDir, "bundled-tts/$VERSION/$assetName")
        val marker = File(root, ".ready")
        if (!marker.isFile || !File(root, "model.onnx").isFile) {
            if (root.exists()) root.deleteRecursively()
            root.mkdirs()
            copyAssetFile(context, "tts/piper/$assetName/model.onnx", File(root, "model.onnx"))
            copyAssetFile(context, "tts/piper/$assetName/tokens.txt", File(root, "tokens.txt"))
            marker.writeText(VERSION)
        }
        return root
    }

    private fun prepareEspeak(context: Context): File {
        espeakReady?.let { return it }
        val root = File(context.filesDir, "bundled-tts/$VERSION/espeak-ng-data")
        val marker = File(root, ".ready")
        if (!marker.isFile) {
            if (root.exists()) root.deleteRecursively()
            copyAssetDir(context, "tts/espeak-ng-data", root)
            marker.writeText(VERSION)
        }
        espeakReady = root
        return root
    }

    private fun copyAssetFile(context: Context, assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output, bufferSize = 1024 * 1024) }
        }
    }

    private fun copyAssetDir(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath)
        if (children.isNullOrEmpty()) {
            copyAssetFile(context, assetPath, dest)
            return
        }
        dest.mkdirs()
        for (child in children) {
            copyAssetDir(context, "$assetPath/$child", File(dest, child))
        }
    }

    private fun cachedAudio(context: Context, accent: String, speed: Float, text: String): CachedAudio? {
        val key = cacheKey(accent, speed, text)
        memoryCache[key]?.let { return it }
        val file = cacheFile(context, key)
        if (!file.isFile) return null
        val loaded = runCatching { readCache(file) }.getOrNull() ?: return null
        memoryCache[key] = loaded
        return loaded
    }

    private fun remember(context: Context, accent: String, speed: Float, text: String, samples: FloatArray, sampleRate: Int) {
        val key = cacheKey(accent, speed, text)
        val cached = CachedAudio(samples, sampleRate)
        memoryCache[key] = cached
        runCatching { writeCache(cacheFile(context, key), cached) }
    }

    private fun cacheKey(accent: String, speed: Float, text: String): String {
        val raw = "$VERSION|$accent|$speed|$text"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun cacheFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, "tts-piper")
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, "$key.pcm")
    }

    private fun readCache(file: File): CachedAudio {
        file.inputStream().use { input ->
            val data = DataInputStream(input)
            val sampleRate = data.readInt()
            val count = data.readInt()
            if (sampleRate !in 8_000..48_000 || count !in 1..480_000) error("bad cache")
            val samples = FloatArray(count) { data.readFloat() }
            return CachedAudio(samples, sampleRate)
        }
    }

    private fun writeCache(file: File, audio: CachedAudio) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.outputStream().use { output ->
            val data = DataOutputStream(output)
            data.writeInt(audio.sampleRate)
            data.writeInt(audio.samples.size)
            audio.samples.forEach(data::writeFloat)
        }
        if (!tmp.renameTo(file)) tmp.delete()
    }

    // 模型原始波形峰值往往只有满幅的一小半，不放大就会听着很小。
    private fun normalizeLoudness(samples: FloatArray): FloatArray {
        var peak = 0f
        for (sample in samples) {
            val level = abs(sample)
            if (level > peak) peak = level
        }
        if (peak < 0.01f) return samples
        val gain = (TARGET_PEAK / peak).coerceAtMost(6f)
        if (gain <= 1.02f) return samples
        return FloatArray(samples.size) { index ->
            (samples[index] * gain).coerceIn(-1f, 1f)
        }
    }

    private fun play(samples: FloatArray, sampleRate: Int, sequence: Long) {
        val pcm = ShortArray(samples.size) { index ->
            (samples[index].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        val next = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        next.setVolume(1f)
        if (next.write(pcm, 0, pcm.size) <= 0) {
            next.release()
            error("playback failed")
        }
        stopTrack()
        track = next
        trackSequence = sequence
        next.play()
    }

    private fun stopTrack() {
        val current = track ?: return
        track = null
        runCatching { current.pause() }
        runCatching { current.flush() }
        runCatching { current.release() }
    }

    private data class CachedAudio(val samples: FloatArray, val sampleRate: Int)
}
