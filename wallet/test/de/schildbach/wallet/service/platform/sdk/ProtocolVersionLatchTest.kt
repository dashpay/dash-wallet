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
import org.junit.Test

/** In-memory [ProtocolVersionHighWaterStore]; share one instance to simulate a restart. */
internal class InMemoryProtocolVersionHighWaterStore : ProtocolVersionHighWaterStore {
    val values = mutableMapOf<String, Int>()
    override fun read(networkId: String): Int? = values[networkId]
    override fun write(networkId: String, version: Int): Boolean {
        values[networkId] = version
        return true
    }
}

/** A [ProtocolVersionLatch] over a fresh in-memory store, for tests that only need an instance. */
internal fun inMemoryProtocolVersionLatch(
    store: ProtocolVersionHighWaterStore = InMemoryProtocolVersionHighWaterStore(),
    networkId: String = "org.dash.test"
) = ProtocolVersionLatch(store, { networkId }, Dispatchers.Unconfined)

/**
 * [ProtocolVersionLatch]: the protocol version a quote and a later funding
 * read must agree on (MO-1069). A lagging or stale DAPI node answering an
 * older version after a newer one was seen must never move funding to the
 * higher LEGACY contested fee the user did not approve.
 */
class ProtocolVersionLatchTest {

    private val store = InMemoryProtocolVersionHighWaterStore()

    /**
     * A [DashSdkService] whose `currentProtocolVersion()` behaves like
     * [DashSdkServiceImpl]'s: each call takes the next live read from [lives]
     * and passes it through [latch].
     */
    private fun sdkService(latch: ProtocolVersionLatch, vararg lives: Int?): DashSdkService {
        val queue = ArrayDeque(lives.toList())
        return mockk {
            coEvery { currentProtocolVersion() } coAnswers { latch.latch(queue.removeFirst()) }
        }
    }

    @Test
    fun `a live 13 after a live 14 still reports 14`() = runTest {
        val latch = inMemoryProtocolVersionLatch(store)

        assertEquals(14, latch.latch(14))
        assertEquals(14, latch.latch(13))
    }

    @Test
    fun `a failed live read after a live 14 reports 14`() = runTest {
        val latch = inMemoryProtocolVersionLatch(store)

        assertEquals(14, latch.latch(14))
        assertEquals(14, latch.latch(null))
    }

    @Test
    fun `a failed live read on a network never seen at 14 stays unknown`() = runTest {
        val latch = inMemoryProtocolVersionLatch(store)

        assertNull(latch.latch(null))
        assertEquals(13, latch.latch(13))
        assertNull("13 is not trusted without a live read: 14 may have activated since", latch.latch(null))
    }

    @Test
    fun `a newer live version raises the latch immediately`() = runTest {
        val latch = inMemoryProtocolVersionLatch(store)

        assertEquals(13, latch.latch(13))
        assertEquals("activation is observed without a restart", 14, latch.latch(14))
        assertEquals(15, latch.latch(15))
        assertEquals(15, latch.latch(14))
        assertEquals(15, latch.latch(null))
        assertEquals(15, store.values["org.dash.test"])
    }

    @Test
    fun `the latch survives a new instance over the same store`() = runTest {
        assertEquals(14, inMemoryProtocolVersionLatch(store).latch(14))

        val afterRestart = inMemoryProtocolVersionLatch(store)

        assertEquals(14, afterRestart.latch(13))
        assertEquals(14, afterRestart.latch(null))
    }

    @Test
    fun `each network keeps its own latch`() = runTest {
        assertEquals(14, inMemoryProtocolVersionLatch(store, networkId = "org.dash.test").latch(14))

        val mainnet = inMemoryProtocolVersionLatch(store, networkId = "org.dash.production")

        assertEquals(13, mainnet.latch(13))
        assertNull(mainnet.latch(null))
        assertEquals(14, store.values["org.dash.test"])
        assertEquals(13, store.values["org.dash.production"])
    }

