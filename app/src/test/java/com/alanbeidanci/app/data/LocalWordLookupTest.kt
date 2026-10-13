package com.alanbeidanci.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalWordLookupTest {
    @Test
    fun scanReadsPlainEnglishAndChinese() {
        assertEquals("achieve", LocalWordLookup.scanTerm("achieve"))
        assertEquals("please", LocalWordLookup.scanTerm("please learn achieve today"))
        assertEquals("实现", LocalWordLookup.scanTerm("实现"))
    }

    @Test
    fun scanReadsJsonAndQuery() {
        assertEquals("diamond", LocalWordLookup.scanTerm("""{"word":"diamond"}"""))
        assertEquals("block", LocalWordLookup.scanTerm("alan://lookup?word=block"))
        assertEquals("方块", LocalWordLookup.scanTerm("https://example.com/scan?q=%E6%96%B9%E5%9D%97"))
        assertEquals("100%", LocalWordLookup.scanTerm("alan://lookup?word=100%25"))
    }

    @Test
    fun scanRejectsEmptyContent() {
        assertNull(LocalWordLookup.scanTerm("   "))
        assertNull(LocalWordLookup.scanTerm("12345"))
    }

    @Test
    fun likePatternEscapesWildcards() {
        assertEquals("100!%", LocalWordLookup.likePattern("100%"))
        assertEquals("a!_b", LocalWordLookup.likePattern("a_b"))
    }
}
