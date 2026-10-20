package com.alanbeidanci.app.data

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

internal data class LocalCalendarDay(val date: LocalDate, val zoneId: String)

/** Re-evaluate on a calendar/zone change even when Room and settings have not emitted. */
internal fun localCalendarChanges(): Flow<LocalCalendarDay> = flow {
    while (true) {
        val zone = ZoneId.systemDefault()
        emit(LocalCalendarDay(LocalDate.now(zone), zone.id))
        delay(1000)
    }
}.distinctUntilChanged()
