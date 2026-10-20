package com.alanbeidanci.app.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alanbeidanci.app.AlanVocabApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SpeechSecurityRegressionTest {
    private fun parse(response: String): ByteArray = VolcengineSpeaker.javaClass
        .getDeclaredMethod("readAudioChunks", String::class.java).apply { isAccessible = true }
        .invoke(VolcengineSpeaker, response) as ByteArray

    @Test fun truncatedOrMalformedStreamsNeverBecomeCompleteAudio() {
        val first = "{\"code\":0,\"data\":\"AQID\"}"
        val end = "{\"code\":20000000,\"data\":null}"
        for (invalid in listOf(first, first + "{", first + end + "junk", first + end + "{",
            end, "{\"data\":\"AQID\"}" + end, "{\"code\":123,\"data\":\"AQID\"}" + end)) {
            assertTrue("不完整或失败的语音流必须被拒绝", runCatching { parse(invalid) }.isFailure)
        }
    }

    @Test fun completedStreamCombinesChunksWithoutDecodingNullAsAudio() {
        val response = "{\"code\":0,\"data\":\"AQID\",\"sentence\":{\"text\":\"a } b\"}}\n" +
            "{\"code\":0,\"data\":\"BA==\"}{\"code\":20000000,\"data\":null} \n"
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), parse(response))
    }

    @Test fun credentialIsEncryptedLocalAndAbsentFromLearningBackup() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AlanVocabApplication>()
        val key = "test-only-${UUID.randomUUID()}"
        try {
            SpeechCredentials.save(app, key)
            assertTrue(SpeechCredentials.isConfigured(app))
            assertEquals(key, SpeechCredentials.read(app))
            val payload = app.getSharedPreferences("alan_speech_credentials", Context.MODE_PRIVATE).getString("encrypted_key", "")!!
            assertFalse(payload.contains(key))
            app.container.repository.ensureSeeded()
            assertFalse(app.container.backupManager.exportJson().contains(key))
        } finally { SpeechCredentials.clear(app) }
    }

    @Test fun removingCredentialKeepsExistingAudioCache() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val cache = File(context.filesDir, "tts-online/credential-regression.bin")
        cache.parentFile!!.mkdirs()
        cache.writeBytes(byteArrayOf(7, 8, 9))
        try {
            SpeechCredentials.save(context, "test-only-key")
            SpeechCredentials.clear(context)
            assertFalse(SpeechCredentials.isConfigured(context))
            assertEquals("", SpeechCredentials.read(context))
            assertArrayEquals(byteArrayOf(7, 8, 9), cache.readBytes())
        } finally { cache.delete(); SpeechCredentials.clear(context) }
    }
}
