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

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * [AtomicFileProtocolVersionHighWaterStore] at the real file boundary: "never
 * stored" and "stored but unreadable" must stay distinct, because the latch
 * fails closed only on the latter. (The SharedPreferences store it replaced
 * could not tell them apart — Android serves an empty map for an unreadable
 * or corrupt preferences file.)
 */
class AtomicFileProtocolVersionHighWaterStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val network = "org.dash.test"
    private val dir by lazy { File(tmp.root, AtomicFileProtocolVersionHighWaterStore.DIRECTORY_NAME) }
    private fun store() = AtomicFileProtocolVersionHighWaterStore { dir }
    private fun markFile() = File(dir, network)

    @Test
    fun `a mark that was never stored reads as absent`() {
        assertNull(store().read(network))
    }

    @Test
    fun `a written mark is confirmed and survives a new instance`() {
        assertTrue(store().write(network, 14))

        assertEquals(14, store().read(network))
        assertEquals("14", markFile().readText().trim())
    }

    @Test
    fun `a corrupt mark throws instead of reading as absent`() {
        dir.mkdirs()
        markFile().writeText("garbage")

        assertThrows(IOException::class.java) { store().read(network) }
    }

    @Test
    fun `an empty mark file throws instead of reading as absent`() {
        dir.mkdirs()
        markFile().writeText("")

        assertThrows(IOException::class.java) { store().read(network) }
    }

    @Test
    fun `a mark that exists but cannot be opened throws instead of reading as absent`() {
        markFile().mkdirs() // a directory where the file should be: exists, can't be read

        assertThrows(IOException::class.java) { store().read(network) }
    }

    @Test
    fun `a mark whose directory cannot be searched throws instead of reading as absent`() {
        assertTrue(store().write(network, 14))
        assumeTrue("needs a non-root filesystem that honours permissions", dir.setExecutable(false))
        try {
            assumeTrue("permission change not enforced here", !File(dir, network).canRead())

            assertThrows(IOException::class.java) { store().read(network) }
        } finally {
            dir.setExecutable(true)
        }
    }

    @Test
    fun `after a restart an unsearchable directory and a live 13 fail closed`() = runTest {
        assertTrue(store().write(network, 14))
        assumeTrue(dir.setExecutable(false))
        try {
            assumeTrue(!File(dir, network).canRead())

            assertNull("a durable 14 may be behind the denied lookup", latch().latch(13))
        } finally {
            dir.setExecutable(true)
        }
        assertEquals("the durable 14 is intact", 14, store().read(network))
    }

    @Test
    fun `each network gets its own file and unsafe characters stay inside the directory`() {
        assertTrue(store().write("org.dash.devnet/../escape", 14))
        assertTrue(store().write(network, 13))

        assertEquals(14, store().read("org.dash.devnet/../escape"))
        assertEquals(13, store().read(network))
        assertTrue(dir.listFiles().orEmpty().all { it.parentFile == dir })
        assertTrue(tmp.root.listFiles().orEmpty().none { it.name.contains("escape") })
    }

    private fun latch() = ProtocolVersionLatch(store(), { network }, Dispatchers.Unconfined)

    @Test
    fun `after a restart a corrupt mark and a live 13 fail closed and leave the file alone`() = runTest {
        dir.mkdirs()
        markFile().writeText("garbage")

        assertNull(latch().latch(13))
        assertNull(latch().latch(null))
        assertEquals("garbage", markFile().readText())
    }

    @Test
    fun `a live current-era version replaces a corrupt mark durably`() = runTest {
        dir.mkdirs()
        markFile().writeText("garbage")

        assertEquals(14, latch().latch(14))
        assertEquals(14, store().read(network))
        assertEquals("the healed mark holds after a restart", 14, latch().latch(13))
    }

    @Test
    fun `funding after a restart with a corrupt mark refuses rather than pricing from a live 13`() {
        dir.mkdirs()
        markFile().writeText("garbage")
        val latch = latch()
        val service = mockk<DashSdkService> {
            coEvery { currentProtocolVersion() } coAnswers { latch.latch(13) }
        }

        assertThrows(ProtocolVersionUnavailableException::class.java) {
            runBlocking { ContestedUsernameFees.resolved(service) }
        }
    }
}
