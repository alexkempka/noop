package com.noop.ingest

import com.noop.ui.latestByKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one-scale rule for body composition (fork addition, 04.10.2026). */
class BodyCompositionSourceTest {
    private val withings = "com.withings.wiscale2"
    private val fitdays = "example.other.scale"  // a stand-in, not a real package name

    @Test
    fun `nothing chosen keeps every app`() {
        assertTrue(BodyCompositionSource.accepts(null, withings))
        assertTrue(BodyCompositionSource.accepts(null, fitdays))
    }

    @Test
    fun `a chosen app excludes the other scale`() {
        assertTrue(BodyCompositionSource.accepts(withings, withings))
        assertFalse(BodyCompositionSource.accepts(withings, fitdays))
    }

    @Test
    fun `a day only the other scale wrote is stale, a day the chosen one wrote is kept`() {
        val observed = setOf("2026-10-03" to "body_fat", "2026-10-04" to "body_fat", "2026-10-04" to "bone_mass")
        val accepted = setOf("2026-10-04" to "body_fat", "2026-10-04" to "bone_mass")
        assertEquals(setOf("2026-10-03" to "body_fat"), BodyCompositionSource.staleRows(observed, accepted))
    }

    @Test
    fun `days this import did not see are never stale`() {
        // Health Connect only lets NOOP re-read recent days; older stored history must survive.
        assertTrue(BodyCompositionSource.staleRows(emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun `the newest reading per key is shown, a key without readings is absent`() {
        val latest = latestByKey(mapOf(
            "body_fat" to listOf("2026-10-01" to 23.4, "2026-10-04" to 23.1),
            "bone_mass" to emptyList(),
        ))
        assertEquals("2026-10-04" to 23.1, latest["body_fat"])
        assertFalse("bone_mass" in latest)
    }
}
