package com.alanbeidanci.app.data

import org.json.JSONObject

object RetentionProfileJson {
    fun encode(profile: RetentionProfile): JSONObject = JSONObject()
        .put("algorithm", profile.algorithm)
        .put("autoEnabled", profile.autoEnabled)
        .put("gain", profile.gain).put("lapse", profile.lapse)
        .put("previousGain", profile.previousGain).put("previousLapse", profile.previousLapse)
        .put("revision", profile.revision)
        .put("lastAttemptAt", profile.lastAttemptAt).put("lastAttemptThrough", profile.lastAttemptThrough)
        .put("trainedThrough", profile.trainedThrough).put("activatedAt", profile.activatedAt)
        .put("sampleCount", profile.sampleCount).put("status", profile.status)
        .put("candidateLoss", profile.candidateLoss).put("baselineLoss", profile.baselineLoss)

    fun decode(json: JSONObject): RetentionProfile {
        val profile = RetentionProfile(
            algorithm = json.getString("algorithm"), autoEnabled = json.getBoolean("autoEnabled"),
            gain = json.getDouble("gain"), lapse = json.getDouble("lapse"),
            previousGain = json.getDouble("previousGain"), previousLapse = json.getDouble("previousLapse"),
            revision = json.getInt("revision"), lastAttemptAt = json.getLong("lastAttemptAt"),
            lastAttemptThrough = json.getLong("lastAttemptThrough"), trainedThrough = json.getLong("trainedThrough"),
            activatedAt = json.getLong("activatedAt"), sampleCount = json.getInt("sampleCount"),
            status = json.getString("status"), candidateLoss = json.getDouble("candidateLoss"),
            baselineLoss = json.getDouble("baselineLoss"),
        ).upgradeLegacy()
        require(profile.isValid()) { "备份中的复习参数版本或数值无效" }
        return profile
    }
}
