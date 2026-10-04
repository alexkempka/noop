package com.noop.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The fork's extra backup entry (ECG files + fork settings), fork addition 04.10.2026. */
class ForkBackupExtrasTest {
    @get:Rule val tmp = TemporaryFolder()

    private val sqliteMagic = byteArrayOf(
        0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66,
        0x6F, 0x72, 0x6D, 0x61, 0x74, 0x20, 0x33, 0x00,
    )

    @Test
    fun `settings and ECG files survive the round trip with their types`() {
        val json = ForkBackupExtras.encode(
            prefs = mapOf(
                "noop_profile" to mapOf("sleep_goal_minutes" to 405, "sleep_goal_asked" to true, "step_goal" to 4000),
                "noop_sleep_marks" to mapOf("marks" to "0:1759537560\n1:1759570020",
                    "applied_nights" to setOf("2026-10-04")),
                "noop_body_composition" to mapOf("chosen_package" to "com.withings.wiscale2"),
            ),
            ecgFiles = mapOf("ecg-1759500000000.json" to """{"samples":[1,2,3]}"""),
        )
        val d = ForkBackupExtras.decode(json)
        assertEquals(405, d.prefs["noop_profile"]!!["sleep_goal_minutes"])
        assertEquals(true, d.prefs["noop_profile"]!!["sleep_goal_asked"])
        assertEquals(4000, d.prefs["noop_profile"]!!["step_goal"])
        assertEquals(setOf("2026-10-04"), d.prefs["noop_sleep_marks"]!!["applied_nights"])
        assertEquals("com.withings.wiscale2", d.prefs["noop_body_composition"]!!["chosen_package"])
        assertEquals("""{"samples":[1,2,3]}""", d.ecgFiles["ecg-1759500000000.json"])
    }

    @Test
    fun `keys and files outside the list are dropped on restore`() {
        val json = ForkBackupExtras.encode(
            prefs = mapOf(
                "noop_profile" to mapOf("step_goal" to 4000, "profile.age" to 50),
                "some_other_file" to mapOf("x" to 1),
            ),
            ecgFiles = mapOf("../evil.json" to "x", "notes.txt" to "y"),
        )
        val d = ForkBackupExtras.decode(json)
        assertEquals(setOf("step_goal"), d.prefs["noop_profile"]!!.keys)
        assertFalse("some_other_file" in d.prefs)
        assertEquals(emptyMap<String, String>(), d.ecgFiles)
    }

    @Test
    fun `a backup carrying the extra entry still stages its database and yields the entry`() {
        val db = tmp.newFile().apply { outputStream().use { it.write(sqliteMagic); it.write("rows".toByteArray()) } }
        val zip = tmp.newFile("b.noopbak")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("noop-backup.sqlite")); z.write(db.readBytes()); z.closeEntry()
            z.putNextEntry(ZipEntry("manifest.json")); z.write("{}".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry(ForkBackupExtras.ENTRY_NAME)); z.write("""{"version":1}""".toByteArray()); z.closeEntry()
        }
        val staged = tmp.newFile()
        assertEquals(DataBackup.StageResult.OK,
            DataBackup.stageBackupSqlite(zip.inputStream(), DataBackup.peekHeader(zip), staged))
        assertArrayEquals(db.readBytes(), staged.readBytes())
        assertEquals("""{"version":1}""",
            DataBackup.readZipEntryText(zip.inputStream(), ForkBackupExtras.ENTRY_NAME, 1024))
    }

    @Test
    fun `an old backup without the entry, or an oversized one, yields nothing`() {
        val zip = tmp.newFile("old.noopbak")
        ZipOutputStream(zip.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("noop-backup.sqlite")); z.write(sqliteMagic); z.closeEntry()
        }
        assertNull(DataBackup.readZipEntryText(zip.inputStream(), ForkBackupExtras.ENTRY_NAME, 1024))
        val big = tmp.newFile("big.noopbak")
        ZipOutputStream(big.outputStream()).use { z ->
            z.putNextEntry(ZipEntry(ForkBackupExtras.ENTRY_NAME)); z.write(ByteArray(4096)); z.closeEntry()
        }
        assertNull(DataBackup.readZipEntryText(big.inputStream(), ForkBackupExtras.ENTRY_NAME, 1024))
    }
}
