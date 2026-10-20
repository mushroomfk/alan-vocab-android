package com.alanbeidanci.app.data

import kotlin.math.exp
import kotlin.math.ln

enum class MemorySkill(val label: String, val importance: Double) {
    MEANING("词义", 0.25), LISTENING("听写", 0.25), SPELLING("拼写", 0.35), CONTEXT("例句", 0.15);

    companion object {
        fun forForm(form: String): MemorySkill? = when (form) {
            "PICK_MEANING" -> MEANING
            "LISTEN" -> LISTENING
            "RECALL", "FADED", "PICK_SPELLING" -> SPELLING
            "CLOZE" -> CONTEXT
            else -> null
        }
    }
}

/** These are explicit product priors, not coefficients fitted from a learner study. */
enum class MemorySource(val coefficient: Double) {
    LEARN(0.35), REVIEW(1.0), TEST(0.9), NEW_TEST(0.5), REPAIR(0.65);
}

data class SkillState(
    val stability: Double = 1.0,
    val difficulty: Double = 5.282434422319005,
    val lastAt: Long = 0,
    val dueAt: Long = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val outcome: String = "UNKNOWN",
) {
    fun valid() = stability.isFinite() && stability in 0.1..36500.0 &&
        difficulty.isFinite() && difficulty in 1.0..10.0 && lastAt >= 0 && dueAt >= 0 &&
        successes >= 0 && failures >= 0 && outcome in setOf("UNKNOWN", "GOOD", "LAPSE", "HINT")
}

data class SkillUpdate(val before: SkillState, val after: SkillState, val weight: Double, val independent: Boolean)

object SkillMemory {
    const val ALGORITHM = "alan-fsrs5-skills-v2"
    const val LEGACY_ALGORITHM = "alan-fsrs5-skills-v1"
    const val DAY = 86_400_000L

    fun formReliability(form: String): Double = when (form) {
        "PICK_MEANING" -> 0.65
        "PICK_SPELLING", "FADED" -> 0.0
        else -> 1.0
    }

    /** Difficulty changes with evidence; stability uses FSRS with the same evidence weight. */
    fun transition(memory: RetentionMath.Memory, good: Boolean, elapsed: Double, weight: Double,
        parameters: RetentionParameters): RetentionMath.Memory {
        require(weight.isFinite() && weight in 0.0..1.0)
        if (weight == 0.0) return memory
        val fsrs = if (good) RetentionMath.reviewGood(memory, elapsed, parameters)
            else RetentionMath.reviewAgain(memory, elapsed, parameters)
        val difficultyDelta = if (good) -0.55 * (memory.difficulty - 1.0) / 9.0
            else 1.4604 * 2.0 * (10.0 - memory.difficulty) / 9.0
        // Interpolate in log space: bounded positive stability, equal treatment of multiplicative changes.
        val stability = exp(ln(memory.stability) + weight * (ln(fsrs.stability) - ln(memory.stability)))
        return RetentionMath.Memory(stability.coerceIn(0.1, 36500.0),
            (memory.difficulty + weight * difficultyDelta).coerceIn(1.0, 10.0))
    }

    fun update(before: SkillState, correct: Boolean, first: Boolean, hinted: Boolean,
        source: MemorySource, form: String, now: Long, parameters: RetentionParameters,
        exposed: Boolean = false): SkillUpdate {
        require(before.valid())
        val independent = first && !hinted && !exposed && formReliability(form) > 0.0
        val elapsed = if (before.lastAt > 0) (now - before.lastAt).coerceAtLeast(0) / DAY.toDouble() else 0.0
        // A second observation on the same calendar day only supplies practice evidence.
        val spaced = before.lastAt == 0L || (now > before.lastAt && MainlineScheduler.dateKey(before.lastAt) != MainlineScheduler.dateKey(now))
        val weight = if (independent && spaced) source.coefficient * formReliability(form) else 0.0
        val memory = transition(RetentionMath.Memory(before.stability, before.difficulty), correct, elapsed, weight, parameters)
        val weak = hinted || !correct
        val days = if (weak || source == MemorySource.LEARN || source == MemorySource.NEW_TEST) 1
            else RetentionMath.intervalDays(memory.stability)
        val evidence = weight > 0.0
        val due = when {
            weak -> minOf(before.dueAt.takeIf { it > now } ?: Long.MAX_VALUE, MainlineScheduler.dueAfterDays(now, 1))
            evidence -> MainlineScheduler.dueAfterDays(now, days)
            else -> before.dueAt.takeIf { it > 0 } ?: MainlineScheduler.dueAfterDays(now, 1)
        }
        return SkillUpdate(before, before.copy(
            stability = memory.stability, difficulty = memory.difficulty,
            lastAt = if (evidence || hinted) now else before.lastAt, dueAt = due,
            successes = before.successes + if (evidence && correct) 1 else 0,
            failures = before.failures + if (first && !correct && !hinted && !exposed) 1 else 0,
            outcome = when { hinted -> "HINT"; !correct -> "LAPSE"; evidence -> if (correct) "GOOD" else "LAPSE"; else -> before.outcome },
        ), weight, independent && evidence)
    }

