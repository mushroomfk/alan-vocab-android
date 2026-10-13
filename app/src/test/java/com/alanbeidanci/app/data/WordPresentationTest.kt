package com.alanbeidanci.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WordPresentationTest {
    @Test
    fun phoneticLineKeepsWhicheverAccentExists() {
        assertEquals("美 'kænsl    英 'kænsl", WordPresentation.phoneticLine("'kænsl", "'kænsl"))
        assertEquals("美 'kænsl", WordPresentation.phoneticLine("'kænsl", "  "))
        assertEquals("", WordPresentation.phoneticLine("", ""))
    }
}
