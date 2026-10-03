package com.noop.ui

import com.noop.data.JournalEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A German WHOOP export's questions fold onto the starter questions they repeat (this fork). */
class JournalImportedEquivalentsTest {

    private val imported = listOf("Alkohol konsumiert?", "Koffein konsumiert?", "Kurz vor dem Schlafengehen noch etwas gegessen?")

    @Test
    fun anEquivalentImportReplacesItsStarterInTheList() {
        val items = resolveJournalItems(imported, savedItems = emptyList())
        val names = items.map { it.canonical }
        assertTrue("Alkohol konsumiert?" in names)
        assertFalse("Did you drink any alcohol?" in names)
        assertFalse("Did you eat close to bedtime?" in names)
        // It takes the starter's group instead of falling into "Other".
        assertEquals(JournalGroup.Nutrition, items.first { it.canonical == "Alkohol konsumiert?" }.group)
    }

    @Test
    fun aQuestionThatOnlyLooksSimilarStaysSeparate() {
        // "any caffeine" is not "caffeine late in the day": both remain.
        val names = resolveJournalItems(imported, savedItems = emptyList()).map { it.canonical }
        assertTrue("Koffein konsumiert?" in names)
        assertTrue("Did you have caffeine late in the day?" in names)
    }

    @Test
    fun withoutTheImportTheStarterStays() {
        val names = resolveJournalItems(emptyList(), savedItems = emptyList()).map { it.canonical }
        assertTrue("Did you drink any alcohol?" in names)
    }

    @Test
    fun aNativeStarterAnswerJoinsTheImportedHistory() {
        val merged = mergeJournalEntries(
            imported = listOf(JournalEntry("my-whoop", "2026-10-01", "Alkohol konsumiert?", true)),
            native = listOf(JournalEntry(JOURNAL_DEVICE_ID, "2026-10-02", "Did you drink any alcohol?", false)),
        )
        assertEquals(setOf("Alkohol konsumiert?"), merged.map { it.question }.toSet())
        assertEquals(2, merged.size)
    }
}