    @Test
    fun `a throwing store does not throw and fails closed on a current-era read`() = runTest {
        val failing = object : ProtocolVersionHighWaterStore {
            override fun read(networkId: String): Int? = throw IllegalStateException("prefs unavailable")
            override fun write(networkId: String, version: Int): Boolean =
                throw IllegalStateException("prefs unavailable")
        }
        val latch = inMemoryProtocolVersionLatch(failing)

        assertNull(latch.latch(null))
        assertNull("an unpersisted 14 must not be quoted: a restart could forget it", latch.latch(14))
        assertEquals("an older version selects the LEGACY fee anyway", 13, latch.latch(13))
    }

    @Test
    fun `a 14 the store could not persist is neither served nor remembered`() = runTest {
        val unconfirmed = object : ProtocolVersionHighWaterStore {
            val values = mutableMapOf<String, Int>()
            override fun read(networkId: String): Int? = values[networkId]
            override fun write(networkId: String, version: Int): Boolean = false
        }
        val latch = inMemoryProtocolVersionLatch(unconfirmed)

        assertNull(latch.latch(14))
        assertEquals("nothing stale was latched in memory", 13, latch.latch(13))
        assertNull(latch.latch(null))
        assertNull(unconfirmed.values["org.dash.test"])
    }

    @Test
    fun `a later confirmed write raises the latch after an unconfirmed one`() = runTest {
        var confirm = false
        val flaky = object : ProtocolVersionHighWaterStore {
            val values = mutableMapOf<String, Int>()
            override fun read(networkId: String): Int? = values[networkId]
            override fun write(networkId: String, version: Int): Boolean {
                if (confirm) values[networkId] = version
                return confirm
            }
        }
        val latch = inMemoryProtocolVersionLatch(flaky)

        assertNull(latch.latch(14))
        confirm = true
        assertEquals(14, latch.latch(14))
        assertEquals(14, latch.latch(13))
        assertEquals(14, inMemoryProtocolVersionLatch(flaky).latch(13))
    }

    @Test
    fun `funding after an unpersisted quote at 14 refuses rather than funding the legacy fee`() {
        val unconfirmed = object : ProtocolVersionHighWaterStore {
            override fun read(networkId: String): Int? = null
            override fun write(networkId: String, version: Int): Boolean = false
        }
        val service = sdkService(inMemoryProtocolVersionLatch(unconfirmed), 14, 14)

        assertEquals(
            "the quote falls back to LEGACY, never under-quoting the later funding",
            ContestedUsernameFees.LEGACY,
            runBlocking { ContestedUsernameFees.current(service) }
        )
        assertThrows(ProtocolVersionUnavailableException::class.java) {
            runBlocking { ContestedUsernameFees.resolved(service) }
        }
    }

    @Test
    fun `funding after a quote at 14 resolves the current fee even when a lagging node reports 13`() = runTest {
        val service = sdkService(inMemoryProtocolVersionLatch(store), 14, 13)

        assertEquals(ContestedUsernameFees.CURRENT, ContestedUsernameFees.current(service))
        assertEquals(ContestedUsernameFees.CURRENT, ContestedUsernameFees.resolved(service))
    }

    @Test
    fun `funding after a quote at 14 resolves the current fee even when its own read fails`() = runTest {
        val service = sdkService(inMemoryProtocolVersionLatch(store), 14, null)

        assertEquals(ContestedUsernameFees.CURRENT, ContestedUsernameFees.current(service))
        assertEquals(ContestedUsernameFees.CURRENT, ContestedUsernameFees.resolved(service))
    }

    @Test
    fun `funding on a network never seen at 14 still refuses an unknown version`() {
        val service = sdkService(inMemoryProtocolVersionLatch(store), 13, null)

        assertEquals(ContestedUsernameFees.LEGACY, runBlocking { ContestedUsernameFees.current(service) })
        assertThrows(ProtocolVersionUnavailableException::class.java) {
            runBlocking { ContestedUsernameFees.resolved(service) }
        }
    }
}
