package com.alanbeidanci.app.audio

import org.junit.Assert.assertEquals
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
}
