package com.alanbeidanci.app.ui

data class LetterInputDecision(
    val text: String,
    val accepted: Boolean,
    val rejected: Boolean,
    val mistake: Boolean,
)

object LetterInput {
    fun letters(value: String): String = value.filter { it.isLetterOrDigit() }

    /**
     * 手机键盘很容易点错。学习时保留已输入内容，用退格修改；
     * 测验只在写满整个词后判断，避免输入到一半被清空，也避免和输入法抢字。
     */
    fun apply(
        previous: String,
        incoming: String,
        target: String,
        judgeWhenComplete: Boolean,
    ): LetterInputDecision {
        val text = letters(incoming)
        if (target.isEmpty()) return LetterInputDecision(text, accepted = false, rejected = false, mistake = false)
        val matched = text.equals(target, ignoreCase = true)
        if (!judgeWhenComplete) {
            return LetterInputDecision(
                text = text,
                accepted = matched,
                rejected = false,
                mistake = text.length > previous.length && !isPrefix(text, target),
            )
        }
        if (text.length < target.length) {
            return LetterInputDecision(text, accepted = false, rejected = false, mistake = false)
        }
        return LetterInputDecision(
            text = if (matched) text else "",
            accepted = matched,
            rejected = !matched,
            mistake = !matched,
        )
    }

    fun isPrefix(input: String, target: String): Boolean {
        if (input.length > target.length) return false
        for (index in input.indices) {
            if (!input[index].equals(target[index], ignoreCase = true)) return false
        }
        return true
    }

    fun cellWidth(availablePx: Float, letterCount: Int, gapPx: Float, minPx: Float, maxPx: Float): Float {
        val count = letterCount.coerceAtLeast(1)
        val raw = (availablePx - gapPx * (count - 1)) / count
        return raw.coerceIn(minPx, maxPx)
    }

    fun rowOverflows(availablePx: Float, letterCount: Int, gapPx: Float, minPx: Float, maxPx: Float): Boolean {
        val count = letterCount.coerceAtLeast(1)
        val width = cellWidth(availablePx, count, gapPx, minPx, maxPx)
        return width * count + gapPx * (count - 1) > availablePx + 0.5f
    }
}
