package com.alanbeidanci.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray

private val Context.alanDataStore by preferencesDataStore(name = "alan_settings")

data class UserSettings(
    val currentBookId: String = "demo-basics",
    val selectedBookIds: Set<String> = setOf("demo-basics"),
    val selectedUnitKeys: Set<String> = emptySet(),
    val dailyGoal: Int = 20,
    val dailyMinutes: Int = 20,
    val autoSpeak: Boolean = true,
    val shuffle: Boolean = false,
    val darkMode: Boolean = false,
    val accent: String = "us",
    val studySpeechRate: Float = 0.95f,
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = 20,
    val notificationPermissionRequested: Boolean = false,
    val onboardingComplete: Boolean = false,
    val compactHome: Boolean = false,
)

class PreferencesStore(private val context: Context) {
    private val writeMeta = context.getSharedPreferences("alan_sync_meta", Context.MODE_PRIVATE)

    private object Keys {
        val currentBookId = stringPreferencesKey("current_book_id")
        val selectedBookIds = stringSetPreferencesKey("selected_book_ids")
        val selectedUnitKeys = stringSetPreferencesKey("selected_unit_keys")
        val dailyGoal = intPreferencesKey("daily_goal")
        val dailyMinutes = intPreferencesKey("daily_minutes")
        val autoSpeak = booleanPreferencesKey("auto_speak")
        val shuffle = booleanPreferencesKey("shuffle")
        val darkMode = booleanPreferencesKey("dark_mode")
        val accent = stringPreferencesKey("accent")
        val studySpeechRate = stringPreferencesKey("study_speech_rate")
        val reminderEnabled = booleanPreferencesKey("reminder_enabled")
        val reminderHour = intPreferencesKey("reminder_hour")
        val notificationPermissionRequested = booleanPreferencesKey("notification_permission_requested")
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val compactHome = booleanPreferencesKey("compact_home")
        val bookSelectionCleared = booleanPreferencesKey("book_selection_cleared")
    }

    val settings: Flow<UserSettings> = context.alanDataStore.data.map { prefs ->
        val storedCurrent = prefs[Keys.currentBookId]
        val currentBookId = storedCurrent?.takeIf { it.isNotBlank() } ?: "demo-basics"
        val storedBooks = prefs[Keys.selectedBookIds].orEmpty().filter { it.isNotBlank() }.toSet()
        val selectedBookIds = when {
            prefs[Keys.bookSelectionCleared] == true -> emptySet()
            storedBooks.isNotEmpty() -> storedBooks
            storedCurrent == "" -> emptySet()
            else -> setOf(currentBookId)
        }
        UserSettings(
            currentBookId = currentBookId,
            selectedBookIds = selectedBookIds,
            selectedUnitKeys = prefs[Keys.selectedUnitKeys].orEmpty(),
            dailyGoal = prefs[Keys.dailyGoal] ?: 20,
            dailyMinutes = prefs[Keys.dailyMinutes]?.coerceIn(5, 180) ?: 20,
            autoSpeak = prefs[Keys.autoSpeak] ?: true,
            shuffle = prefs[Keys.shuffle] ?: false,
            darkMode = prefs[Keys.darkMode] ?: false,
            accent = prefs[Keys.accent] ?: "us",
            studySpeechRate = prefs[Keys.studySpeechRate]?.toFloatOrNull()?.coerceIn(0.5f, 2f) ?: 0.95f,
            reminderEnabled = prefs[Keys.reminderEnabled] ?: true,
            reminderHour = prefs[Keys.reminderHour] ?: 20,
            notificationPermissionRequested = prefs[Keys.notificationPermissionRequested] ?: false,
            onboardingComplete = prefs[Keys.onboardingComplete] ?: false,
            compactHome = prefs[Keys.compactHome] ?: false,
        )
    }

    suspend fun setCurrentBook(id: String) {
        if (id.isBlank()) return
        context.alanDataStore.edit { prefs ->
            prefs[Keys.currentBookId] = id
            prefs[Keys.selectedBookIds] = setOf(id)
            prefs[Keys.selectedUnitKeys] = emptySet()
            prefs[Keys.bookSelectionCleared] = false
        }
        markWrites("currentBookId", "selectedBookIds", "selectedUnitKeys")
    }

