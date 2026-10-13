package com.alanbeidanci.app.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.alanbeidanci.app.AlanVocabApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class RetentionTrainingWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val app = applicationContext as AlanVocabApplication
        app.container.retentionPersonalization.check()
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // No replacement parameters are published when the attempt fails.
        if (runAttemptCount < 2) Result.retry() else Result.failure()
    }
}

object RetentionTrainingScheduler {
    const val WORK_NAME = "alan_retention_personalization"

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<RetentionTrainingWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
