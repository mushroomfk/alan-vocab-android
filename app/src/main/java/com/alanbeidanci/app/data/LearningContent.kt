package com.alanbeidanci.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

data class LearningContent(
    val roots: List<String> = emptyList(),
    val suffixes: List<String> = emptyList(),
    val wordParts: List<String> = emptyList(),
    val wordFamily: List<String> = emptyList(),
    val collocations: List<String> = emptyList(),
    val coreExample: String = "",
    val coreExampleTranslation: String = "",
    val contextExample: String = "",
    val contextExampleTranslation: String = "",
    val curated: Boolean = false,
) {
    val structure: List<String> get() = roots + suffixes + wordParts
    val hasContent: Boolean get() =
        structure.isNotEmpty() || wordFamily.isNotEmpty() || collocations.isNotEmpty() ||
            coreExample.isNotBlank() || contextExample.isNotBlank()
}

/** Resolves the same reviewed learning content used by the Web client. */
object LearningContentPresentation {
    fun clozeFor(context: Context, word: WordEntity): ClozeQuestion? {
        val content = forWord(context, word)
        return listOf(content.coreExample, content.contextExample, word.example)
            .firstNotNullOfOrNull { ClozeQuestion.create(it, word.word) }
    }

    @Volatile
    private var cachedContent: JSONObject? = null

    fun forWord(context: Context, word: WordEntity): LearningContent {
        val source = loadContent(context)
        val spelling = normalizedSpelling(word.word)
        val generic = source.optJSONObject("generic")?.optJSONObject(spelling) ?: JSONObject()
        val contextual = source.optJSONObject("context")?.optJSONObject(contextKey(word.bookId, spelling)) ?: JSONObject()

        return LearningContent(
            roots = list(contextual, generic, "roots"),
            suffixes = list(contextual, generic, "suffixes"),
            wordParts = list(contextual, generic, "wordParts"),
            wordFamily = list(contextual, generic, "wordFamily"),
            collocations = list(contextual, generic, "collocations"),
            coreExample = text(contextual, generic, "coreExample").ifBlank { word.example.trim() },
            coreExampleTranslation = text(contextual, generic, "coreExampleTranslation")
                .ifBlank { word.exampleTranslation.trim() },
            contextExample = text(contextual, generic, "contextExample"),
            contextExampleTranslation = text(contextual, generic, "contextExampleTranslation"),
            curated = generic.length() > 0 || contextual.length() > 0,
        )
    }

    fun canonicalSpelling(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(TRAILING_ANNOTATION, "")
        .replace(WHITESPACE, " ")
        .trim()

    fun normalizedSpelling(value: String): String = canonicalSpelling(value).lowercase()

    private fun loadContent(context: Context): JSONObject {
        cachedContent?.let { return it }
        return synchronized(this) {
            cachedContent ?: runCatching {
                context.applicationContext.assets.open("learning-content.json")
                    .bufferedReader(Charsets.UTF_8)
                    .use { JSONObject(it.readText()) }
            }.getOrElse { JSONObject() }.also { cachedContent = it }
        }
    }

    private fun contextKey(bookId: String, spelling: String): String = when {
        bookId.contains("minecraft", ignoreCase = true) -> "minecraft-game:$spelling"
        bookId.contains("nce", ignoreCase = true) -> "nce-all:$spelling"
        bookId.contains("xiangshao", ignoreCase = true) -> "xiangshao-all:$spelling"
        else -> ""
    }

    private fun list(contextual: JSONObject, generic: JSONObject, key: String): List<String> =
        contextual.optJSONArray(key)?.strings().orEmpty().ifEmpty {
            generic.optJSONArray(key)?.strings().orEmpty()
        }

    private fun text(contextual: JSONObject, generic: JSONObject, key: String): String =
        contextual.optString(key).trim().ifBlank { generic.optString(key).trim() }

    private fun JSONArray.strings(): List<String> = buildList {
        for (index in 0 until length()) {
            optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
        }
    }

    private val TRAILING_ANNOTATION = Regex("\\s*\\([^)]*\\)\\s*$")
    private val WHITESPACE = Regex("\\s+")
}
