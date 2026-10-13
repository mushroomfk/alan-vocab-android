package com.alanbeidanci.app.audio

import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

object PronunciationAudio {
    private const val ROOT = "/pronunciation/v1"

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
