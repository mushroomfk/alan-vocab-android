package com.alanbeidanci.app.data

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 本地 FSRS-5 默认参数，用来估计稳定度和难度。
 * 期望记住率 0.9 时，间隔天数约等于稳定度。这是模型估计，不是保证记住。
 * 个人参数只校准长期增长 w[8] 和遗忘恢复 w[11]；其余保持默认，版本固定。
 * 同一会话里后来写对，不在这里加稳定度。
 */
object RetentionMath {
    private val w = doubleArrayOf(
        0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575, 0.1192, 1.01925,
        1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621,
    )
    private const val DECAY = -0.5
    private const val FACTOR = 19.0 / 81.0
    private const val AGAIN = 1
    private const val GOOD = 3

    data class Memory(val stability: Double, val difficulty: Double)

    fun initialGood(): Memory = initial(GOOD)

    fun initialAgain(): Memory = initial(AGAIN)

    fun reviewGood(memory: Memory, elapsedDays: Double, parameters: RetentionParameters = RetentionParameters()): Memory =
        review(memory, GOOD, elapsedDays, parameters)

    fun reviewAgain(memory: Memory, elapsedDays: Double, parameters: RetentionParameters = RetentionParameters()): Memory =
        review(memory, AGAIN, elapsedDays, parameters)

    /** 0.9 记住率下四舍五入后的间隔。至少 1 天。 */
    fun intervalDays(stability: Double): Int {
        if (stability <= 0.0) return 1
        val days = stability / FACTOR * (0.9.pow(1.0 / DECAY) - 1.0)
        return days.roundToInt().coerceAtLeast(1)
    }

    private fun initial(rating: Int): Memory = Memory(
        stability = w[rating - 1].coerceAtLeast(0.1),
        difficulty = initialDifficulty(rating),
    )

    fun recallProbability(stability: Double, elapsedDays: Double): Double =
        (1.0 + FACTOR * elapsedDays.coerceAtLeast(0.0) / stability.coerceAtLeast(0.1)).pow(DECAY)

    private fun review(memory: Memory, rating: Int, elapsedDays: Double, parameters: RetentionParameters): Memory {
        val personal = parameters.takeIf { it.isValid() } ?: RetentionParameters()
        val stability = memory.stability.coerceAtLeast(0.1)
        val difficulty = if (memory.difficulty <= 0.0) initialDifficulty(GOOD) else memory.difficulty
        val recall = (1.0 + FACTOR * elapsedDays.coerceAtLeast(0.0) / stability).pow(DECAY)
        val nextStability = if (rating == AGAIN) {
            personal.lapse * difficulty.pow(-w[12]) * ((stability + 1.0).pow(w[13]) - 1.0) * exp(w[14] * (1.0 - recall))
        } else {
            val gain = exp(personal.gain) * (11.0 - difficulty) * stability.pow(-w[9]) * (exp(w[10] * (1.0 - recall)) - 1.0)
            stability * (gain + 1.0)
        }
        return Memory(
            stability = nextStability.coerceAtLeast(0.1),
            difficulty = nextDifficulty(difficulty, rating),
        )
    }

    private fun initialDifficulty(rating: Int): Double =
        (w[4] - exp(w[5] * (rating - 1)) + 1.0).coerceIn(1.0, 10.0)

    private fun nextDifficulty(difficulty: Double, rating: Int): Double {
        val delta = -w[6] * (rating - 3)
        val stepped = difficulty + delta * (10.0 - difficulty) / 9.0
        val reverted = w[7] * initialDifficulty(4) + (1.0 - w[7]) * stepped
        return reverted.coerceIn(1.0, 10.0)
    }
}
