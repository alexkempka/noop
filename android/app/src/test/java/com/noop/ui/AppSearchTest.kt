package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSearchTest {
    private val bp = SearchEntry("Blutdruck – Schätzung", listOf("kalibrieren", "Manschette"), "vital_detail/blood_pressure")
    private val sleep = SearchEntry("Schlafziel", listOf("Schlafbedarf"), "settings")

    @Test fun findsByTitleOrKeywordIgnoringCaseAndUmlauts() {
        assertTrue(AppSearch.matches(bp, "blutdruck"))
        assertTrue(AppSearch.matches(bp, "KALIBRIEREN"))
        assertTrue(AppSearch.matches(bp, "schatzung"))   // ä typed as a
        assertTrue(AppSearch.matches(sleep, "schlaf"))
    }

    @Test fun everyWordMustMatch() {
        assertTrue(AppSearch.matches(bp, "blutdruck kalib"))
        assertFalse(AppSearch.matches(bp, "blutdruck schlaf"))
    }

    @Test fun anEmptyQueryFindsNothing() = assertFalse(AppSearch.matches(bp, "   "))

    @Test fun duplicatesAreFoldedAndOrderKept() =
        assertEquals(listOf(bp, sleep), AppSearch.search(listOf(bp, sleep, bp), "e"))
}
