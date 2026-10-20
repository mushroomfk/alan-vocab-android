package com.alanbeidanci.app.ui

import com.alanbeidanci.app.data.RetentionGrade
import com.alanbeidanci.app.data.MemorySkill
import com.alanbeidanci.app.data.SkillMemory
import com.alanbeidanci.app.data.SkillPracticePlan
import com.alanbeidanci.app.data.WordProgressEntity

/**
 * 新词：先看，再凭中文默写，并分别确认词义、听写、有例句时的填空。
 * 无提示写对 [PRODUCTION_GOAL] 次，只算这次练习通过，不算长期记住。
 * 队列里没有别的词时，下一次产出题先停住，不要求马上再输入。
 * 巩固：先做一次没提示的回忆。第一次的结果决定下次隔多久；听过或看过答案后再写对，不能把这次遗忘盖掉。
 * 已经知道哪项不稳，就只加那一项，不要求每个词每次都做完全部题型。
 */
enum class PracticeForm {
    STUDY,
    PICK_MEANING,
    PICK_SPELLING,
    FADED,
    RECALL,
    LISTEN,
    CLOZE,
}

enum class PracticePhase { LEARN, REVIEW }

enum class FirstResult { NONE, CORRECT, WRONG, HINTED }

enum class PracticeMove { INTRODUCE, HARDER, AGAIN, EASIER }

enum class StopReason { FAILED, SKIP }

data class AbilityMarks(
    val meaning: Int = 0,
    val listening: Int = 0,
    val spelling: Int = 0,
    val context: Int = 0,
) {
    /** 答对且没提示记 2，答错记 1。带提示的答对不升档。选拼写和补字母的答对也不算会拼。 */
    fun record(form: PracticeForm, independent: Boolean, correct: Boolean): AbilityMarks {
        if (!correct) {
            return when (form) {
                PracticeForm.PICK_MEANING -> copy(meaning = 1)
                PracticeForm.LISTEN -> copy(listening = 1)
                PracticeForm.CLOZE -> copy(context = 1)
                PracticeForm.STUDY -> this
                else -> copy(spelling = 1)
            }
        }
        if (!independent) return this
        return when (form) {
            PracticeForm.PICK_MEANING -> copy(meaning = 2)
            PracticeForm.LISTEN -> copy(listening = 2)
            PracticeForm.CLOZE -> copy(context = 2)
            PracticeForm.RECALL -> copy(spelling = 2)
            else -> this
        }
    }
}

data class SavedPractice(
    val form: PracticeForm? = null,
    val hits: Int = 0,
    val misses: Int = 0,
    val hinted: Boolean = false,
    val firstResult: FirstResult = FirstResult.NONE,
    val abilities: AbilityMarks = AbilityMarks(),
    val hasSession: Boolean = false,
    val skillMemory: String = "",
    val plan: SkillPracticePlan? = null,
)

sealed class PracticeEffect {
    data class Show(
        val state: MemoryState,
        val gap: Int,
        val reveal: Boolean = false,
        val move: PracticeMove = PracticeMove.AGAIN,
    ) : PracticeEffect()

    data class Graduate(
        val shaky: Boolean,
        val grade: RetentionGrade,
        val abilities: AbilityMarks,
    ) : PracticeEffect()

    data class Park(val state: MemoryState, val reason: StopReason) : PracticeEffect() {
        val rung: Int get() = MemoryPractice.rungOf(state.form)
    }

    data class Defer(val state: MemoryState) : PracticeEffect()
}

data class MemoryState(
    val wordId: Long,
    val form: PracticeForm,
    val cleanRecalls: Int = 0,
    val misses: Int = 0,
    val studied: Boolean = false,
    val phase: PracticePhase = PracticePhase.LEARN,
    val hinted: Boolean = false,
    val firstResult: FirstResult = FirstResult.NONE,
    val abilities: AbilityMarks = AbilityMarks(),
    val hasExample: Boolean = true,
    val plan: SkillPracticePlan? = null,
    // Separate from explicit help: practice can pass after exposure, long-term evidence cannot.
    val exposed: Boolean = false,
)

data class PracticeQueue(
    val showing: MemoryState?,
    val pending: MutableList<MemoryState>,
)

object MemoryPractice {
    const val PRODUCTION_GOAL = 3
    const val MISS_CAP = 4
    const val PROMOTE_GAP = 2
    const val SUCCESS_GAP = 3
    const val FAIL_GAP = 1

