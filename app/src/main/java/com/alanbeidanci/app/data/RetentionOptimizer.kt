package com.alanbeidanci.app.data

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Small CPU-only optimizer for this app's two-rating FSRS-5 variant, not the upstream
 * full 19-parameter optimizer. Only w[8] and w[11] change. Predictions precede the
 * observed answer; held-out answers never take part in candidate selection.
 */
object RetentionOptimizer {
    const val DAY = 86_400_000L
    const val MIN_SAMPLES = 400
    const val MIN_WORDS = 40
    const val MIN_SPAN_DAYS = 30
    const val MIN_NEW_SAMPLES = 100
    const val CHECK_INTERVAL = 30 * DAY
    const val MAX_LOGS = 20_000

    data class Review(val time: Long, val elapsed: Double, val good: Boolean)
    data class Sequence(val wordId: Long, val seed: RetentionMath.Memory, val reviews: List<Review>)
    data class Prediction(val wordId: Long, val time: Long, val probability: Double, val good: Boolean) {
        val loss: Double get() {
            val p = probability.coerceIn(0.001, 0.999)
            return -ln(if (good) p else 1.0 - p)
        }
        val brier: Double get() = (probability - if (good) 1.0 else 0.0).let { it * it }
    }

    /** A broken chronology, hint, same-day repetition or legacy row breaks the chain. */
    fun sequences(logs: List<ReviewLogEntity>, now: Long): List<Sequence> {
        val result = mutableListOf<Sequence>()
        logs.groupBy { it.wordId }.forEach { (word, entries) ->
            var last: ReviewLogEntity? = null
            var seed = RetentionMath.Memory(1.0, 5.0)
            val reviews = mutableListOf<Review>()
            fun flush() {
                if (reviews.size >= 2) result += Sequence(word, seed, reviews.toList())
                reviews.clear()
            }
            entries.sortedWith(compareBy<ReviewLogEntity> { it.timestamp }.thenBy { it.id }).forEach { row ->
                val elapsed = (row.timestamp - row.memoryPreviousReviewAt) / DAY.toDouble()
                val valid = row.eventKind == "schedule" && row.memoryAlgorithm == RetentionParameters.ALGORITHM &&
                    row.memoryIndependent && row.timestamp in 1..now && row.memoryPreviousReviewAt > 0 &&
                    elapsed in 1.0..3650.0 && row.memoryBeforeStability.isFinite() &&
                    row.memoryBeforeStability in 0.1..36500.0 && row.memoryBeforeDifficulty.isFinite() &&
                    row.memoryBeforeDifficulty in 1.0..10.0 && row.scheduleGrade in setOf("GOOD", "LAPSE") &&
                    row.correct == (row.scheduleGrade == "GOOD")
                if (!valid) {
                    flush()
                    last = null
                } else {
                    if (last?.timestamp != row.memoryPreviousReviewAt) {
                        flush()
                        seed = RetentionMath.Memory(row.memoryBeforeStability, row.memoryBeforeDifficulty)
                    }
                    reviews += Review(row.timestamp, elapsed, row.correct)
                    last = row
                }
            }
            flush()
        }
        return result
    }

    fun predict(sequences: List<Sequence>, parameters: RetentionParameters): List<Prediction> = buildList {
        sequences.forEach { sequence ->
            var memory = sequence.seed
            sequence.reviews.forEachIndexed { index, review ->
                // The first snapshot is an initialization, not evidence for the new parameters.
                if (index > 0) add(Prediction(sequence.wordId, review.time,
                    RetentionMath.recallProbability(memory.stability, review.elapsed), review.good))
                memory = if (review.good) RetentionMath.reviewGood(memory, review.elapsed, parameters)
                    else RetentionMath.reviewAgain(memory, review.elapsed, parameters)
            }
        }
    }.sortedWith(compareBy<Prediction> { it.time }.thenBy { it.wordId })

    /** Paired differences grouped by word, so repeated observations aren't treated as independent words. */
    fun reliablyBetter(candidate: List<Prediction>, baseline: List<Prediction>): Boolean {
        if (candidate.size < 80 || candidate.size != baseline.size) return false
        if (candidate.indices.any { candidate[it].wordId != baseline[it].wordId || candidate[it].time != baseline[it].time }) return false
        if (candidate.any { !it.probability.isFinite() }) return false
        val changes = candidate.indices.groupBy { candidate[it].wordId }.values.map { indexes ->
            indexes.map { baseline[it].loss - candidate[it].loss }.average()
        }
        if (changes.size < 20) return false
        val mean = changes.average()
        val variance = changes.sumOf { (it - mean) * (it - mean) } / (changes.size - 1)
        val standardError = sqrt(variance / changes.size)
        val improvement = baseline.map { it.loss }.average() - candidate.map { it.loss }.average()
        return mean > 1.96 * standardError && improvement > max(0.002, baseline.map { it.loss }.average() * 0.02) &&
            candidate.map { it.brier }.average() <= baseline.map { it.brier }.average()
    }