    suspend fun toggleBookSelection(id: String) {
        if (id.isBlank()) return
        context.alanDataStore.edit { prefs ->
            val cleared = prefs[Keys.bookSelectionCleared] == true
            val currentId = prefs[Keys.currentBookId]?.takeIf { it.isNotBlank() } ?: "demo-basics"
            val selected = when {
                cleared -> mutableSetOf()
                else -> (prefs[Keys.selectedBookIds] ?: setOf(currentId)).filter { it.isNotBlank() }.toMutableSet()
            }
            if (id in selected) selected.remove(id) else selected.add(id)
            if (selected.isEmpty()) {
                prefs.remove(Keys.selectedBookIds)
                prefs[Keys.bookSelectionCleared] = true
                prefs[Keys.selectedUnitKeys] = emptySet()
                if (currentId.isNotBlank()) prefs[Keys.currentBookId] = currentId
            } else {
                prefs[Keys.bookSelectionCleared] = false
                prefs[Keys.selectedBookIds] = selected
                prefs[Keys.currentBookId] = if (currentId in selected) currentId else selected.min()
                prefs[Keys.selectedUnitKeys] = prefs[Keys.selectedUnitKeys].orEmpty()
                    .filter { key -> selected.any { key.startsWith("$it::") } }
                    .toSet()
            }
        }
        markWrites("currentBookId", "selectedBookIds", "selectedUnitKeys")
    }

    suspend fun toggleUnitSelection(key: String) {
        context.alanDataStore.edit { prefs ->
            val selected = prefs[Keys.selectedUnitKeys].orEmpty().toMutableSet()
            if (!selected.add(key)) selected.remove(key)
            prefs[Keys.selectedUnitKeys] = selected
        }
        markWrite("selectedUnitKeys")
    }

    suspend fun toggleUnitsSelection(keys: Set<String>) {
        context.alanDataStore.edit { prefs ->
            val selected = prefs[Keys.selectedUnitKeys].orEmpty().toMutableSet()
            if (keys.all { it in selected }) selected.removeAll(keys)
            else selected.addAll(keys)
            prefs[Keys.selectedUnitKeys] = selected
        }
        markWrite("selectedUnitKeys")
    }

    suspend fun setSelectedBooks(ids: Set<String>) {
        context.alanDataStore.edit { prefs ->
            val next = ids.filter { it.isNotBlank() }.toSet().ifEmpty { setOf(prefs[Keys.currentBookId]?.takeIf { it.isNotBlank() } ?: "demo-basics") }
            prefs[Keys.bookSelectionCleared] = false
            prefs[Keys.selectedBookIds] = next
            prefs[Keys.currentBookId] = prefs[Keys.currentBookId]?.takeIf { it.isNotBlank() && it in next } ?: next.min()
            prefs[Keys.selectedUnitKeys] = prefs[Keys.selectedUnitKeys].orEmpty().filter { key -> next.any { key.startsWith("$it::") } }.toSet()
        }
        markWrites("currentBookId", "selectedBookIds", "selectedUnitKeys")
    }

    suspend fun setSelectedUnitKeys(keys: Set<String>) {
        context.alanDataStore.edit { prefs ->
            prefs[Keys.selectedUnitKeys] = keys
        }
        markWrite("selectedUnitKeys")
    }

    suspend fun selectOnlyBook(id: String) {
        context.alanDataStore.edit { prefs ->
            prefs[Keys.selectedBookIds] = setOf(id)
            prefs[Keys.currentBookId] = id
            prefs[Keys.selectedUnitKeys] = emptySet()
            prefs[Keys.bookSelectionCleared] = false
        }
        markWrites("currentBookId", "selectedBookIds", "selectedUnitKeys")
    }

    suspend fun setDailyGoal(value: Int) {
        update(Keys.dailyGoal, value)
        markWrite("dailyGoal")
    }

    suspend fun setDailyMinutes(value: Int) {
        update(Keys.dailyMinutes, value.coerceIn(5, 180))
        markWrite("dailyMinutes")
    }

    suspend fun setAutoSpeak(value: Boolean) {
        update(Keys.autoSpeak, value)
        markWrite("autoSpeak")
    }

    suspend fun setShuffle(value: Boolean) {
        update(Keys.shuffle, value)
        markWrite("shuffle")
    }

    suspend fun setDarkMode(value: Boolean) {
        update(Keys.darkMode, value)
        markWrite("darkMode")
    }

    suspend fun setAccent(value: String) {
        update(Keys.accent, value)
        markWrite("accent")
    }

    suspend fun setStudySpeechRate(value: Float) {
        update(Keys.studySpeechRate, value.coerceIn(0.5f, 2f).toString())
        markWrite("studySpeechRate")
    }

