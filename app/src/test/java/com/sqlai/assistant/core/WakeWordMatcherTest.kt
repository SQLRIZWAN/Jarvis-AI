package com.sqlai.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordMatcherTest {

    @Test
    fun matchesAtWordBoundaries() {
        assertTrue(WakeWordMatcher.containsWakeWord("sql open whatsapp", "sql"))
        assertTrue(WakeWordMatcher.containsWakeWord("hello sql there", "sql"))
        assertTrue(WakeWordMatcher.containsWakeWord("SQL STOP", "sql"))
        assertTrue(WakeWordMatcher.containsWakeWord("bolo, sql. kya haal", "sql"))
    }

    @Test
    fun rejectsPartialWords() {
        assertFalse(WakeWordMatcher.containsWakeWord("mysql query chalao", "sql"))
        assertFalse(WakeWordMatcher.containsWakeWord("sqlserver kholo", "sql"))
        assertFalse(WakeWordMatcher.containsWakeWord("psql dump le lo", "sql"))
        assertFalse(WakeWordMatcher.containsWakeWord("kya haal hai", "sql"))
    }

    @Test
    fun handlesEmptyInputs() {
        assertFalse(WakeWordMatcher.containsWakeWord("", "sql"))
        assertFalse(WakeWordMatcher.containsWakeWord("hello", ""))
        assertEquals(-1, WakeWordMatcher.find("", "sql"))
    }

    @Test
    fun remainderStripsWakeWordAndPunctuation() {
        assertEquals(
            "open whatsapp",
            WakeWordMatcher.remainder("sql open whatsapp", "sql")
        )
        assertEquals(
            "stop",
            WakeWordMatcher.remainder("SQL stop!", "sql")
        )
        assertEquals(
            "kholo",
            WakeWordMatcher.remainder("hello sql, kholo", "sql")
        )
        assertEquals(
            "bolo",
            WakeWordMatcher.remainder("bolo", "sql")
        )
    }

    @Test
    fun findsWakeWordAfterEarlierSubstring() {
        // "mysql" contains "sql" but NOT at a word boundary - "sql" later
        // in the same text must still be found (index 11).
        assertTrue(WakeWordMatcher.find("mysql mein sql likho", "sql") == 11)
    }
}
