package com.alanbeidanci.app.data

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID

class RetentionPersonalization(private val db: AlanDatabase) {
    val profile = db.retentionProfileDao().observe()

    suspend fun setEnabled(enabled: Boolean) = db.withTransaction {
        val current = db.retentionProfileDao().get() ?: RetentionProfile()
        db.retentionProfileDao().save(current.copy(autoEnabled = enabled, generation = UUID.randomUUID().toString()))
    }

    /** Manual rollback pauses automatic fitting; it never rewrites any word's due date. */
    suspend fun restorePrevious() = db.withTransaction {
        val current = db.retentionProfileDao().get() ?: return@withTransaction
        if (current.revision == 0 || !current.isValid()) return@withTransaction
        db.retentionProfileDao().save(current.copy(
            gain = current.previousGain, lapse = current.previousLapse,
            previousGain = current.gain, previousLapse = current.lapse,
            autoEnabled = false, revision = current.revision + 1,
            generation = UUID.randomUUID().toString(), status = "MANUAL_ROLLBACK",
        ))
    }

    suspend fun useDefaults() = db.withTransaction {
        val current = db.retentionProfileDao().get() ?: RetentionProfile()
        db.retentionProfileDao().save(RetentionProfile(
            autoEnabled = false, revision = current.revision + 1,
            previousGain = current.parameters().takeIf { it.isValid() }?.gain ?: RetentionParameters().gain,
            previousLapse = current.parameters().takeIf { it.isValid() }?.lapse ?: RetentionParameters().lapse,
            generation = UUID.randomUUID().toString(), status = "MANUAL_DEFAULT",
        ))
    }

    suspend fun check(now: Long = System.currentTimeMillis()): Boolean {
        val (snapshot, logs) = db.withTransaction {
            val saved = db.retentionProfileDao().get() ?: RetentionProfile(generation = UUID.randomUUID().toString())
                .also { db.retentionProfileDao().save(it) }
            saved to if (saved.autoEnabled) db.reviewLogDao().trainingLogs(RetentionOptimizer.MAX_LOGS) else emptyList()
        }
        if (!snapshot.autoEnabled) return false
        val fitted = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            RetentionOptimizer.fit(logs, snapshot, now) { context.ensureActive() }
        }
        currentCoroutineContext().ensureActive()
        return db.withTransaction {
            // Settings changes, restore and another completed fit invalidate the captured profile.
            if (db.retentionProfileDao().get() != snapshot) return@withTransaction false
            db.retentionProfileDao().save(fitted)
            fitted.revision != snapshot.revision
        }
    }
}
