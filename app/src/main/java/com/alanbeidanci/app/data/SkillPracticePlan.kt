package com.alanbeidanci.app.data

data class SkillPracticeTarget(val skill: MemorySkill, val required: Int, val passed: Int = 0, val attempts: Int = 0) {
    fun valid() = required in 1..3 && passed in 0..required && attempts in passed..1000
}

data class SkillPracticePlan(val targets: List<SkillPracticeTarget>, val review: Boolean) {
    fun pending(): SkillPracticeTarget? = targets.firstOrNull { it.passed < it.required }
    fun first(skill: MemorySkill) = (targets.firstOrNull { it.skill == skill }?.attempts ?: 0) == 0
    fun record(skill: MemorySkill, correct: Boolean, independent: Boolean): SkillPracticePlan = copy(targets = targets.map {
        if (it.skill != skill) it else it.copy(
            required = if (!correct || !independent) maxOf(it.required, 2) else it.required,
            passed = if (correct && independent) (it.passed + 1).coerceAtMost(3) else it.passed,
            attempts = (it.attempts + 1).coerceAtMost(1000),
        )
    })

    /** 还不会这个词时，先多看英文选中文。例句填空留到词义后面。 */
    fun preferMeaningRecognition(): SkillPracticePlan {
        val meaning = targets.find { it.skill == MemorySkill.MEANING } ?: SkillPracticeTarget(MemorySkill.MEANING, 1)
        val required = if (meaning.passed >= meaning.required) {
            (meaning.passed + 1).coerceAtMost(3)
        } else {
            maxOf(meaning.required, (meaning.passed + 2).coerceAtMost(3))
        }
        val bumped = meaning.copy(required = required.coerceIn(1, 3), passed = meaning.passed.coerceAtMost(required))
        val rest = targets.filter { it.skill != MemorySkill.MEANING }
        return copy(targets = listOf(bumped) + rest.filter { it.skill != MemorySkill.CONTEXT } + rest.filter { it.skill == MemorySkill.CONTEXT })
    }

    fun encode(): String = "v1|${if (review) 1 else 0}|" + targets.joinToString(";") {
        "${it.skill},${it.required},${it.passed},${it.attempts}"
    }

    companion object {
        fun decode(value: String): SkillPracticePlan? {
            if (value.isBlank()) return null
            val p = value.split('|'); require(p.size == 3 && p[0] == "v1" && p[1] in listOf("0", "1")) { "练习计划版本无效" }
            val targets = p[2].split(';').map { row ->
                val s = row.split(','); require(s.size == 4)
                SkillPracticeTarget(MemorySkill.valueOf(s[0]), s[1].toInt(), s[2].toInt(), s[3].toInt()).also { require(it.valid()) }
            }
            require(targets.isNotEmpty() && targets.map { it.skill }.distinct().size == targets.size)
            return SkillPracticePlan(targets, p[1] == "1")
        }

        private fun recall(state: SkillState, now: Long): Double =
            if (state.lastAt > 0) RetentionMath.recallProbability(
                state.stability,
                (now - state.lastAt).coerceAtLeast(0) / SkillMemory.DAY.toDouble(),
            ) else 0.0

        /** 词义还能想起来的程度。没有词义记录时当作不熟。 */
        private fun recognition(states: Map<MemorySkill, SkillState>, now: Long): Double =
            states[MemorySkill.MEANING]?.let { recall(it, now) } ?: 0.0

        private fun priority(skill: MemorySkill, state: SkillState, recognition: Double, now: Long): Double {
            val familiarity = when (skill) {
                MemorySkill.MEANING -> (1.0 - recognition) * FAMILIARITY_WEIGHT
                MemorySkill.SPELLING, MemorySkill.CONTEXT -> recognition * FAMILIARITY_WEIGHT
                else -> 0.0
            }
            val r = recall(state, now)
            return 0.45 * (1.0 - r) + 0.3 * (state.difficulty - 1.0) / 9.0 +
                (if (state.outcome in setOf("LAPSE", "HINT")) 0.2 else 0.0) +
                (if (state.dueAt <= now) 0.15 else 0.0) +
                familiarity
        }

        fun create(states: Map<MemorySkill, SkillState>, review: Boolean, now: Long): SkillPracticePlan {
            val known = recognition(states, now)
            val ranked = states.entries.sortedWith(compareByDescending<Map.Entry<MemorySkill, SkillState>> {
                priority(it.key, it.value, known, now)
            }.thenBy { it.key.ordinal })
            val selected = if (review) ranked.filter {
                it.value.dueAt <= now || it.value.outcome in setOf("LAPSE", "HINT", "UNKNOWN")
            }.ifEmpty { ranked.take(1) } else ranked
            return SkillPracticePlan(selected.map { (skill, state) ->
                val repetitions = when {
                    state.outcome == "LAPSE" && state.difficulty >= 8 -> 3
                    state.outcome in setOf("LAPSE", "HINT") -> 2
                    !review && skill == MemorySkill.MEANING && known < FAMILIAR_ENOUGH -> 2
                    !review && skill == MemorySkill.LISTENING -> 2
                    !review && skill in setOf(MemorySkill.SPELLING, MemorySkill.CONTEXT) && known >= FAMILIAR_ENOUGH -> 2
                    else -> 1
                }
                SkillPracticeTarget(skill, repetitions)
            }, review)
        }

        private const val FAMILIARITY_WEIGHT = 0.40
        private const val FAMILIAR_ENOUGH = 0.5
    }
}