    /** 没学完的词下次从这档开始。0 是全新词。 */
    fun rungOf(form: PracticeForm): Int = when (form) {
        PracticeForm.STUDY -> 0
        PracticeForm.PICK_MEANING -> 1
        PracticeForm.PICK_SPELLING -> 2
        PracticeForm.FADED -> 3
        PracticeForm.RECALL -> 4
        PracticeForm.LISTEN -> 5
        PracticeForm.CLOZE -> 6
    }

    fun formForRung(savedRung: Int): PracticeForm = when (savedRung) {
        1 -> PracticeForm.PICK_MEANING
        2 -> PracticeForm.PICK_SPELLING
        3 -> PracticeForm.FADED
        4 -> PracticeForm.RECALL
        5 -> PracticeForm.LISTEN
        6 -> PracticeForm.CLOZE
        else -> PracticeForm.STUDY
    }

    /** 没有例句时不能把听写当成填空。 */
    fun presentedForm(form: PracticeForm, hasExample: Boolean): PracticeForm =
        if (form == PracticeForm.CLOZE && !hasExample) PracticeForm.LISTEN else form

    fun opening(wordId: Long, savedRung: Int): MemoryState {
        val form = formForRung(savedRung)
        return MemoryState(wordId = wordId, form = form, studied = form != PracticeForm.STUDY)
    }

    fun begin(wordIds: List<Long>, savedRungs: Map<Long, Int> = emptyMap()): PracticeQueue {
        val saved = savedRungs.mapValues { (_, rung) ->
            if (rung <= 0) SavedPractice()
            else SavedPractice(form = formForRung(rung), hasSession = true)
        }
        return begin(wordIds, saved, PracticePhase.LEARN, emptyMap())
    }

    fun begin(
        wordIds: List<Long>,
        saved: Map<Long, SavedPractice>,
        phase: PracticePhase,
        examples: Map<Long, Boolean>,
    ): PracticeQueue {
        val pending = wordIds.mapTo(mutableListOf()) { id ->
            val item = saved[id] ?: SavedPractice()
            val hasExample = examples[id] ?: true
            val eligiblePlan = item.plan?.copy(targets = item.plan.targets.filter {
                it.skill != MemorySkill.CONTEXT || hasExample
            })?.takeIf { it.targets.isNotEmpty() && it.pending() != null }
            val plan = eligiblePlan ?: SkillPracticePlan.create(
                SkillMemory.states(WordProgressEntity(wordId = id, skillMemory = item.skillMemory,
                    abilityMeaning = item.abilities.meaning, abilityListening = item.abilities.listening,
                    abilitySpelling = item.abilities.spelling, abilityContext = item.abilities.context), hasExample),
                phase == PracticePhase.REVIEW, System.currentTimeMillis())
            val opened = if (item.hasSession) {
                restore(id, item, phase, hasExample)
            } else if (phase == PracticePhase.REVIEW) {
                freshReview(id, item.abilities, hasExample)
            } else {
                opening(id, 0).copy(hasExample = hasExample, abilities = normalize(item.abilities, hasExample))
            }
            opened.copy(plan = plan, form = if ((!item.hasSession && phase == PracticePhase.REVIEW) ||
                (item.form == PracticeForm.CLOZE && !hasExample))
                formForSkill(plan.pending()!!.skill) else opened.form)
        }
        val showing = if (pending.isEmpty()) null else pending.removeAt(0)
        return PracticeQueue(showing, pending)
    }

    fun afterStudy(state: MemoryState): PracticeEffect.Show {
        val probe = if (state.form == PracticeForm.STUDY) state.plan?.pending()?.skill?.let(::formForSkill) ?: PracticeForm.RECALL else state.form
        return PracticeEffect.Show(
            state = state.copy(form = probe, studied = true, hinted = false, exposed = true),
            gap = 0,
            move = PracticeMove.INTRODUCE,
        )
    }

    fun afterCorrect(state: MemoryState, queueSize: Int = Int.MAX_VALUE): PracticeEffect {
        if (state.plan != null) return plannedAnswer(state, true, queueSize)
        if (state.phase == PracticePhase.REVIEW) return afterReviewCorrect(state, queueSize)
        val independent = !state.hinted
        val abilities = state.abilities.record(state.form, independent, correct = true)
        val production = state.form >= PracticeForm.RECALL && independent
        val hits = state.cleanRecalls + if (production) 1 else 0
        val first = if (state.firstResult == FirstResult.NONE && state.hinted) FirstResult.HINTED else state.firstResult
        val next = state.copy(
            cleanRecalls = hits,
            abilities = normalize(abilities, state.hasExample),
            hinted = false,
            firstResult = first,
        )
        if (production && hits >= PRODUCTION_GOAL) {
            return PracticeEffect.Graduate(
                shaky = next.misses > 0 || next.firstResult == FirstResult.WRONG || next.firstResult == FirstResult.HINTED,
                grade = RetentionGrade.PRACTICE,
                abilities = next.abilities,
            )
        }
        val nextForm = nextLearnForm(next)
        val queued = next.copy(form = nextForm)
        if (queueSize == 0 && nextForm >= PracticeForm.RECALL) return PracticeEffect.Defer(queued)
        return PracticeEffect.Show(
            state = queued,
            gap = if (nextForm >= PracticeForm.RECALL) SUCCESS_GAP else PROMOTE_GAP,
            move = PracticeMove.HARDER,
        )
    }

