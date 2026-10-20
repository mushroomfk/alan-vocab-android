package com.alanbeidanci.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class MainlineStep {
    REVIEW,
    REMEDY_TEST,
    CONSOLIDATION_TEST,
    NEW_WORD_TEST,
    LEARN,
    DONE,
}

enum class MainlineTestKind { REMEDY, CONSOLIDATION, NEW_WORDS }

enum class TypingPurpose { MAINLINE_LEARN, MAINLINE_REVIEW, AUTONOMOUS_REVIEW, MAINLINE_CONSOLIDATE, RELEARN }

/** 最近学习花掉的时间。记录不够时用默认值：新词 4 分钟，到期词 1 分钟，旧错词 2 分钟。 */
data class StudyPace(
    val millisPerNewWord: Long = 4 * 60_000L,
    val millisPerDueWord: Long = 60_000L,
    val millisPerReviewWord: Long = 2 * 60_000L,
)

enum class ReviewOutcome { CLEAN, SHAKY, FAILED }

/** 长期间隔只看巩固时的第一次独立回忆。练习通过和跳过不在这里加稳定度。 */
enum class RetentionGrade { GOOD, LAPSE, RECHECK, PRACTICE }

data class MainlinePlan(
    val step: MainlineStep = MainlineStep.LEARN,
    val reviewWords: List<WordEntity> = emptyList(),
    val remedyTestWords: List<WordEntity> = emptyList(),
    val consolidationWords: List<WordEntity> = emptyList(),
    val pendingTestWords: List<WordEntity> = emptyList(),
    val learnWords: List<WordEntity> = emptyList(),
    val deferredRemedyCount: Int = 0,
    val learnedToday: Int = 0,
    val touchedToday: Int = 0,
    val masteredCount: Int = 0,
    val fixedCount: Int = 0,
    val totalWords: Int = 0,
    val learnedWords: Int = 0,
    val reviewDoneToday: Int = 0,
    val remedyDoneToday: Int = 0,
    val consolidationDoneToday: Int = 0,
    val newWordTestDoneToday: Int = 0,
    val newWordCap: Int = 0,
    val extraNewWordCount: Int = 0,
) {
    val currentWords: List<WordEntity>
        get() = when (step) {
            MainlineStep.REVIEW -> reviewWords
            MainlineStep.REMEDY_TEST -> remedyTestWords
            MainlineStep.CONSOLIDATION_TEST -> consolidationWords
            MainlineStep.NEW_WORD_TEST -> pendingTestWords
            MainlineStep.LEARN -> learnWords
            MainlineStep.DONE -> emptyList()
        }
}

object MainlineScheduler {
    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE
    private val intervals = longArrayOf(10 * MINUTE, DAY, 3 * DAY, 7 * DAY, 15 * DAY, 30 * DAY)
    // 旧的固定间隔仍留给没走过新巩固流程的双测。新巩固看第一次独立回忆。
    private val masteryReviewGaps = longArrayOf(0, 3 * DAY, 4 * DAY, 8 * DAY, 15 * DAY)

