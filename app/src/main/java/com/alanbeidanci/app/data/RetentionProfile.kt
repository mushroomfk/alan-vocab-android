package com.alanbeidanci.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Versioned, bounded two-parameter calibration of the existing FSRS-5 transition. */
data class RetentionParameters(val gain: Double = 1.54575, val lapse: Double = 1.9395) {
    fun isValid(): Boolean = gain.isFinite() && lapse.isFinite() && gain in 0.9..2.2 && lapse in 1.0..3.0

    companion object {
        const val ALGORITHM = "alan-fsrs5-calibration-v2"
        const val LEGACY_ALGORITHM = "alan-fsrs5-calibration-v1"
    }
}

@Entity(tableName = "retention_profile")
data class RetentionProfile(
    @PrimaryKey val id: Int = 1,
    val generation: String = "",
    val algorithm: String = RetentionParameters.ALGORITHM,
    val autoEnabled: Boolean = true,
    val gain: Double = 1.54575,
    val lapse: Double = 1.9395,
    val previousGain: Double = 1.54575,
    val previousLapse: Double = 1.9395,
    val revision: Int = 0,
    val lastAttemptAt: Long = 0,
    val lastAttemptThrough: Long = 0,
    val trainedThrough: Long = 0,
    val activatedAt: Long = 0,
    val sampleCount: Int = 0,
    val status: String = "COLLECTING",
    val candidateLoss: Double = -1.0,
    val baselineLoss: Double = -1.0,
) {
    fun parameters(): RetentionParameters = RetentionParameters(gain, lapse)
    fun previousParameters(): RetentionParameters = RetentionParameters(previousGain, previousLapse)
    /** Preserve both parameter sets; old evidence is retained but not reinterpreted by v2 fitting. */
    fun upgradeLegacy(): RetentionProfile = if (algorithm == RetentionParameters.LEGACY_ALGORITHM) copy(
        algorithm = RetentionParameters.ALGORITHM, lastAttemptAt = 0, lastAttemptThrough = 0,
        trainedThrough = 0, activatedAt = 0, sampleCount = 0, status = "COLLECTING",
        candidateLoss = -1.0, baselineLoss = -1.0,
    ) else this
    fun isValid(): Boolean = id == 1 && algorithm == RetentionParameters.ALGORITHM &&
        parameters().isValid() && previousParameters().isValid() && revision >= 0 &&
        candidateLoss.isFinite() && baselineLoss.isFinite() && sampleCount >= 0 &&
        lastAttemptAt >= 0 && lastAttemptThrough >= 0 && trainedThrough >= 0 && activatedAt >= 0

    fun description(): String = when {
        !autoEnabled -> "自动调整已关闭，保留当前排期参数。"
        status == "ROLLED_BACK" -> "新记录未支持上次调整，已恢复上一组参数。"
        status == "INVALID_RESET" -> "参数校验未通过，已恢复默认参数。"
        status == "NO_IMPROVEMENT" -> "已检查学习记录，暂时保留原参数。"
        revision > 0 && parameters() != RetentionParameters() -> "正在使用个人参数；只在验证有改善时更新。"
        else -> "正在积累有效复习记录（$sampleCount 条），记录足够后自动检查。"
    }
}

@Dao
interface RetentionProfileDao {
    @Query("SELECT * FROM retention_profile WHERE id = 1")
    suspend fun get(): RetentionProfile?

    @Query("SELECT * FROM retention_profile WHERE id = 1")
    fun observe(): Flow<RetentionProfile?>

    @Upsert
    suspend fun save(profile: RetentionProfile)

    @Query("DELETE FROM retention_profile")
    suspend fun clear()
}
