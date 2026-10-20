package com.alanbeidanci.app

import android.app.Application
import com.alanbeidanci.app.audio.BundledEnglishSpeaker
import com.alanbeidanci.app.data.AlanDatabase
import com.alanbeidanci.app.data.RetentionPersonalization
import com.alanbeidanci.app.notifications.RetentionTrainingScheduler
import com.alanbeidanci.app.data.BackupManager
import com.alanbeidanci.app.data.LearningRepository
import com.alanbeidanci.app.data.PreferencesStore
import com.alanbeidanci.app.notifications.ReminderScheduler
import com.alanbeidanci.app.network.AlanApiClient
import com.alanbeidanci.app.network.SecureSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AppContainer(application: Application) {
    val applicationContext = application.applicationContext
    val database = AlanDatabase.create(application)
    val preferences = PreferencesStore(application)
    val sessionStore = SecureSessionStore(application)
    val api = AlanApiClient(sessionStore)
    val repository = LearningRepository(database, applicationContext, api, preferences)
    val retentionPersonalization = RetentionPersonalization(database)
    val backupManager = BackupManager(repository, preferences)
}

class AlanVocabApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        BundledEnglishSpeaker.warmUp(this)
        ReminderScheduler.createChannel(this)
        RetentionTrainingScheduler.schedule(this)
        applicationScope.launch {
            container.sessionStore.ensureLocalSession()
            container.repository.prepareForUser(1L)
            container.repository.ensureSeeded()
            val settings = container.preferences.settings.first()
            ReminderScheduler.schedule(this@AlanVocabApplication, settings.reminderEnabled, settings.reminderHour)
        }
    }
}
