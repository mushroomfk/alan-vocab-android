package com.alanbeidanci.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

@Entity(tableName = "dual_test_sessions")
data class DualTestSessionEntity(@PrimaryKey val key: String, val kind: String, val payload: String) {
    fun checkpoint(): DualTestCheckpoint = DualTestCheckpoint.decode(payload).also {
        require(it.key == key && it.kind.name == kind) { "双测断点身份无效" }
    }
}

/** Round 0 is listening; round 1 is Chinese-to-English spelling. Answers and checkpoint commit together. */
data class DualTestCheckpoint(
    val key: String,
    val kind: MainlineTestKind,
    val wordIds: List<Long>,
    val sessionId: String = UUID.randomUUID().toString(),
    val revision: Long = 0,
    val round: Int = 0,
    val index: Int = 0,
    val listening: Map<Long, Boolean> = emptyMap(),
    val spelling: Map<Long, Boolean> = emptyMap(),
    val input: String = "",
    val holdResult: Boolean = false,
    val holdCorrect: Boolean = false,
    val currentWrong: Int = 0,
    val totalWrong: Int = 0,
    val typedChars: Int = 0,
    val correctChars: Int = 0,
    val preparedAt: Long = 0,
    val answeredAt: Long = 0,
    val exposed: Boolean = false,
) {
    val wordId: Long get() = wordIds[index]
    val completedQuestions: Int get() = listening.size + spelling.size
    val bothCorrect: Int get() = spelling.count { (id, correct) -> correct && listening[id] == true }

    fun valid(): Boolean = key.startsWith("${kind.name}:") && sessionId.isNotBlank() && revision >= 0 &&
        wordIds.isNotEmpty() && wordIds.all { it > 0 } && wordIds.distinct().size == wordIds.size &&
        round in 0..1 && index in wordIds.indices &&
        listening.keys == (if (round == 0) wordIds.take(index) else wordIds).toSet() &&
        spelling.keys == (if (round == 1) wordIds.take(index) else emptyList()).toSet() &&
        currentWrong >= 0 && totalWrong >= currentWrong && typedChars >= 0 && correctChars in 0..typedChars &&
        preparedAt >= 0 && answeredAt >= 0 && (!holdCorrect || holdResult) &&
        (if (holdResult) preparedAt > 0 && answeredAt >= preparedAt else answeredAt == 0L)

    fun entity(): DualTestSessionEntity {
        require(valid()) { "双测断点无效" }
        fun results(rows: Map<Long, Boolean>) = JSONObject().also { o -> rows.forEach { (id, v) -> o.put(id.toString(), v) } }
        return DualTestSessionEntity(key, kind.name, JSONObject()
            .put("version", 1).put("key", key).put("kind", kind.name).put("sessionId", sessionId)
            .put("wordIds", JSONArray(wordIds)).put("revision", revision).put("round", round).put("index", index)
            .put("listening", results(listening)).put("spelling", results(spelling))
            .put("input", input).put("holdResult", holdResult).put("holdCorrect", holdCorrect)
            .put("currentWrong", currentWrong).put("totalWrong", totalWrong).put("typedChars", typedChars)
            .put("correctChars", correctChars).put("preparedAt", preparedAt).put("answeredAt", answeredAt)
            .put("exposed", exposed).toString())
    }

    fun afterAnswer(correct: Boolean): DualTestCheckpoint? {
        require(valid() && holdResult)
        val english = if (round == 0) listening + (wordId to correct) else listening
        val chinese = if (round == 1) spelling + (wordId to correct) else spelling
        if (round == 1 && index == wordIds.lastIndex) return null
        return copy(revision = revision + 1, round = if (index == wordIds.lastIndex) 1 else round,
            index = if (index == wordIds.lastIndex) 0 else index + 1, listening = english, spelling = chinese,
            input = "", holdResult = false, holdCorrect = false, currentWrong = 0,
            preparedAt = 0, answeredAt = 0, exposed = false)
    }

    companion object {
        fun key(kind: MainlineTestKind, settings: UserSettings): String {
            val scope = settings.selectedBookIds.sorted().joinToString("\u0000") + "\u0001" + settings.selectedUnitKeys.sorted().joinToString("\u0000")
            val hash = MessageDigest.getInstance("SHA-256").digest(scope.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "${kind.name}:$hash"
        }

        fun decode(value: String): DualTestCheckpoint {
            val o = JSONObject(value)
            require(o.getInt("version") == 1) { "双测断点版本无效" }
            fun results(name: String): Map<Long, Boolean> {
                val obj = o.getJSONObject(name)
                return obj.keys().asSequence().associate { it.toLong() to obj.getBoolean(it) }
            }
            val ids = o.getJSONArray("wordIds")
            return DualTestCheckpoint(o.getString("key"), MainlineTestKind.valueOf(o.getString("kind")),
                (0 until ids.length()).map { ids.getLong(it) }, o.getString("sessionId"), o.getLong("revision"),
                o.getInt("round"), o.getInt("index"), results("listening"), results("spelling"),
                o.getString("input"), o.getBoolean("holdResult"), o.getBoolean("holdCorrect"),
                o.getInt("currentWrong"), o.getInt("totalWrong"), o.getInt("typedChars"), o.getInt("correctChars"),
                o.getLong("preparedAt"), o.getLong("answeredAt"), o.getBoolean("exposed"))
                .also { require(it.valid()) { "双测断点数据无效" } }
        }
    }
}

@Dao
interface DualTestSessionDao {
    @Query("SELECT * FROM dual_test_sessions WHERE `key` = :key") suspend fun get(key: String): DualTestSessionEntity?
    @Query("SELECT * FROM dual_test_sessions") suspend fun getAll(): List<DualTestSessionEntity>
    @Upsert suspend fun save(item: DualTestSessionEntity)
    @Query("DELETE FROM dual_test_sessions WHERE `key` = :key") suspend fun delete(key: String)
    @Query("DELETE FROM dual_test_sessions") suspend fun clear()
}
