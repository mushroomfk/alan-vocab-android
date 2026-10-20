package com.alanbeidanci.app.data

object ReviewScheduler {
    private val intervals = longArrayOf(
        10 * 60 * 1000L,
        24 * 60 * 60 * 1000L,
        3 * 24 * 60 * 60 * 1000L,
        7 * 24 * 60 * 60 * 1000L,
        15 * 24 * 60 * 60 * 1000L,
        30 * 24 * 60 * 60 * 1000L,
    )

    fun next(
        current: WordProgressEntity?,
        correct: Boolean,
        now: Long = System.currentTimeMillis(),
    ): WordProgressEntity {
        val previous = current ?: error("A word id is required before scheduling")
        val nextStreak = if (correct) previous.streak + 1 else 0
        val nextFamiliarity = if (correct) {
            (previous.familiarity + 1).coerceAtMost(intervals.lastIndex)
        } else {
            (previous.familiarity - 1).coerceAtLeast(0)
        }
        val intervalIndex = if (correct) nextFamiliarity else 0
        return previous.copy(
            familiarity = nextFamiliarity,
            streak = nextStreak,
            wrongCount = previous.wrongCount + if (correct) 0 else 1,
            attempts = previous.attempts + 1,
            correctCount = previous.correctCount + if (correct) 1 else 0,
            lastReviewedAt = now,
            dueAt = now + intervals[intervalIndex],
            isMastered = nextFamiliarity >= intervals.lastIndex && nextStreak >= 3,
        )
    }
}

