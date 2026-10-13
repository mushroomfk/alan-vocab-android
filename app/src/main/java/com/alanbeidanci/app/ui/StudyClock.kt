package com.alanbeidanci.app.ui

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class StudySlice(
    val startedAt: Long,
    val finishedAt: Long,
    val durationMs: Long,
)

/**
 * 学习时长只算前台里、距离上次操作不超过 [idleAfterMs] 的区间。
 * 退到后台、闲置或本轮结束就停下。跨过本地零点时按日期拆开，避免一整段都记到开始那天。
 */
class StudyClock(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val idleAfterMs: Long = IDLE_AFTER_MS,
) {
    private var running = false
    private var foreground = true
    private var held = false
    private var segmentOpen = false
    private var segmentStart = 0L
    private var lastInteraction = 0L
    private val totals = LinkedHashMap<LocalDate, Long>()

    fun start(now: Long) {
        running = true
        held = false
        foreground = true
        begin(now)
    }

    fun interact(now: Long) {
        if (!running || held || !foreground) return
        if (!segmentOpen) {
            begin(now)
            return
        }
        if (now >= idleCutoff()) {
            close(idleCutoff())
            begin(now)
        } else {
            lastInteraction = now
        }
    }

    fun tick(now: Long) {
        if (!segmentOpen) return
        if (now >= idleCutoff()) close(idleCutoff())
    }

    fun background(now: Long) {
        if (segmentOpen) close(minOf(now, idleCutoff()))
        foreground = false
    }

    fun foreground(now: Long) {
        foreground = true
        if (running && !held && !segmentOpen) begin(now)
    }

    /** 结果页不再计时，已经累计的时长留到 [finish]。 */
    fun hold(now: Long) {
        background(now)
        held = true
    }

    fun elapsed(now: Long): Long = totals.values.sum() + openDuration(now)

    fun finish(now: Long): List<StudySlice> {
        hold(now)
        running = false
        return totals.mapNotNull { (date, duration) ->
            if (duration < MIN_SLICE_MS) return@mapNotNull null
            val startedAt = date.atStartOfDay(zone).toInstant().toEpochMilli()
            StudySlice(startedAt, startedAt + duration, duration)
        }
    }

    private fun begin(now: Long) {
        segmentOpen = true
        segmentStart = now
        lastInteraction = now
    }

    private fun idleCutoff(): Long = lastInteraction + idleAfterMs

    private fun openDuration(now: Long): Long {
        if (!segmentOpen) return 0L
        val end = minOf(now, idleCutoff())
        return (end - segmentStart).coerceAtLeast(0L)
    }

    private fun close(end: Long) {
        if (!segmentOpen) return
        val start = segmentStart
        segmentOpen = false
        if (end <= start) return
        var cursor = start
        while (cursor < end) {
            val date = Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val next = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val sliceEnd = minOf(end, next)
            totals[date] = (totals[date] ?: 0L) + (sliceEnd - cursor)
            cursor = sliceEnd
        }
    }

    companion object {
        const val IDLE_AFTER_MS = 120_000L
        const val MIN_SLICE_MS = 1_000L
    }
}
