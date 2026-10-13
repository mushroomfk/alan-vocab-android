package com.alanbeidanci.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StudyClockTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun idleAndBackgroundAreNotCounted() {
        val clock = StudyClock(zone, idleAfterMs = 120_000)
        clock.start(0)
        clock.tick(130_000)
        assertEquals(120_000, clock.elapsed(130_000))
        clock.interact(200_000)
        clock.background(210_000)
        clock.tick(500_000)
        assertEquals(130_000, clock.elapsed(500_000))
    }

    @Test
    fun activeTimeSplitsAcrossLocalMidnight() {
        val clock = StudyClock(zone, idleAfterMs = 3 * 60 * 60 * 1000L)
        val start = LocalDate.of(2026, 10, 12).atTime(23, 50).atZone(zone).toInstant().toEpochMilli()
        val end = LocalDate.of(2026, 10, 13).atTime(0, 10).atZone(zone).toInstant().toEpochMilli()
        clock.start(start)
        clock.interact(end)
        val slices = clock.finish(end)
        assertEquals(2, slices.size)
        assertEquals(10 * 60 * 1000L, slices[0].durationMs)
        assertEquals(10 * 60 * 1000L, slices[1].durationMs)
        assertTrue(slices[0].startedAt < end)
        assertEquals(
            LocalDate.of(2026, 10, 13).atStartOfDay(zone).toInstant().toEpochMilli(),
            slices[1].startedAt,
        )
    }
}
