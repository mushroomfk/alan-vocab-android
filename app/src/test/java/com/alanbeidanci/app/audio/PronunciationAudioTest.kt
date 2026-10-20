package com.alanbeidanci.app.audio

import com.alanbeidanci.app.audio.PronunciationAudio.VoiceCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PronunciationAudioTest {
    @Test
    fun normalizationMatchesWebAndRemovesTrailingAnnotations() {
        assertEquals("the", PronunciationAudio.normalize("  THE　"))
        assertEquals("noodle", PronunciationAudio.normalize("noodle(pl. noodles)"))
        assertEquals("grandfather", PronunciationAudio.normalize("grandfather (grandpa)"))
    }

    @Test
    fun recordedAudioPathMatchesWebHashContract() {
        assertEquals(
            "/pronunciation/v1/en-GB/b9776d7ddf459c9ad5b0e1d6ac61e27befb5e99fd62446677600d7cacef544d0.flac",
            PronunciationAudio.path("the", "uk"),
        )
        assertEquals(
            "/pronunciation/v1/en-US/b9776d7ddf459c9ad5b0e1d6ac61e27befb5e99fd62446677600d7cacef544d0.flac",
            PronunciationAudio.path("the", "us"),
        )
    }

    @Test
    fun choosesTheClearestMatchingVoiceAndSkipsNoveltyVoices() {
        val voices = listOf(
            VoiceCandidate("Albert", "en", "US", 400, 100, false),
            VoiceCandidate("Samantha", "en", "US", 300, 100, false),
            VoiceCandidate("en-us-neural", "en", "US", 500, 200, true),
            VoiceCandidate("Daniel", "en", "GB", 400, 100, false),
        )
        assertEquals("en-us-neural", PronunciationAudio.chooseVoice(voices, "en", "US")?.id)
        assertEquals("Daniel", PronunciationAudio.chooseVoice(voices, "en", "GB")?.id)
        assertEquals(
            "en-us-x-sfg-local",
            PronunciationAudio.chooseVoice(
                voices + VoiceCandidate("en-us-x-sfg-local", "en", "US", 400, 100, false),
                "en",
                "US",
            )?.id,
        )
        assertEquals(
            "Samantha",
            PronunciationAudio.chooseVoice(
                listOf(
                    VoiceCandidate("en-us-neural", "en", "US", 400, 100, true),
                    VoiceCandidate("Samantha", "en", "US", 400, 300, false),
                ),
                "en",
                "US",
            )?.id,
        )
        assertNull(PronunciationAudio.chooseVoice(emptyList(), "en", "US"))
    }
}