    fun afterWrong(state: MemoryState, gaveUp: Boolean = false): PracticeEffect {
        if (state.plan != null) return plannedAnswer(state, false, Int.MAX_VALUE, gaveUp)
        val abilities = normalize(state.abilities.record(state.form, independent = true, correct = false), state.hasExample)
        if (state.phase == PracticePhase.REVIEW && state.firstResult == FirstResult.CORRECT) {
            return PracticeEffect.Graduate(shaky = false, grade = RetentionGrade.GOOD, abilities = abilities)
        }
        val first = if (state.firstResult == FirstResult.NONE) {
            if (state.hinted) FirstResult.HINTED else FirstResult.WRONG
        } else state.firstResult
        val misses = state.misses + 1
        val dropped = demote(state.form)
        val next = state.copy(form = dropped, misses = misses, firstResult = first, abilities = abilities, hinted = false)
        if (misses >= MISS_CAP) return PracticeEffect.Park(next, StopReason.FAILED)
        return PracticeEffect.Show(state = next, gap = FAIL_GAP, reveal = true, move = PracticeMove.EASIER)
    }

    fun afterSkip(state: MemoryState): PracticeEffect.Park = PracticeEffect.Park(state, StopReason.SKIP)

    fun place(queue: MutableList<MemoryState>, state: MemoryState, gap: Int) {
        queue.add(gap.coerceIn(0, queue.size), state)
    }

    fun popNext(queue: MutableList<MemoryState>): MemoryState? =
        if (queue.isEmpty()) null else queue.removeAt(0)

    fun letterHint(spelling: String): String {
        val letters = spelling.filter { it.isLetterOrDigit() }
        return when {
            letters.isEmpty() -> ""
            letters.length == 1 -> "·"
            letters.length == 2 -> "${letters.first()}·"
            else -> letters.first() + "·".repeat(letters.length - 2) + letters.last()
        }
    }

    fun formForSkill(skill: MemorySkill): PracticeForm = when (skill) {
        MemorySkill.MEANING -> PracticeForm.PICK_MEANING
        MemorySkill.LISTENING -> PracticeForm.LISTEN
        MemorySkill.SPELLING -> PracticeForm.RECALL
        MemorySkill.CONTEXT -> PracticeForm.CLOZE
    }

    private fun plannedAnswer(state: MemoryState, correct: Boolean, queueSize: Int, gaveUp: Boolean = false): PracticeEffect {
        val plan = requireNotNull(state.plan)
        val skill = MemorySkill.forForm(state.form.name) ?: return afterStudy(state)
        val canonical = state.form == formForSkill(skill)
        val independent = canonical && !state.hinted
        val updated = if (canonical) plan.record(skill, correct, independent) else plan
        val abilities = normalize(state.abilities.record(state.form, independent, correct), state.hasExample)
        val first = when {
            !correct && !state.hinted -> FirstResult.WRONG
            state.hinted && state.firstResult != FirstResult.WRONG -> FirstResult.HINTED
            state.phase == PracticePhase.REVIEW && state.exposed && state.firstResult == FirstResult.NONE -> FirstResult.HINTED
            state.firstResult == FirstResult.NONE -> FirstResult.CORRECT
            else -> state.firstResult
        }
        val next = state.copy(plan = updated, abilities = abilities, firstResult = first, hinted = false, exposed = true,
            misses = state.misses + if (correct) 0 else 1,
            cleanRecalls = state.cleanRecalls + if (correct && independent) 1 else 0)
        if (!correct) {
            val recognize = gaveUp || skill == MemorySkill.CONTEXT
            val retryPlan = if (recognize) updated.preferMeaningRecognition() else updated
            val retry = next.copy(
                plan = retryPlan,
                form = when {
                    recognize -> PracticeForm.PICK_MEANING
                    skill == MemorySkill.SPELLING -> PracticeForm.FADED
                    else -> formForSkill(skill)
                },
            )
            return if (retry.misses >= MISS_CAP) PracticeEffect.Park(retry, StopReason.FAILED)
                else PracticeEffect.Show(retry, FAIL_GAP, reveal = true, move = PracticeMove.EASIER)
        }
        val pending = updated.targets.firstOrNull { it.passed < it.required && it.skill != skill } ?: updated.pending()
        if (pending == null) {
            val grade = if (state.phase == PracticePhase.LEARN) RetentionGrade.PRACTICE else when (first) {
                FirstResult.WRONG -> RetentionGrade.LAPSE
                FirstResult.HINTED -> RetentionGrade.RECHECK
                else -> RetentionGrade.GOOD
            }
            return PracticeEffect.Graduate(first != FirstResult.CORRECT, grade, abilities)
        }
        val form = formForSkill(pending.skill)
        val queued = next.copy(form = form)
        if (form == state.form && queueSize == 0) return PracticeEffect.Defer(queued)
        return PracticeEffect.Show(queued, if (form == state.form) SUCCESS_GAP else PROMOTE_GAP,
            move = if (form == state.form) PracticeMove.AGAIN else PracticeMove.HARDER)
    }