    suspend fun setReminderEnabled(value: Boolean) {
        update(Keys.reminderEnabled, value)
        markWrite("reminderEnabled")
    }

    suspend fun setReminderHour(value: Int) {
        update(Keys.reminderHour, value)
        markWrite("reminderHour")
    }
    suspend fun setCompactHome(value: Boolean) = update(Keys.compactHome, value)
    suspend fun markNotificationPermissionRequested() = update(Keys.notificationPermissionRequested, true)
    suspend fun completeOnboarding() = update(Keys.onboardingComplete, true)

    suspend fun snapshot(): UserSettings = settings.first()

    /** 服务端设置键上次被本机用户改写的毫秒时间戳（0 表示从未改过）。 */
    fun localWriteTime(serverKey: String): Long = writeMeta.getLong("local_$serverKey", 0L)

    /** 应用来自服务器的设置值（不标记本地改动时间，避免回推）。返回是否应用成功。 */
    suspend fun applyServerSetting(serverKey: String, value: Any?): Boolean = when (serverKey) {
        "dailyGoal" -> {
            val number = (value as? Number)?.toInt() ?: return false
            context.alanDataStore.edit { it[Keys.dailyGoal] = number }
            true
        }
        "autoSpeak" -> {
            val boolean = value as? Boolean ?: return false
            context.alanDataStore.edit { it[Keys.autoSpeak] = boolean }
            true
        }
        "shuffle" -> {
            val boolean = value as? Boolean ?: return false
            context.alanDataStore.edit { it[Keys.shuffle] = boolean }
            true
        }
        "darkMode" -> {
            val boolean = value as? Boolean ?: return false
            context.alanDataStore.edit { it[Keys.darkMode] = boolean }
            true
        }
        "accent" -> {
            val string = value as? String ?: return false
            context.alanDataStore.edit { it[Keys.accent] = string }
            true
        }
        "studySpeechRate" -> {
            val number = (value as? Number)?.toFloat() ?: return false
            context.alanDataStore.edit { it[Keys.studySpeechRate] = number.coerceIn(0.5f, 2f).toString() }
            true
        }
        "reminderEnabled" -> {
            val boolean = value as? Boolean ?: return false
            context.alanDataStore.edit { it[Keys.reminderEnabled] = boolean }
            true
        }
        "reminderHour" -> {
            val number = (value as? Number)?.toInt() ?: return false
            context.alanDataStore.edit { it[Keys.reminderHour] = number }
            true
        }
        "currentBookId" -> {
            val string = (value as? String)?.takeIf { it.isNotBlank() } ?: return false
            context.alanDataStore.edit { it[Keys.currentBookId] = string }
            true
        }
        "selectedBookIds" -> {
            val ids = stringSetValue(value)?.filter { it.isNotBlank() }?.toSet()?.takeIf { it.isNotEmpty() } ?: return false
            context.alanDataStore.edit { prefs ->
                prefs[Keys.bookSelectionCleared] = false
                prefs[Keys.selectedBookIds] = ids
                val current = prefs[Keys.currentBookId].orEmpty()
                if (current.isBlank() || current !in ids) prefs[Keys.currentBookId] = ids.min()
                prefs[Keys.selectedUnitKeys] = prefs[Keys.selectedUnitKeys].orEmpty()
                    .filterTo(linkedSetOf()) { key -> ids.any { key.startsWith("$it::") } }
            }
            true
        }
        "selectedUnitKeys" -> {
            val keys = stringSetValue(value) ?: return false
            context.alanDataStore.edit { it[Keys.selectedUnitKeys] = keys }
            true
        }
        else -> false
    }

    /** 供推送到服务器的当前设置值。 */
    suspend fun serverValue(serverKey: String): Any? {
        val prefs = context.alanDataStore.data.first()
        return when (serverKey) {
            "dailyGoal" -> prefs[Keys.dailyGoal] ?: 20
            "autoSpeak" -> prefs[Keys.autoSpeak] ?: true
            "shuffle" -> prefs[Keys.shuffle] ?: false
            "darkMode" -> prefs[Keys.darkMode] ?: false
            "accent" -> prefs[Keys.accent] ?: "us"
            "studySpeechRate" -> prefs[Keys.studySpeechRate]?.toFloatOrNull() ?: 0.95f
            "reminderEnabled" -> prefs[Keys.reminderEnabled] ?: true
            "reminderHour" -> prefs[Keys.reminderHour] ?: 20
            "currentBookId" -> prefs[Keys.currentBookId]?.takeIf { it.isNotBlank() } ?: "demo-basics"
            "selectedBookIds" -> {
                if (prefs[Keys.bookSelectionCleared] == true) emptyList()
                else {
                    val stored = prefs[Keys.selectedBookIds].orEmpty().filter { it.isNotBlank() }.sorted()
                    stored.ifEmpty { listOf(prefs[Keys.currentBookId]?.takeIf { it.isNotBlank() } ?: "demo-basics") }
                }
            }
            "selectedUnitKeys" -> prefs[Keys.selectedUnitKeys].orEmpty().sorted()
            else -> null
        }
    }

