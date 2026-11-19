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

package de.schildbach.wallet.service.platform

import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.bitcoinj.evolution.SimplifiedMasternodeListManager
import org.dashj.platform.dapiclient.DapiClient
import org.dashj.platform.sdk.callbacks.ContextProvider
import org.dashj.platform.sdk.platform.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * [PlatformServiceImplementation] constructs the legacy `Platform` client (whose constructor
 * loads `libsdklib`) only on first use, exactly once under concurrency, and wires the context
 * provider and the latest masternode-list manager before the client is visible.
 */
class PlatformServiceLazyClientTest {

    private val created = AtomicInteger(0)

    /** A fake legacy client whose DAPI client records the context provider it was given. */
    private class FakeClient {
        val dapi = mockk<DapiClient>(relaxed = true)
        val provider = slot<ContextProvider>()
        val platform = mockk<Platform>(relaxed = true)

        init {
            every { dapi.contextProvider = capture(provider) } just runs
            every { platform.client } returns dapi
        }
    }

    private val clients: MutableList<FakeClient> = Collections.synchronizedList(mutableListOf())

    private fun countingFactory(): (org.bitcoinj.core.NetworkParameters) -> Platform = {
        created.incrementAndGet()
        FakeClient().also { clients += it }.platform
    }

    private fun service(
        supported: Boolean = true,
        factory: (org.bitcoinj.core.NetworkParameters) -> Platform = countingFactory()
    ) = PlatformServiceImplementation(mockk(relaxed = true), mockk(relaxed = true), supported, factory)

    @Test
    fun constructingTheServiceCreatesNoClient() {
        service()
        assertEquals(0, created.get())
    }

    @Test
    fun aManagerDeliveredBeforeCreationIsStoredWithoutCreatingAndAppliedAtCreation() {
        val svc = service()
        val manager = mockk<SimplifiedMasternodeListManager>()

        svc.setMasternodeListManager(manager)
        assertEquals(0, created.get())

        val platform = svc.platform
        val fake = clients.single()
        verifyOrder {
            fake.dapi.contextProvider = any()
            platform.setMasternodeListManager(manager)
        }
        assertTrue(fake.provider.isCaptured)
    }

    @Test
    fun aManagerDeliveredAfterCreationIsAppliedToTheExistingClient() {
        val svc = service()
        val platform = svc.platform
        val manager = mockk<SimplifiedMasternodeListManager>()

        svc.setMasternodeListManager(manager)

        verify(exactly = 1) { platform.setMasternodeListManager(manager) }
        assertEquals(1, created.get())
    }

    @Test
    fun theLatestManagerWins() {
        val svc = service()
        val first = mockk<SimplifiedMasternodeListManager>()
        val second = mockk<SimplifiedMasternodeListManager>()

        svc.setMasternodeListManager(first)
        svc.setMasternodeListManager(second)
        val platform = svc.platform

        verify(exactly = 0) { platform.setMasternodeListManager(first) }
        verify(exactly = 1) { platform.setMasternodeListManager(second) }
    }

    @Test
    fun concurrentFirstCallersShareOneClient() {
        val enter = CountDownLatch(1)
        val release = CountDownLatch(1)
        val svc = service(factory = {
            created.incrementAndGet()
            enter.countDown()
            release.await(5, TimeUnit.SECONDS) // park the first creation
            FakeClient().also { clients += it }.platform
        })
        val seen: MutableList<Platform> = Collections.synchronizedList(mutableListOf())
        val readers = (1..8).map { i ->
            thread {
                when (i % 4) {
                    0 -> seen += svc.platform
                    1 -> { svc.dpp; seen += svc.platform }
                    2 -> { svc.profiles; seen += svc.platform }
                    else -> { svc.client; seen += svc.platform }
                }
            }
        }
        try {
            assertTrue(enter.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
        }
        readers.forEach { it.join(5_000) }

        assertEquals(1, created.get())
        assertEquals(8, seen.size)
        val only = clients.single().platform
        seen.forEach { assertSame(only, it) }
    }

    @Test
    fun aFailingFactoryPropagatesAndTheNextCallRetries() {
        var calls = 0
        val svc = service(factory = {
            calls++
            if (calls == 1) throw IllegalStateException("native load failed")
            FakeClient().also { clients += it }.platform
        })

        assertThrows(IllegalStateException::class.java) { svc.platform }
        val platform = svc.platform

        assertEquals(2, calls)
        assertSame(clients.single().platform, platform)
        assertSame(platform, svc.platform)
    }

    @Test
    fun theContextProviderReturnsNotFoundBeforeAnyManagerIsDelivered() {
        val svc = service()
        svc.platform
        val provider = clients.single().provider.captured

        assertNull(provider.getQuorumPublicKey(1, ByteArray(32), 100))
    }

    @Test
    fun unsupportedPlatformNeverCreatesAndAccessorsThrow() {
        val svc = service(supported = false)

        assertThrows(NullPointerException::class.java) { svc.platform }
        assertThrows(NullPointerException::class.java) { svc.profiles }
        assertThrows(NullPointerException::class.java) { svc.dpp }
        svc.setMasternodeListManager(mockk())

        assertEquals(0, created.get())
    }
}