    /**
     * 第一次独立写对才增加稳定度，一次只走一步，不连跳两档。
     * 第一次没写对就是遗忘：稳定度按失败下降，下次检查固定在明天，不再只降一档排到 15 天后。
     * 听过或看过答案后写对，用 [RetentionGrade.RECHECK]，稳定度不变。
     * 跳过不要调用这里。
     */
    fun applyRetention(
        current: WordProgressEntity,
        grade: RetentionGrade,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        meaning: Int = current.abilityMeaning,
        listening: Int = current.abilityListening,
        spelling: Int = current.abilitySpelling,
        context: Int = current.abilityContext,
        parameters: RetentionParameters = RetentionParameters(),
    ): WordProgressEntity {
        val seeded = seedMemory(current)
        val elapsedDays = when {
            seeded.stability <= 0.0 -> 0.0
            current.lastReviewedAt > 0L -> (now - current.lastReviewedAt).coerceAtLeast(0L) / DAY.toDouble()
            else -> seeded.stability
        }
        val updated = when (grade) {
            RetentionGrade.GOOD ->
                if (seeded.stability <= 0.0) RetentionMath.initialGood() else RetentionMath.reviewGood(seeded, elapsedDays, parameters)
            RetentionGrade.LAPSE ->
                if (seeded.stability <= 0.0) RetentionMath.initialAgain() else RetentionMath.reviewAgain(seeded, elapsedDays, parameters)
            RetentionGrade.RECHECK, RetentionGrade.PRACTICE -> seeded
        }
        val days = when (grade) {
            RetentionGrade.GOOD -> RetentionMath.intervalDays(updated.stability)
            RetentionGrade.LAPSE, RetentionGrade.RECHECK -> 1
            RetentionGrade.PRACTICE -> 1
        }
        val fixed = grade == RetentionGrade.GOOD && days >= 30
        val category = when (grade) {
            RetentionGrade.LAPSE -> "relearn"
            RetentionGrade.GOOD -> if (fixed) "fixed" else "mastered"
            else -> current.category
        }
        return current.copy(
            stabilityDays = updated.stability,
            difficulty = updated.difficulty,
            masteryStreak = when (grade) {
                RetentionGrade.GOOD -> streakForInterval(days)
                RetentionGrade.LAPSE -> 1
                else -> current.masteryStreak
            },
            category = category,
            isMastered = if (grade == RetentionGrade.LAPSE) false else if (grade == RetentionGrade.GOOD) true else current.isMastered,
            dueAt = dueAfterDays(now, days, zone),
            lastReviewedAt = now,
            updatedAt = now,
            wrongCount = current.wrongCount + if (grade == RetentionGrade.LAPSE) 1 else 0,
            attempts = current.attempts + if (grade == RetentionGrade.PRACTICE) 0 else 1,
            correctCount = current.correctCount + if (grade == RetentionGrade.GOOD) 1 else 0,
            fixedAt = if (fixed && current.fixedAt == 0L) now else current.fixedAt,
            mainlineConsolidationDate = dateKey(now),
            practiceForm = "",
            practiceHits = 0,
            practiceMisses = 0,
            practiceHinted = false,
            firstResult = "",
            abilityMeaning = meaning,
            abilityListening = listening,
            abilitySpelling = spelling,
            abilityContext = context,
        )
    }

    /** 还没存过稳定度的旧进度，用原来的档位当作起点，不改已经排好的到期日。 */
    fun seedMemory(progress: WordProgressEntity): RetentionMath.Memory {
        val stability = when {
            progress.stabilityDays > 0.0 -> progress.stabilityDays
            else -> when (progress.masteryStreak.coerceIn(0, 5)) {
                1 -> 1.0
                2 -> 3.0
                3 -> 7.0
                4 -> 15.0
                5 -> 30.0
                else -> 0.0
            }
        }
        val difficulty = if (progress.difficulty > 0.0) progress.difficulty else RetentionMath.initialGood().difficulty
        return RetentionMath.Memory(stability, difficulty)
    }

    private fun streakForInterval(days: Int): Int = when {
        days >= 30 -> 5
        days >= 15 -> 4
        days >= 7 -> 3
        days >= 3 -> 2
        else -> 1
    }

    /**
     * 新词上限同时受每日目标和剩余时间约束。
     * 先扣到期词和旧错词的用时，剩下的再除以新词实际用时。
     */
    fun newWordCap(
        dailyGoal: Int,
        dailyMinutes: Int,
        dueCount: Int,
        reviewCount: Int,
        pace: StudyPace = StudyPace(),
    ): Int {
        val budgetMs = dailyMinutes.coerceIn(5, 180) * 60_000L
        val reserved = dueCount * pace.millisPerDueWord + reviewCount * pace.millisPerReviewWord
        val remaining = (budgetMs - reserved).coerceAtLeast(0)
        val perNew = pace.millisPerNewWord.coerceIn(30_000L, 20 * 60_000L)
        val byTime = (remaining / perNew).toInt()
        return minOf(dailyGoal.coerceIn(1, 100), byTime)
    }

    /**
     * 设定时长只决定今天先排多少。超时后的下一批按同样的固定时长再排，不再扣到期词。
     * 已经学过或这次会话里已经出现的词不重复进入。
     */
    fun extraLearnWords(
        words: List<WordEntity>,
        progressItems: List<WordProgressEntity>,
        settings: UserSettings,
        excludeIds: Set<Long> = emptySet(),
        now: Long = System.currentTimeMillis(),
        pace: StudyPace = StudyPace(),
    ): List<WordEntity> {
        val progress = progressItems.associateBy { it.wordId }
        val today = dateKey(now)
        val batch = newWordCap(settings.dailyGoal, settings.dailyMinutes, 0, 0, pace).coerceAtLeast(1)
        return scopedWords(words, settings).filter { word ->
            val item = progress[word.id]
            word.id !in excludeIds && (item?.attempts ?: 0) == 0 && item?.mainlineLearnedDate != today
        }.take(batch)
    }

