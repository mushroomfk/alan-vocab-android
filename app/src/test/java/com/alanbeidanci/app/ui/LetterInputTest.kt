package com.alanbeidanci.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterInputTest {
    @Test
    fun learningKeepsTypedLettersAndAllowsBackspace() {
        val first = LetterInput.apply("", "a", "apple", judgeWhenComplete = false)
        assertEquals("a", first.text)
        assertFalse(first.accepted)
        assertFalse(first.mistake)

        val wrong = LetterInput.apply("app", "appx", "apple", judgeWhenComplete = false)
        assertEquals("appx", wrong.text)
        assertTrue(wrong.mistake)
        assertFalse(wrong.accepted)

        val fixed = LetterInput.apply("appx", "appl", "apple", judgeWhenComplete = false)
        assertEquals("appl", fixed.text)
        assertFalse(fixed.mistake)

        val done = LetterInput.apply("appl", "apple", "apple", judgeWhenComplete = false)
        assertTrue(done.accepted)
        assertFalse(done.mistake)
    }

    @Test
    fun learningDoesNotTreatALongerWordAsSuccess() {
        val decision = LetterInput.apply("apple", "apples", "apple", judgeWhenComplete = false)
        assertFalse(decision.accepted)
        assertEquals("apples", decision.text)
        assertTrue(decision.mistake)
    }

    @Test
    fun learningIgnoresCase() {
        val decision = LetterInput.apply("Appl", "Apple", "apple", judgeWhenComplete = false)
        assertTrue(decision.accepted)
    }

    @Test
    fun testWaitsUntilTheWholeWordBeforeJudging() {
        val partial = LetterInput.apply("ap", "apx", "apple", judgeWhenComplete = true)
        assertEquals("apx", partial.text)
        assertFalse(partial.rejected)
        assertFalse(partial.mistake)

        val wrong = LetterInput.apply("appl", "applx", "apple", judgeWhenComplete = true)
        assertTrue(wrong.rejected)
        assertEquals("", wrong.text)

        val corrected = LetterInput.apply("", "apple", "apple", judgeWhenComplete = true)
        assertTrue(corrected.accepted)
    }

    @Test
    fun stripsSpacesAndPunctuationFromTheKeyboard() {
        val decision = LetterInput.apply("", "ap ple!", "apple", judgeWhenComplete = false)
        assertEquals("apple", decision.text)
        assertTrue(decision.accepted)
    }

    @Test
    fun eightLetterWordFitsAPhoneAndFourteenNeedsScroll() {
        val available = 328f
        val gap = 6f
        assertFalse(LetterInput.rowOverflows(available, 8, gap, 30f, 46f))
        assertTrue(LetterInput.rowOverflows(available, 14, gap, 30f, 46f))
        assertFalse(LetterInput.rowOverflows(available, 12, gap, 20f, 46f))
        assertEquals(46f, LetterInput.cellWidth(available, 4, gap, 30f, 46f))
    }
}