    private fun markWrite(serverKey: String) {
        writeMeta.edit().putLong("local_$serverKey", System.currentTimeMillis()).apply()
    }

    private fun markWrites(vararg serverKeys: String) {
        val now = System.currentTimeMillis()
        writeMeta.edit().apply {
            serverKeys.forEach { putLong("local_$it", now) }
        }.apply()
    }

    private fun stringSetValue(value: Any?): Set<String>? = when (value) {
        is JSONArray -> buildSet {
            for (index in 0 until value.length()) {
                value.optString(index).trim().takeIf { it.isNotEmpty() }?.let(::add)
            }
        }
        is Collection<*> -> value.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }.toSet()
        else -> null
    }

    /** 切换到另一个账号时清除账号级设置，保留本机引导和通知授权状态。 */
    suspend fun resetCloudSettingsForAccount() {
        context.alanDataStore.edit { prefs ->
            prefs[Keys.currentBookId] = "demo-basics"
            prefs[Keys.selectedBookIds] = setOf("demo-basics")
            prefs[Keys.selectedUnitKeys] = emptySet()
            prefs[Keys.bookSelectionCleared] = false
            prefs.remove(Keys.dailyGoal)
            prefs.remove(Keys.dailyMinutes)
            prefs.remove(Keys.autoSpeak)
            prefs.remove(Keys.shuffle)
            prefs.remove(Keys.darkMode)
            prefs.remove(Keys.accent)
            prefs.remove(Keys.studySpeechRate)
            prefs.remove(Keys.reminderEnabled)
            prefs.remove(Keys.reminderHour)
        }
        writeMeta.edit().clear().apply()
    }

    suspend fun restoreLearningSettings(value: UserSettings) {
        context.alanDataStore.edit { prefs ->
            val books = value.selectedBookIds.filter { it.isNotBlank() }.toSet()
            val current = value.currentBookId.takeIf { it.isNotBlank() }
                ?: books.minOrNull()
                ?: prefs[Keys.currentBookId]?.takeIf { it.isNotBlank() }
                ?: "demo-basics"
            prefs[Keys.currentBookId] = current
            if (books.isEmpty()) {
                prefs.remove(Keys.selectedBookIds)
                prefs[Keys.bookSelectionCleared] = true
            } else {
                prefs[Keys.bookSelectionCleared] = false
                prefs[Keys.selectedBookIds] = books
            }
            prefs[Keys.selectedUnitKeys] = value.selectedUnitKeys.filter { it.isNotBlank() }.toSet()
            prefs[Keys.dailyGoal] = value.dailyGoal
            prefs[Keys.dailyMinutes] = value.dailyMinutes.coerceIn(5, 180)
            prefs[Keys.autoSpeak] = value.autoSpeak
            prefs[Keys.shuffle] = value.shuffle
            prefs[Keys.darkMode] = value.darkMode
            prefs[Keys.accent] = value.accent
            prefs[Keys.studySpeechRate] = value.studySpeechRate.toString()
            prefs[Keys.reminderEnabled] = value.reminderEnabled
            prefs[Keys.reminderHour] = value.reminderHour
            prefs[Keys.compactHome] = value.compactHome
        }
        markWrites(
            "currentBookId", "selectedBookIds", "selectedUnitKeys", "dailyGoal", "dailyMinutes", "autoSpeak",
            "shuffle", "darkMode", "accent", "studySpeechRate", "reminderEnabled", "reminderHour",
        )
    }

    internal suspend fun resetForTesting() {
        context.alanDataStore.edit { it.clear() }
    }

    private suspend fun update(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        context.alanDataStore.edit { it[key] = value }
    }

    private suspend fun update(key: androidx.datastore.preferences.core.Preferences.Key<Int>, value: Int) {
        context.alanDataStore.edit { it[key] = value }
    }

    private suspend fun update(key: androidx.datastore.preferences.core.Preferences.Key<Boolean>, value: Boolean) {
        context.alanDataStore.edit { it[key] = value }
    }
}