    private fun afterReviewCorrect(state: MemoryState, queueSize: Int): PracticeEffect {
        if (state.hinted && state.firstResult == FirstResult.NONE) {
            return PracticeEffect.Graduate(shaky = true, grade = RetentionGrade.RECHECK, abilities = state.abilities)
        }
        if (state.firstResult == FirstResult.WRONG || state.firstResult == FirstResult.HINTED) {
            val abilities = state.abilities.record(state.form, independent = !state.hinted, correct = true)
            return PracticeEffect.Graduate(shaky = true, grade = RetentionGrade.LAPSE, abilities = abilities)
        }
        val abilities = state.abilities.record(state.form, independent = !state.hinted, correct = true)
        val graded = state.copy(firstResult = FirstResult.CORRECT, abilities = abilities, hinted = false)
        val probe = extraProbe(graded)
        if (probe == null) return PracticeEffect.Graduate(shaky = false, grade = RetentionGrade.GOOD, abilities = abilities)
        return PracticeEffect.Show(
            state = graded.copy(form = probe),
            gap = if (queueSize == 0) 0 else SUCCESS_GAP,
            move = PracticeMove.HARDER,
        )
    }

    private fun nextLearnForm(state: MemoryState): PracticeForm = when {
        state.abilities.spelling != 2 -> PracticeForm.RECALL
        state.abilities.meaning != 2 && state.abilities.meaning != -1 -> PracticeForm.PICK_MEANING
        state.abilities.listening != 2 -> PracticeForm.LISTEN
        state.hasExample && state.abilities.context != 2 && state.abilities.context != -1 -> PracticeForm.CLOZE
        else -> PracticeForm.RECALL
    }

    private fun extraProbe(state: MemoryState): PracticeForm? = when {
        state.abilities.listening == 1 -> PracticeForm.LISTEN
        state.hasExample && state.abilities.context == 1 -> PracticeForm.CLOZE
        state.abilities.meaning == 1 -> PracticeForm.PICK_MEANING
        else -> null
    }

    private fun freshReview(wordId: Long, abilities: AbilityMarks, hasExample: Boolean): MemoryState {
        val marks = normalize(abilities, hasExample)
        return MemoryState(
            wordId = wordId,
            form = PracticeForm.RECALL,
            phase = PracticePhase.REVIEW,
            studied = true,
            abilities = marks,
            hasExample = hasExample,
        )
    }

    private fun restore(wordId: Long, saved: SavedPractice, phase: PracticePhase, hasExample: Boolean): MemoryState {
        val form = presentedForm(saved.form ?: PracticeForm.RECALL, hasExample)
        return MemoryState(
            wordId = wordId,
            form = form,
            cleanRecalls = saved.hits,
            misses = saved.misses,
            studied = form != PracticeForm.STUDY,
            phase = phase,
            hinted = saved.hinted,
            firstResult = saved.firstResult,
            abilities = normalize(saved.abilities, hasExample),
            hasExample = hasExample,
        )
    }

    private fun normalize(abilities: AbilityMarks, hasExample: Boolean): AbilityMarks =
        if (hasExample || abilities.context != 0) abilities else abilities.copy(context = -1)

    private fun demote(form: PracticeForm): PracticeForm = when (form) {
        PracticeForm.CLOZE -> PracticeForm.LISTEN
        PracticeForm.LISTEN -> PracticeForm.RECALL
        PracticeForm.RECALL -> PracticeForm.FADED
        PracticeForm.FADED -> PracticeForm.PICK_SPELLING
        PracticeForm.PICK_SPELLING -> PracticeForm.PICK_MEANING
        PracticeForm.PICK_MEANING, PracticeForm.STUDY -> PracticeForm.PICK_MEANING
    }
}
