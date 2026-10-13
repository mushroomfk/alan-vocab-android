package com.alanbeidanci.app.data

import android.content.Context
import org.json.JSONObject

/** Adds presentation metadata for users upgrading from dictionaries seeded before POS support. */
object WordPresentation {
    @Volatile
    private var cachedLexicon: JSONObject? = null

    fun partOfSpeech(context: Context, word: WordEntity): String {
        val found = linkedSetOf<String>()
        collectParts(word.partOfSpeech, found)
        POS_REGEX.findAll(word.translation).forEach { collectParts(it.groupValues[1], found) }
        collectParts(lexicon(context).optString(word.word.trim().lowercase()), found)
        return POS_ORDER.filter(found::contains).joinToString("/")
    }

    fun cleanTranslation(value: String): String = POS_REGEX.replace(value) { match ->
        if (match.range.first == 0) "" else match.value.replace(match.groupValues[1], "")
    }.trim()

    fun phoneticLine(phoneticUs: String, phoneticUk: String): String = listOfNotNull(
        phoneticUs.trim().takeIf { it.isNotBlank() }?.let { "美 $it" },
        phoneticUk.trim().takeIf { it.isNotBlank() }?.let { "英 $it" },
    ).joinToString("    ")

    private fun lexicon(context: Context): JSONObject {
        cachedLexicon?.let { return it }
        return synchronized(this) {
            cachedLexicon ?: runCatching {
                context.applicationContext.assets.open("pos-lexicon.json")
                    .bufferedReader(Charsets.UTF_8)
                    .use { JSONObject(it.readText()) }
            }.getOrElse { JSONObject() }.also { cachedLexicon = it }
        }
    }

    private fun collectParts(value: String, target: MutableSet<String>) {
        value.lowercase().split("/", " ", ";", "；", ",", "，").forEach { raw ->
            val part = when (raw.trim()) {
                "vt.", "vi.", "v." -> "v."
                "a.", "adj." -> "adj."
                "ad.", "adv." -> "adv."
                "n.", "pron.", "prep.", "conj.", "num.", "int.", "aux.", "art." -> raw.trim()
                else -> ""
            }
            if (part.isNotBlank()) target += part
        }
    }

    private val POS_REGEX = Regex(
        "(?:^|[\\s;；,，])(n\\.|v\\.|vt\\.|vi\\.|a\\.|adj\\.|ad\\.|adv\\.|pron\\.|prep\\.|conj\\.|num\\.|int\\.|aux\\.|art\\.)\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val POS_ORDER = listOf("n.", "v.", "adj.", "adv.", "pron.", "prep.", "conj.", "num.", "int.", "aux.", "art.")
}
