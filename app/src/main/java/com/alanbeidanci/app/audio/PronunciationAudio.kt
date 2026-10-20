package com.alanbeidanci.app.audio

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

object PronunciationAudio {
    private const val ROOT = "/pronunciation/v1"
    private val NOVELTY_VOICE = listOf(
        "albert", "bad news", "bahh", "bells", "boing", "bubbles", "cellos",
        "good news", "jester", "organ", "superstar", "trinoids", "wobble", "zarvox",
        "fred", "kathy", "ralph",
    )

    data class VoiceCandidate(
        val id: String,
        val language: String,
        val country: String,
        val quality: Int,
        val latency: Int,
        val requiresNetwork: Boolean,
    )

    // 单机版没有自带录音，只能用手机语音。优先本机的自然英语音色；只有本机音色很差时才用要联网的高音质语音。
    fun chooseVoice(voices: List<VoiceCandidate>, language: String, country: String): VoiceCandidate? {
        val requestedLanguage = language.lowercase()
        val requestedCountry = country.lowercase()
        val eligible = voices.filter { voice ->
            voice.language.lowercase() == requestedLanguage &&
                (requestedCountry.isEmpty() || voice.country.isEmpty() || voice.country.lowercase() == requestedCountry) &&
                NOVELTY_VOICE.none { voice.id.lowercase().contains(it) }
        }
        if (eligible.isEmpty()) return null
        val offlineNatural = eligible.filter { voice ->
            !voice.requiresNetwork && (voice.quality >= 400 || naturalness(voice.id) >= 500)
        }
        val pool = if (offlineNatural.isNotEmpty()) offlineNatural else eligible
        return pool.maxByOrNull { voice ->
            voice.quality * 1_000_000 +
                naturalness(voice.id) * 1_000 -
                (if (voice.requiresNetwork) 2_000 else 0) -
                voice.latency
        }
    }

    private fun naturalness(id: String): Int {
        val name = id.lowercase()
        var score = 0
        if (name.contains("-x-") || name.contains("neural") || name.contains("wavenet")) score += 500
        if (name.contains("local")) score += 100
        if (name.contains("pico") || name.contains("espeak")) score -= 500
        return score
    }

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("\\s*\\([^)]*\\)\\s*$"), "")
        .replace(Regex("\\s+"), " ")

    fun key(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(normalize(value).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun path(value: String, accent: String): String {
        val locale = if (accent == "uk") "en-GB" else "en-US"
        return "$ROOT/$locale/${key(value)}.flac"
    }
}
