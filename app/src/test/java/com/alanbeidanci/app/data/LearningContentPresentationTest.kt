package com.alanbeidanci.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LearningContentPresentationTest {
    @Test
    fun canonicalSpellingRemovesTrailingDictionaryAnnotation() {
        assertEquals("mother", LearningContentPresentation.canonicalSpelling("mother (mum)"))
        assertEquals("fishing rod", LearningContentPresentation.normalizedSpelling("  Fishing   Rod  "))
    }
}
