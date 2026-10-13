package com.alanbeidanci.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class AssetDictionary(
    val book: WordBookEntity,
    val words: List<WordEntity>,
)

/** Loads the same manifest and JSON dictionaries used by the web client. */
class DictionaryAssetLoader(private val context: Context) {
    private val manifest: JSONObject by lazy {
        context.assets.open("dictionary-manifest.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    }

    val version: Int get() = manifest.optInt("version", 1)

    val dictionaryCount: Int get() = manifest.optJSONArray("dictionaries")?.length() ?: 0

    private val posLexicon: JSONObject by lazy {
        runCatching {
            context.assets.open("pos-lexicon.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        }.getOrElse { JSONObject() }
    }

    suspend fun forEachDictionary(
        onBook: suspend (index: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
        block: suspend (AssetDictionary) -> Unit,
    ) {
        val entries = manifest.optJSONArray("dictionaries") ?: JSONArray()
        val total = entries.length()
        for (bookIndex in 0 until total) {
            val entry = entries.optJSONObject(bookIndex) ?: continue
            val id = entry.optString("id").takeIf { it.isNotBlank() } ?: continue
            val name = entry.optString("name", id)
            onBook(bookIndex, total, name)
            val path = entry.optString("url").removePrefix("/")
            val rawWords = runCatching {
                context.assets.open(path).bufferedReader(Charsets.UTF_8).use { JSONArray(it.readText()) }
            }.getOrElse { JSONArray() }
            val book = WordBookEntity(
                id = id,
                name = entry.optString("name", id),
                subtitle = "${entry.optString("category", "本地词库")} · ${entry.optInt("length", rawWords.length())} 词",
                category = entry.optString("category", "其他词库"),
                description = entry.optString("description", "本地词库"),
                colorHex = colorFor(entry.optString("category"), entry.optInt("orderIndex", bookIndex)),
                orderIndex = entry.optInt("orderIndex", bookIndex),
            )
            val words = buildList {
                val orderIndex = entry.optInt("orderIndex", bookIndex)
                for (wordIndex in 0 until rawWords.length()) {
                    val raw = rawWords.optJSONObject(wordIndex) ?: continue
                    val name = raw.optString("name", raw.optString("word", "")).trim()
                    if (name.isBlank()) continue
                    val translations = translations(raw)
                    val partOfSpeech = inferPartOfSpeech(raw, translations)
                    val cleanTranslations = translations.map(::cleanTranslation).filter { it.isNotBlank() }
                    val chapter = raw.optInt("chapter", wordIndex / 20 + 1)
                    val unitId = raw.optString("unitId").ifBlank { "chapter-$chapter" }
                    val unitName = raw.optString("unitName").ifBlank { "第 ${chapter} 单元" }
                    add(
                        WordEntity(
                            id = stableWordId(orderIndex, wordIndex),
                            bookId = id,
                            word = name,
                            phoneticUs = raw.optString("usphone", raw.optString("phoneticUs", "")),
                            phoneticUk = raw.optString("ukphone", raw.optString("phoneticUk", "")),
                            translation = cleanTranslations.joinToString("；").ifBlank { "暂无释义" },
                            partOfSpeech = partOfSpeech,
                            example = raw.optString("example", ""),
                            exampleTranslation = raw.optString("exampleTranslation", ""),
                            chapter = chapter,
                            orderIndex = raw.optInt("orderIndex", wordIndex + 1),
                            unitId = unitId,
                            unitName = unitName,
                            folderId = raw.optString("folderId", ""),
                            folderName = raw.optString("folderName", ""),
                        ),
                    )
                }
            }
            block(AssetDictionary(book, words))
        }
    }

    private fun translations(raw: JSONObject): List<String> {
        val array = raw.optJSONArray("trans") ?: return listOfNotNull(raw.optString("translation").takeIf { it.isNotBlank() })
        return buildList {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun inferPartOfSpeech(raw: JSONObject, translations: List<String>): String {
        raw.optString("partOfSpeech").trim().takeIf { it.isNotBlank() }?.let { return it }
        val found = linkedSetOf<String>()
        translations.forEach { translation ->
            POS_REGEX.findAll(translation).forEach { match ->
                val value = match.groupValues[1].lowercase()
                found += when (value) {
                    "vt.", "vi." -> "v."
                    "a." -> "adj."
                    "ad." -> "adv."
                    else -> value
                }
            }
        }
        val indexed = posLexicon.optString(raw.optString("name", raw.optString("word", "")).trim().lowercase())
        indexed.split("/").filter { it.isNotBlank() }.forEach(found::add)
        return POS_ORDER.filter(found::contains).joinToString("/")
    }

    private fun cleanTranslation(value: String): String = POS_REGEX.replace(value) { match ->
        if (match.range.first == 0) "" else match.value.replace(match.groupValues[1], "")
    }.trim()

    private fun stableWordId(orderIndex: Int, wordIndex: Int): Long =
        (orderIndex + 1L) * 1_000_000L + wordIndex + 1L

    private fun colorFor(category: String, index: Int): Long = when {
        category.contains("国际") -> 0xFF6B7DB3
        category.contains("青少年") -> 0xFFCE9A2C
        category.contains("代码") -> 0xFF7A5AF8
        category.contains("日语") -> 0xFFB85C8A
        category.contains("德语") -> 0xFF9B6A3C
        category.contains("哈萨克") -> 0xFF4F7D3A
        category.contains("印尼") -> 0xFF148B80
        category.contains("美剧") -> 0xFFD06B4F
        else -> if (index % 2 == 0) 0xFF148B80 else 0xFF3F7CAC
    }

    companion object {
        private val POS_REGEX = Regex("(?:^|[\\s;；,，])(n\\.|v\\.|vt\\.|vi\\.|a\\.|adj\\.|ad\\.|adv\\.|pron\\.|prep\\.|conj\\.|num\\.|int\\.|aux\\.|art\\.)\\s*", RegexOption.IGNORE_CASE)
        private val POS_ORDER = listOf("n.", "v.", "adj.", "adv.", "pron.", "prep.", "conj.", "num.", "int.", "aux.", "art.")
    }
}
