/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.service.platform.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PaidContactAddressStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000_000_000L
    private val file: File get() = File(tmp.root, PaidContactAddressStore.FILE_NAME)

    private fun store() = PaidContactAddressStore(file) { now }

    @Test
    fun recorded_survivesARestart() {
        store().record("yAddr1")

        assertEquals(setOf("yAddr1"), store().paid())
    }

    @Test
    fun entriesExpireAfterTheRetention() {
        store().record("old")
        now += PaidContactAddressStore.RETENTION_MS / 2
        store().record("recent")
        now += PaidContactAddressStore.RETENTION_MS / 2 + 1

        assertEquals(setOf("recent"), store().paid())
    }

    @Test
    fun rerecording_refreshesTheTimestamp() {
        val s = store()
        s.record("a")
        now += PaidContactAddressStore.RETENTION_MS - 1
        s.record("a")
        now += 2

        assertEquals(setOf("a"), store().paid())
    }

    @Test
    fun keepsOnlyTheNewestEntries() {
        val s = store()
        repeat(PaidContactAddressStore.MAX_ENTRIES + 3) {
            now++
            s.record("addr$it")
        }

        val paid = store().paid()
        assertEquals(PaidContactAddressStore.MAX_ENTRIES, paid.size)
        assertTrue("addr0" !in paid)
        assertTrue("addr${PaidContactAddressStore.MAX_ENTRIES + 2}" in paid)
    }

    @Test
    fun unreadableLinesAreIgnored() {
        file.writeText("garbage\n$now yGood\n12 \n x y z\n")

        assertEquals(setOf("yGood"), store().paid())
    }

    @Test
    fun noFile_isMemoryOnly() {
        val s = PaidContactAddressStore(file = null) { now }
        s.record("a")

        assertEquals(setOf("a"), s.paid())
        assertEquals(emptySet<String>(), PaidContactAddressStore(file = null) { now }.paid())
    }

    @Test
    fun unwritableFile_keepsTheEntryInMemory() {
        // A directory where the file should be: every write fails.
        val dir = File(tmp.root, "blocked").apply { mkdirs() }
        File(dir, "x").writeText("")
        val s = PaidContactAddressStore(dir) { now }

        s.record("a")

        assertEquals(setOf("a"), s.paid())
    }
}