    /** 近 14 天、至少 3 个完成词才替换默认用时，避免一次特别慢或特别快把计划带偏。 */
    fun paceFromSessions(sessions: List<StudySessionEntity>, now: Long = System.currentTimeMillis()): StudyPace {
        val cutoff = now - 14L * 24 * 60 * 60 * 1000
        val recent = sessions.filter { it.finishedAt >= cutoff && it.durationMs > 0 && it.completed > 0 }
        fun average(modes: Set<String>, fallback: Long): Long {
            val rows = recent.filter { it.mode in modes }
            val words = rows.sumOf { it.completed }
            if (words < 3) return fallback
            return (rows.sumOf { it.durationMs } / words).coerceIn(30_000L, 20 * 60_000L)
        }
        return StudyPace(
            millisPerNewWord = average(setOf("LEARN"), 4 * 60_000L),
            millisPerDueWord = average(setOf("CONSOLIDATE"), 60_000L),
            millisPerReviewWord = average(setOf("REVIEW"), 2 * 60_000L),
        )
    }

    fun daysUntil(now: Long, dueAt: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val due = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, due)
        return days.toInt().coerceAtLeast(0)
    }

    fun dueAfterDays(now: Long, days: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            .plusDays(days.coerceAtLeast(1).toLong())
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()

    /** 到期日按本地日历判断。dueAt 落在今天或更早，当天任何时刻都该出现。 */
    fun isDueOnOrBefore(dueAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (dueAt <= 0L) return false
        val dueDay = Instant.ofEpochMilli(dueAt).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return !dueDay.isAfter(today)
    }

    fun masteryDueAt(progress: WordProgressEntity): Long {
        if (progress.dueAt > 0) return progress.dueAt
        if (progress.category != "mastered") return progress.dueAt
        val streak = progress.masteryStreak.coerceIn(1, 4)
        val milestoneDueAt = progress.lastReviewedAt.takeIf { it > 0 }?.plus(masteryReviewGaps[streak]) ?: 0L
        return when {
            progress.dueAt > 0 && milestoneDueAt > 0 -> minOf(progress.dueAt, milestoneDueAt)
            progress.dueAt > 0 -> progress.dueAt
            else -> milestoneDueAt
        }
    }

    fun dateKey(now: Long = System.currentTimeMillis()): String =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    fun startOfToday(now: Long = System.currentTimeMillis()): Long =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** 某一本没有勾选单元时保留整本；只有这本书自己勾了单元才过滤。 */
    fun matchesUnitScope(word: WordEntity, selectedUnitKeys: Set<String>): Boolean {
        if (selectedUnitKeys.isEmpty()) return true
        val prefix = "${word.bookId}::"
        if (selectedUnitKeys.none { it.startsWith(prefix) }) return true
        return "$prefix${word.unitId}" in selectedUnitKeys
    }

    fun scopedWords(words: List<WordEntity>, settings: UserSettings): List<WordEntity> = words.filter { word ->
        word.bookId in settings.selectedBookIds && matchesUnitScope(word, settings.selectedUnitKeys)
    }

    /** 最近一次听写和中文都没过，今天再看一轮。累计错了多少次不再加轮数。 */
    fun reviewCycleCount(progress: WordProgressEntity?): Int =
        if (progress?.category == "error") 2 else 1

    /** 先完成所有词第 1 轮。最近一次两面都没过的，再来第 2 轮。 */
    fun expandReviewRounds(words: List<WordEntity>, progressItems: List<WordProgressEntity>): List<WordEntity> {
        val progress = progressItems.associateBy { it.wordId }
        return buildList {
            for (round in 1..2) {
                words.forEach { word ->
                    if (reviewCycleCount(progress[word.id]) >= round) add(word)
                }
            }
        }
    }

    fun plan(
        words: List<WordEntity>,
        progressItems: List<WordProgressEntity>,
        settings: UserSettings,
        touchedToday: Int = 0,
        now: Long = System.currentTimeMillis(),
        pace: StudyPace = StudyPace(),
    ): MainlinePlan {
        val scoped = scopedWords(words, settings)
        val progress = progressItems.associateBy { it.wordId }
        val today = dateKey(now)
        val byId = scoped.associateBy { it.id }

        val pendingRemedy = progressItems.filter {
            it.wordId in byId && it.mainlineClassifiedDate.isNotBlank() && it.category in setOf("error", "weak")
        }
        val eligibleRemedy = pendingRemedy.filter { it.mainlineClassifiedDate != today }
        val review = eligibleRemedy
            .filter { it.mainlineRemedyReviewDate != today }
            .sortedWith(compareByDescending<WordProgressEntity> { it.category == "error" }.thenByDescending { it.wrongCount })
            .mapNotNull { byId[it.wordId] }
        val remedyTest = eligibleRemedy
            .filter { it.mainlineRemedyReviewDate == today && it.mainlineRemedyTestDate != today }
            .sortedByDescending { it.wrongCount }
            .mapNotNull { byId[it.wordId] }
        val consolidation = progressItems
            .filter {
                // 到期的已牢记、已固定，以及上次没想起来的，都要回来。
                it.wordId in byId && it.category in setOf("mastered", "fixed", "relearn") &&
                    it.mainlineClassifiedDate.isNotBlank() &&
                    isDueOnOrBefore(masteryDueAt(it), now)
            }
            .sortedWith(compareBy<WordProgressEntity> { if (it.category == "relearn") 0 else 1 }.thenBy(::masteryDueAt))
            .mapNotNull { byId[it.wordId] }
        val pendingTest = progressItems
            .filter { it.wordId in byId && it.mainlineDualPending && it.mainlineDualDeferredDate != today }
            .sortedBy { it.lastReviewedAt }
            .mapNotNull { byId[it.wordId] }
        val learnedToday = progressItems.count { it.wordId in byId && it.mainlineLearnedDate == today }
        val todayNewWordCap = newWordCap(settings.dailyGoal, settings.dailyMinutes, consolidation.size, review.size, pace)
        val remainingGoal = (todayNewWordCap - learnedToday).coerceAtLeast(0)
        val unseen = scoped.filter { (progress[it.id]?.attempts ?: 0) == 0 }
        val learn = unseen.take(remainingGoal)
        val step = when {
            review.isNotEmpty() -> MainlineStep.REVIEW
            remedyTest.isNotEmpty() -> MainlineStep.REMEDY_TEST
            consolidation.isNotEmpty() -> MainlineStep.CONSOLIDATION_TEST
            learn.isNotEmpty() -> MainlineStep.LEARN
            pendingTest.isNotEmpty() -> MainlineStep.NEW_WORD_TEST
            else -> MainlineStep.DONE
        }

        return MainlinePlan(
            step = step,
            reviewWords = review,
            remedyTestWords = remedyTest,
            consolidationWords = consolidation,
            pendingTestWords = pendingTest,
            learnWords = learn,
            deferredRemedyCount = pendingRemedy.count {
                it.mainlineClassifiedDate == today || it.mainlineRemedyTestDate == today
            },
            learnedToday = learnedToday,
            touchedToday = touchedToday,
            masteredCount = progressItems.count { it.wordId in byId && it.category == "mastered" },
            fixedCount = progressItems.count { it.wordId in byId && it.category == "fixed" },
            totalWords = scoped.size,
            learnedWords = scoped.count { (progress[it.id]?.attempts ?: 0) > 0 },
            reviewDoneToday = progressItems.count { it.wordId in byId && it.mainlineRemedyReviewDate == today },
            remedyDoneToday = progressItems.count { it.wordId in byId && it.mainlineRemedyTestDate == today },
            consolidationDoneToday = progressItems.count {
                it.wordId in byId && it.mainlineConsolidationDate == today
            },
            newWordTestDoneToday = progressItems.count {
                it.wordId in byId &&
                    it.mainlineLearnedDate == today &&
                    it.mainlineClassifiedDate == today &&
                    !it.mainlineDualPending
            },
            newWordCap = todayNewWordCap,
            extraNewWordCount = (unseen.size - learn.size).coerceAtLeast(0),
        )
    }

    fun stepStatus(plan: MainlinePlan, row: MainlineStep): String {
        if (plan.step == row) return "正在做"
        val current = STEP_ORDER[plan.step] ?: 0
        val number = STEP_ORDER[row] ?: 0
        if (current < number) return "还没到"
        return if (stepFinishedToday(plan, row)) "做完了" else "今天不用做"
    }

    private fun stepFinishedToday(plan: MainlinePlan, row: MainlineStep): Boolean = when (row) {
        MainlineStep.REVIEW -> plan.reviewDoneToday > 0
        MainlineStep.REMEDY_TEST -> plan.remedyDoneToday > 0
        MainlineStep.CONSOLIDATION_TEST -> plan.consolidationDoneToday > 0
        MainlineStep.LEARN -> plan.learnedToday > 0
        MainlineStep.NEW_WORD_TEST -> plan.newWordTestDoneToday > 0
        MainlineStep.DONE -> false
    }

    private val STEP_ORDER = mapOf(
        MainlineStep.REVIEW to 1,
        MainlineStep.REMEDY_TEST to 2,
        MainlineStep.CONSOLIDATION_TEST to 3,
        MainlineStep.LEARN to 4,
        MainlineStep.NEW_WORD_TEST to 5,
        MainlineStep.DONE to 6,
    )

    fun nextProgress(
        current: WordProgressEntity,
        correct: Boolean,
        now: Long = System.currentTimeMillis(),
        countWrong: Boolean = true,
    ): WordProgressEntity {
        val nextStreak = if (correct) current.streak + 1 else 0
        val familiarity = if (correct) (current.familiarity + 1).coerceAtMost(5) else (current.familiarity - 1).coerceAtLeast(0)
        val category = if (!countWrong && !correct && current.isMastered) "learning" else current.category
        return current.copy(
            familiarity = familiarity,
            streak = nextStreak,
            wrongCount = current.wrongCount + if (!correct && countWrong) 1 else 0,
            attempts = current.attempts + 1,
            correctCount = current.correctCount + if (correct) 1 else 0,
            lastReviewedAt = now,
            dueAt = if (current.isMastered) masteryDueAt(current) else now + intervals[if (correct) familiarity else 0],
            category = category,
            isMastered = if (category == "learning") false else current.isMastered,
            updatedAt = now,
        )
    }

    fun classifyDual(
        progress: WordProgressEntity,
        listenPass: Boolean,
        chinesePass: Boolean,
        now: Long = System.currentTimeMillis(),
    ): WordProgressEntity {
        val both = listenPass && chinesePass
        val neither = !listenPass && !chinesePass
        val wasFixed = progress.category == "fixed"
        var masteryStreak = progress.masteryStreak
        val category = when (progress.category) {
            "fixed" -> if (both) "fixed" else if (neither) "error" else "weak"
            "mastered" -> if (both) {
                masteryStreak += 1
                if (masteryStreak >= 5) "fixed" else "mastered"
            } else if (neither) "error" else "weak"
            "weak", "error" -> if (both && progress.passStreak + 1 >= 2) {
                masteryStreak = 1
                "mastered"
            } else if (both) progress.category else if (neither) "error" else "weak"
            else -> if (both) {
                masteryStreak = 1
                "mastered"
            } else if (neither) "error" else "weak"
        }
        val isMastered = category == "mastered" || category == "fixed"
        val dueAt = when {
            category == "fixed" -> now + intervals[3]
            isMastered && progress.category == "mastered" && both ->
                (masteryDueAt(progress).takeIf { it > 0 } ?: now) + masteryReviewGaps[masteryStreak.coerceAtMost(4)]
            // 走过新词五步的词，次日（本地日历的下一天零点）再默写。没走过这套流程的仍从第 3 天开始。
            isMastered && progress.category == "learning" && progress.level >= 4 -> dueAfterDays(now, 1)
            isMastered -> now + masteryReviewGaps[1]
            // 听写或中文没过：明天再查，不用 10 分钟后的旧间隔，也不保留原来更远的到期日。
            else -> dueAfterDays(now, 1)
        }
        return progress.copy(
            category = category,
            masteryStreak = if (isMastered) masteryStreak else 0,
            passStreak = if (both) progress.passStreak + 1 else 0,
            lastListenPass = listenPass,
            lastChinesePass = chinesePass,
            lastReviewedAt = now,
            lastSpotCheckAt = if (category == "fixed") now else progress.lastSpotCheckAt,
            dueAt = dueAt,
            isMastered = isMastered,
            fixedAt = if (category == "fixed" && !wasFixed) now else progress.fixedAt,
            mainlineDualPending = false,
            updatedAt = now,
        )
    }

    fun normalizedPhonetic(word: WordEntity, accent: String): String {
        val raw = if (accent == "uk") word.phoneticUk else word.phoneticUs
        return raw.lowercase()
            .replace(Regex("[\\s/\\[\\]ˈˌ'’.:;-]"), "")
            .replace("ː", "")
    }

    fun hasEnglishHomophoneConflict(word: WordEntity, words: List<WordEntity>, accent: String): Boolean {
        val phonetic = normalizedPhonetic(word, accent)
        if (phonetic.isBlank()) return false
        return words.any {
            it.id != word.id && !it.word.equals(word.word, ignoreCase = true) && normalizedPhonetic(it, accent) == phonetic
        }
    }

    fun blankExample(example: String, spelling: String): String =
        ClozeQuestion.create(example, spelling)?.prompt.orEmpty()
}
