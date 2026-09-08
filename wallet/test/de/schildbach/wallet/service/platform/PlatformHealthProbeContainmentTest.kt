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

import de.schildbach.wallet.Constants
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.dash.wallet.common.services.BlockchainStateProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * MO-973: the health probe is ADVISORY — it feeds one warning row on the
 * username screen and its own KDoc says it must never gate the submit button.
 * It was nonetheless able to kill the process.
 *
 * `probe()` caught only `Exception`, but the legacy gRPC stack fails with an
 * ERROR: `ExceptionInInitializerError` out of `NettyChannelBuilder.<clinit>`
 * when R8 strips a member its static init reaches by reflection. That sailed
 * past this catch, past the caller's bare `try/finally`
 * (`RequestUserNameViewModel.checkNetworkHealth`), out of the coroutine, and
 * took the app down — 11 times on one HONOR PTP-N49, which QA reported as a
 * username-creation crash, a username-creation failure, and "the lock screen
 * appears after clicking Continue" (that being the relaunch).
 *
 * The keep rule that stops R8 stripping it is a separate commit. This pins the
 * containment, so the NEXT thing to break underneath an advisory probe
 * degrades instead of crashing.
 */
class PlatformHealthProbeContainmentTest {

    // SUPPORTS_PLATFORM is a mutable static set from a native-ABI check at
    // runtime, so it is false under a JVM test. Without forcing it true,
    // probe() returns UNKNOWN on its first line and every assertion below
    // would pass vacuously.
    private var savedSupportsPlatform = false

    @Before
    fun setUp() {
        savedSupportsPlatform = Constants.SUPPORTS_PLATFORM
        Constants.SUPPORTS_PLATFORM = true
    }

    @After
    fun tearDown() {
        Constants.SUPPORTS_PLATFORM = savedSupportsPlatform
    }

    private fun probeWith(clientFailure: Throwable): PlatformHealthProbe {
        val platformService = mockk<PlatformService>()
        // Standing in for DAPIGrpcMasternode.<init> — the first touch of the
        // legacy client is what detonates the static initializer.
        every { platformService.client } throws clientFailure
        val stateProvider = mockk<BlockchainStateProvider>()
        coEvery { stateProvider.getState() } returns null
        return PlatformHealthProbe(platformService, stateProvider)
    }

    /**
     * THE REGRESSION PIN. The assertion that matters is that `probe()` RETURNS
     * at all — with `catch (Exception)` this test does not fail an assertion,
     * it dies with the Error, which is exactly what the app did.
     */
    @Test
    fun probe_degradesToUnknown_whenTheLegacyGrpcStackFailsToInitialize() = runBlocking {
        val probe = probeWith(ExceptionInInitializerError("NettyChannelBuilder"))

        assertEquals(PlatformHealth.UNKNOWN, probe.probe())
    }

    /** Any Error, not just the one we happened to hit. */
    @Test
    fun probe_degradesToUnknown_onAnyErrorFromTheLegacyStack() = runBlocking {
        assertEquals(
            PlatformHealth.UNKNOWN,
            probeWith(NoClassDefFoundError("io/grpc/netty/shaded/io/grpc/netty/NettyChannelBuilder")).probe()
        )
        assertEquals(
            PlatformHealth.UNKNOWN,
            probeWith(NoSuchMethodError("NioSocketChannel.<init>")).probe()
        )
    }

    /** Ordinary exceptions keep degrading as they always did. */
    @Test
    fun probe_degradesToUnknown_onAnOrdinaryException() = runBlocking {
        assertEquals(PlatformHealth.UNKNOWN, probeWith(IllegalStateException("no live address")).probe())
    }

    /**
     * Cancellation is control flow, NOT a probe failure. A `catch (Throwable)`
     * that swallows it breaks structured concurrency — the screen closing mid
     * probe would leave the caller's coroutine looking successful.
     */
    @Test
    fun probe_propagatesCancellation_ratherThanReportingUnknown() = runBlocking {
        val probe = probeWith(CancellationException("screen closed"))

        try {
            probe.probe()
            fail("cancellation must propagate, not degrade to UNKNOWN")
        } catch (expected: CancellationException) {
            // correct
        }
    }
}