    fun fit(logs: List<ReviewLogEntity>, profile: RetentionProfile, now: Long, checkCancelled: () -> Unit = {}): RetentionProfile {
        if (!profile.autoEnabled) return profile
        if (!profile.isValid()) return RetentionProfile(generation = profile.generation, status = "INVALID_RESET")
        val sequences = sequences(logs, now)
        val current = predict(sequences, profile.parameters())
        val counted = profile.copy(sampleCount = current.size)
        if (profile.lastAttemptAt > now || now - profile.lastAttemptAt < CHECK_INTERVAL) return counted
        if (current.size < MIN_SAMPLES || current.map { it.wordId }.distinct().size < MIN_WORDS ||
            current.last().time - current.first().time < MIN_SPAN_DAYS * DAY ||
            current.count { it.time > profile.lastAttemptThrough } < MIN_NEW_SAMPLES) return counted
        val split = current[(current.size * 0.75).toInt() - 1].time
        val training = current.filter { it.time <= split }
        val validation = current.filter { it.time > split }
        fun balanced(rows: List<Prediction>) = rows.count { it.good } >= 20 && rows.count { !it.good } >= 20
        if (!balanced(training) || !balanced(validation) || validation.size < 80 ||
            validation.first().time <= profile.lastAttemptThrough || validation.map { it.wordId }.distinct().size < 20) return counted
        val attempted = counted.copy(lastAttemptAt = now, lastAttemptThrough = current.last().time)

        // Re-evaluate an installed model only on observations made after installation.
        if (profile.revision > 0 && profile.activatedAt > 0) {
            val live = current.filter { it.time > profile.activatedAt }
            if (balanced(live) && live.size >= MIN_NEW_SAMPLES) {
                val previous = predict(sequences, profile.previousParameters()).filter { it.time > profile.activatedAt }
                if (reliablyBetter(previous, live)) return attempted.copy(
                    gain = profile.previousGain, lapse = profile.previousLapse,
                    previousGain = profile.gain, previousLapse = profile.lapse,
                    revision = profile.revision + 1, activatedAt = now, status = "ROLLED_BACK",
                    candidateLoss = previous.map { it.loss }.average(), baselineLoss = live.map { it.loss }.average(),
                )
            }
        }
        var best = profile.parameters()
        fun objective(parameters: RetentionParameters): Double {
            val loss = predict(sequences, parameters).filter { it.time <= split }.map { it.loss }.average()
            // Mild regularization limits needless movement away from well-tested defaults.
            return loss + 0.002 * (abs(parameters.gain - 1.54575) + abs(parameters.lapse - 1.9395))
        }
        var bestLoss = objective(best)
        val candidates = buildList {
            add(RetentionParameters())
            for (gain in listOf(0.9, 1.1, 1.3, 1.54575, 1.7, 1.9, 2.2)) {
                for (lapse in listOf(1.0, 1.3, 1.6, 1.9395, 2.3, 2.6, 3.0)) add(RetentionParameters(gain, lapse))
            }
        }
        candidates.forEach { candidate ->
            checkCancelled()
            val loss = objective(candidate)
            if (loss.isFinite() && loss < bestLoss) { best = candidate; bestLoss = loss }
        }
        val proposed = predict(sequences, best).filter { it.time > split }
        val defaults = predict(sequences, RetentionParameters()).filter { it.time > split }
        val accepted = best != profile.parameters() && reliablyBetter(proposed, validation) &&
            (best == RetentionParameters() || reliablyBetter(proposed, defaults))
        return attempted.copy(
            gain = if (accepted) best.gain else profile.gain,
            lapse = if (accepted) best.lapse else profile.lapse,
            previousGain = if (accepted) profile.gain else profile.previousGain,
            previousLapse = if (accepted) profile.lapse else profile.previousLapse,
            revision = profile.revision + if (accepted) 1 else 0,
            activatedAt = if (accepted) now else profile.activatedAt,
            trainedThrough = if (accepted) split else profile.trainedThrough,
            status = if (accepted) "PERSONALIZED" else "NO_IMPROVEMENT",
            candidateLoss = proposed.map { it.loss }.average(), baselineLoss = validation.map { it.loss }.average(),
        )
    }
}
