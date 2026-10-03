package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalFollowUpTest {

    @Test
    fun proteinAsksForGrams() {
        val f = journalFollowUps("Eiweiß eingenommen?")
        assertEquals(1, f.size)
        assertEquals("g", (f[0] as JournalFollowUp.Amount).unit)
    }

    @Test
    fun caffeineAsksForServingsAndTheLastTime() {
        val f = journalFollowUps("Koffein konsumiert?")
        assertTrue(f[0] is JournalFollowUp.Count)
        assertTrue(f[1] is JournalFollowUp.LastTime)
        assertEquals(f.map { it::class }, journalFollowUps("Did you have caffeine late in the day?").map { it::class })
    }

    @Test
    fun otherQuestionsAskNothingMore() {
        assertTrue(journalFollowUps("Alkohol konsumiert?").isEmpty())
        assertTrue(journalFollowUps("Did you use a sauna?").isEmpty())
    }

    @Test
    fun theTimeRowIsNeverABehaviour() {
        val key = journalLastTimeKey("Koffein konsumiert?")
        assertTrue(isJournalDetailKey(key))
        assertFalse(isJournalDetailKey("Koffein konsumiert?"))
    }
}
