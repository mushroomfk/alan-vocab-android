package com.alanbeidanci.app.data

/** A real blank and its explicit canonical answer; inflected/separable phrases need authored answers. */
data class ClozeQuestion(val prompt: String, val answer: String) {
    companion object {
        fun create(example: String, spelling: String): ClozeQuestion? {
            val answer = LearningContentPresentation.canonicalSpelling(spelling)
            if (example.isBlank() || answer.isBlank()) return null
            val pattern = Regex("(?i)\\b${Regex.escape(answer)}\\b")
            if (!pattern.containsMatchIn(example)) return null
            return ClozeQuestion(example.replace(pattern, "____"), answer)
        }
    }
}
