package com.alanbeidanci.app.data

import java.net.URLDecoder

object LocalWordLookup {
    private val chinese = Regex("[\\p{IsHan}]{1,24}")
    private val english = Regex("[A-Za-z][A-Za-z'-]{0,63}")
    private val schemes = setOf("http", "https", "alan")

    fun scanTerm(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        jsonTerm(text)?.let { return clip(it) }
        uriTerm(text)?.let { return clip(it) }
        return extractTerm(text)?.let(::clip)
    }

    fun likePattern(query: String): String =
        query.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    private fun clip(value: String): String? {
        val trimmed = value.trim().replace(Regex("\\s+"), " ")
        if (trimmed.isEmpty()) return null
        return trimmed.take(64)
    }

    private fun jsonTerm(raw: String): String? {
        if (!raw.startsWith("{")) return null
        val match = Regex("\"(?:word|query|q|text)\"\\s*:\\s*\"([^\"\\\\]+)\"").find(raw) ?: return null
        return match.groupValues[1]
    }

    private fun uriTerm(raw: String): String? {
        val scheme = raw.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme !in schemes) return null
        val query = raw.substringAfter('?', "").substringBefore('#')
        val params = query.split('&').mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            if (pieces.size != 2) null else decode(pieces[0]) to decode(pieces[1])
        }.toMap()
        return params["word"] ?: params["query"] ?: params["q"]
            ?: raw.substringBefore('?').substringBefore('#').substringAfterLast('/').takeIf { it.isNotBlank() }
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun extractTerm(value: String): String? = chinese.find(value)?.value ?: english.find(value)?.value
}