    /** Legacy marks are priors only. No past per-skill answers are manufactured. */
    fun states(progress: WordProgressEntity, hasExample: Boolean): Map<MemorySkill, SkillState> {
        val stored = decode(progress.skillMemory)
        return MemorySkill.entries.filter { it != MemorySkill.CONTEXT || hasExample }.associateWith { skill ->
            stored[skill] ?: run {
                val mark = mark(progress, skill)
                SkillState(
                    stability = if (mark == 2) progress.stabilityDays.takeIf { it.isFinite() && it > 0 }?.coerceAtMost(36500.0) ?: 1.0 else 1.0,
                    difficulty = if (mark == 1) 6.5 else progress.difficulty.takeIf { it.isFinite() && it in 1.0..10.0 } ?: 5.282434422319005,
                    dueAt = progress.dueAt.coerceAtLeast(0),
                    outcome = if (mark == 1) "LAPSE" else "UNKNOWN",
                )
            }
        }
    }

    fun mark(progress: WordProgressEntity, skill: MemorySkill): Int = when (skill) {
        MemorySkill.MEANING -> progress.abilityMeaning
        MemorySkill.LISTENING -> progress.abilityListening
        MemorySkill.SPELLING -> progress.abilitySpelling
        MemorySkill.CONTEXT -> progress.abilityContext
    }

    fun aggregate(progress: WordProgressEntity, states: Map<MemorySkill, SkillState>, now: Long): WordProgressEntity {
        if (states.isEmpty()) return progress
        val total = states.keys.sumOf { it.importance }
        val average = states.entries.sumOf { it.key.importance * it.value.difficulty } / total
        val difficulty = 0.6 * average + 0.4 * states.values.maxOf { it.difficulty }
        val due = states.values.map { it.dueAt }.filter { it > 0 }.minOrNull() ?: MainlineScheduler.dueAfterDays(now, 1)
        val weak = states.values.any { it.outcome in setOf("LAPSE", "HINT", "UNKNOWN") }
        val fixed = !weak && states.values.all { it.stability >= 30 }
        // Keep inactive legacy context history without using an invalid cloze in the active schedule.
        val stored = decode(progress.skillMemory) + states
        return progress.copy(skillMemory = encode(stored), difficulty = difficulty,
            stabilityDays = states.values.minOf { it.stability },
            dueAt = due,
            category = if (weak) "relearn" else if (fixed) "fixed" else "mastered",
            isMastered = !weak, fixedAt = if (fixed && progress.fixedAt == 0L) now else progress.fixedAt)
    }

    /** Completion of practice is not another independent review. Move only expired active skills. */
    fun finishPractice(states: Map<MemorySkill, SkillState>, now: Long): Map<MemorySkill, SkillState> =
        states.mapValues { (_, s) -> if (s.dueAt == 0L || MainlineScheduler.isDueOnOrBefore(s.dueAt, now))
            s.copy(dueAt = MainlineScheduler.dueAfterDays(now, 1)) else s }

    fun exposedOnDay(exposedAt: Long, now: Long): Boolean = exposedAt > 0 &&
        (exposedAt > now || MainlineScheduler.dateKey(exposedAt) == MainlineScheduler.dateKey(now))

    fun encode(states: Map<MemorySkill, SkillState>): String {
        require(states.values.all { it.valid() })
        return "v1|" + states.entries.sortedBy { it.key.ordinal }.joinToString(";") { (skill, s) ->
            listOf(skill.name, s.stability, s.difficulty, s.lastAt, s.dueAt, s.successes, s.failures, s.outcome).joinToString(",")
        }
    }

    fun decode(value: String): Map<MemorySkill, SkillState> {
        if (value.isBlank()) return emptyMap()
        require(value.startsWith("v1|")) { "不支持的分项记忆版本" }
        val entries = value.removePrefix("v1|").split(';').filter { it.isNotBlank() }.map { row ->
            val p = row.split(','); require(p.size == 8)
            MemorySkill.valueOf(p[0]) to SkillState(p[1].toDouble(), p[2].toDouble(), p[3].toLong(),
                p[4].toLong(), p[5].toInt(), p[6].toInt(), p[7]).also { require(it.valid()) { "分项记忆数值无效" } }
        }
        require(entries.map { it.first }.distinct().size == entries.size)
        return entries.toMap()
    }
}
