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

package de.schildbach.wallet

import de.schildbach.wallet.database.dao.TxDisplayCacheDao
import de.schildbach.wallet.database.dao.TxGroupCacheDao
import de.schildbach.wallet.service.platform.sdk.CutoverUiDataService
import de.schildbach.wallet.service.platform.sdk.CutoverUiSource
import de.schildbach.wallet.service.platform.sdk.L1TxUiRecord
import de.schildbach.wallet.service.platform.sdk.SdkBalanceSplitDuffs
import de.schildbach.wallet.service.platform.sdk.SdkSeamTxSnapshot
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import io.mockk.every
import io.mockk.mockk
import java.util.function.BooleanSupplier
import java.util.function.Supplier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.bitcoinj.core.Address
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.ReceiveAddressUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SR-03 live-receive refusal END TO END: the SDK service's live read and
 * [WalletApplication.decideLiveReceiveAddress] together.
 *
 * [LiveReceiveAddressDecisionTest] pins the decision rule in isolation, which
 * is not enough on its own — the rule's post-cutover arm is only ever reached
 * when the service answers NOTHING, and the service used to answer a failed
 * live revalidation out of its warm cache. A non-null answer is returned
 * unchanged by the decision, so the fail-closed handling the Receive screen
 * relies on was unreachable in production: cache A, take a payment on A, let
 * both the event refresh and the next live lookup fail, and the already-paid
 * QR stayed on screen.
 *
 * In package `de.schildbach.wallet` because the decision is package-private.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveReceiveAddressHeldCacheTest {

    private val params = TestNet3Params.get()

    /** A real testnet address, so the engine answer can cross the decision's Address boundary. */
    private val engineAddress = "ydW78zVxRgNhANX2qtG4saSCC5ejNQjw2U"

    /** The HELD dashj chain's frozen pointer — the address the refusal exists to avoid. */
    private val frozenDashj = Address.fromBase58(params, "yM9uCfkYnDbBwfHiSSQ4sNDLiKQRhMTeYH")

    /** Only what the receive-address path needs; everything else is inert. */
    private class ReceiveOnlySource(
        @Volatile var nextReceiveAddress: String?
    ) : CutoverUiSource {
        override suspend fun boundWalletIdOrNull(): String? = "cd".repeat(32)

        override fun observeTotalDuffs(walletIdHex: String): Flow<Long> = MutableStateFlow(123_456L)

        override suspend fun currentTotalDuffs(walletIdHex: String): Long = 123_456L

        override suspend fun currentBalanceSplitDuffs(walletIdHex: String): SdkBalanceSplitDuffs =
            SdkBalanceSplitDuffs(confirmed = 123_456L, unconfirmed = 0L)

        override suspend fun coinJoinFundedTxids(
            walletIdHex: String,
            txidHexes: Collection<String>
        ): Set<String> = emptySet()

        override fun observeWalletTxRecords(walletIdHex: String): Flow<List<L1TxUiRecord>> =
            MutableStateFlow(emptyList())

        override fun observeSeamTxSnapshots(walletIdHex: String): Flow<SdkSeamTxSnapshot> =
            MutableStateFlow(SdkSeamTxSnapshot(emptyList(), emptyMap(), emptySet(), emptyMap()))

        override fun nextReceiveAddressOrNull(walletIdHex: String, accountIndex: Int): String? =
            nextReceiveAddress
    }

    private fun buildService(
        source: CutoverUiSource,
        scope: kotlinx.coroutines.CoroutineScope
    ): CutoverUiDataService {
        val config = mockk<DashPayConfig> {
            every { observePreservingErrors(DashPayConfig.CUTOVER_STATE) } returns flowOf("CUT_OVER")
        }
        return CutoverUiDataService(
            source = source,
            dashPayConfig = config,
            scope = scope,
            txDisplayCacheDao = mockk<TxDisplayCacheDao>(relaxed = true),
            txGroupCacheDao = mockk<TxGroupCacheDao>(relaxed = true),
            walletUIConfig = mockk<WalletUIConfig>(relaxed = true),
            resolveString = { "" },
            notifyCoinsReceived = {}
        )
    }

    /** The pipelines hop onto a real dispatcher, so virtual time alone does not drain them. */
    private fun TestScope.pumpUntil(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            runCurrent()
            if (condition()) return true
            Thread.sleep(5)
        }
        runCurrent()
        return condition()
    }

    @Test
    fun postCutover_aFailedRevalidationOnAWarmCacheReachesTheUnavailablePath() = runTest {
        val source = ReceiveOnlySource(engineAddress)
        val service = buildService(source, backgroundScope)
        service.start()
        assertTrue(
            "precondition: the cache is WARM with the engine address",
            pumpUntil { service.sdkReceiveAddressOrNull() == engineAddress }
        )

        // A payment lands on that address and the engine stops answering: the
        // event refresh fails, and so does the next live lookup.
        source.nextReceiveAddress = null

        val live = service.sdkReceiveAddressLiveBlockingOrNull()
            ?.let { Address.fromBase58(params, it) }
        assertNull("a live read must not be answered from the hold", live)

        var fallbackUsed = false
        val thrown = try {
            WalletApplication.decideLiveReceiveAddress(
                live,
                BooleanSupplier { true },
                "current",
                Supplier { fallbackUsed = true; frozenDashj }
            )
            false
        } catch (e: ReceiveAddressUnavailableException) {
            true
        }

        assertTrue(
            "a failed revalidation must reach the decision's unavailable path, not pass as an answer",
            thrown
        )
        assertFalse(
            "failing must not touch the held dashj chain — that address is the defect",
            fallbackUsed
        )
        // The hold itself is untouched: the SYNCHRONOUS overlay still has
        // somewhere better than the frozen dashj pointer to go.
        assertEquals(engineAddress, service.sdkReceiveAddressOrNull())
    }

    @Test
    fun postCutover_arevalidatedAddressIsStillServedThroughTheDecision() = runTest {
        // The other direction, so the refusal above cannot be met by refusing
        // everything: a read that DOES reach the engine is served, and the
        // decision never consults ownership or the dashj chain for it.
        val source = ReceiveOnlySource(engineAddress)
        val service = buildService(source, backgroundScope)
        service.start()
        assertTrue(pumpUntil { service.sdkReceiveAddressOrNull() == engineAddress })

        val live = service.sdkReceiveAddressLiveBlockingOrNull()
            ?.let { Address.fromBase58(params, it) }

        var ownershipAsked = false
        var fallbackUsed = false
        val served = WalletApplication.decideLiveReceiveAddress(
            live,
            BooleanSupplier { ownershipAsked = true; true },
            "current",
            Supplier { fallbackUsed = true; frozenDashj }
        )

        assertEquals(Address.fromBase58(params, engineAddress), served)
        assertFalse("the happy path must not pay for an ownership read", ownershipAsked)
        assertFalse(fallbackUsed)
    }
}
