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

import de.schildbach.wallet_test.R
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.dash.wallet.common.money.Dash
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The detail sheet follows its entry live: settlement, failure, wallet change, ownership. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlatformActivityDetailFeedTest {
    private val walletA = "aa".repeat(32)
    private val walletB = "bb".repeat(32)
    private val idHex = "01".repeat(32)
    private val rowId = shieldedHistoryRowId(0, idHex)

    private fun entry(
        status: ShieldedActivityStatus,
        kindTag: Int = ShieldedActivityKindTag.IDENTITY_CREATE,
        id: String = idHex
    ) = ShieldedActivityEntry(
        id = id,
        direction = ShieldedActivityDirection.OUT,
        amount = Dash(2_796_148L),
        timestampMs = 1_000L,
        memo = null,
        pending = status == ShieldedActivityStatus.PENDING,
        kindTag = kindTag,
        accountIndex = 0,
        amountCredits = 2_796_148_800L,
        feeCredits = 203_851_200L,
        status = status
    )

    private fun service(
        feed: kotlinx.coroutines.flow.Flow<ShieldedActivitySnapshot?>,
        ownership: () -> CounterpartyOwnership = { CounterpartyOwnership.OWN }
    ): ShieldedBalanceService = mockk<ShieldedBalanceService>().also {
        every { it.observeShieldedActivityOrNotReady() } returns feed
        coEvery { it.counterpartyOwnership(any(), any()) } answers { ownership() }
    }

    @Test
    fun pendingFlipsToConfirmedInPlace_thenAFailedEntryEndsTheSheet() = runTest {
        val feed = MutableStateFlow<ShieldedActivitySnapshot?>(
            ShieldedActivitySnapshot(walletA, listOf(entry(ShieldedActivityStatus.PENDING)))
        )
        val states = mutableListOf<PlatformActivityDetailState>()
        val job = launch { platformActivityDetailFeed(service(feed), rowId).collect { states += it } }
        runCurrent()
        assertTrue((states.last() as PlatformActivityDetailState.Shown).detail.pending)

        feed.value = ShieldedActivitySnapshot(walletA, listOf(entry(ShieldedActivityStatus.CONFIRMED)))
        runCurrent()
        assertFalse((states.last() as PlatformActivityDetailState.Shown).detail.pending)

        // Failed entries are dropped by the mapping: the entry is gone.
        feed.value = ShieldedActivitySnapshot(walletA, listOf(entry(ShieldedActivityStatus.CONFIRMED, id = "02".repeat(32))))
        runCurrent()
        assertEquals(PlatformActivityDetailState.Unavailable, states.last())
        assertTrue(job.isCompleted) // terminal
        assertEquals(3, states.size)
    }

    @Test
    fun walletChangeOrNotReadyAfterLoad_endsTheSheet() = runTest {
        for (next in listOf<ShieldedActivitySnapshot?>(
            ShieldedActivitySnapshot(walletB, listOf(entry(ShieldedActivityStatus.CONFIRMED))),
            null
        )) {
            val feed = MutableStateFlow<ShieldedActivitySnapshot?>(
                ShieldedActivitySnapshot(walletA, listOf(entry(ShieldedActivityStatus.CONFIRMED)))
            )
            val states = mutableListOf<PlatformActivityDetailState>()
            launch { platformActivityDetailFeed(service(feed), rowId).collect { states += it } }
            runCurrent()
            assertTrue(states.last() is PlatformActivityDetailState.Shown)
            feed.value = next
            runCurrent()
            assertEquals(PlatformActivityDetailState.Unavailable, states.last())
        }
    }

    @Test
    fun waitsForAReadyWallet_thenGivesUp() = runTest {
        val feed = MutableStateFlow<ShieldedActivitySnapshot?>(null)
        val states = mutableListOf<PlatformActivityDetailState>()
        launch { platformActivityDetailFeed(service(feed), rowId, readyWaitMs = 1_000L).collect { states += it } }
        runCurrent()
        assertTrue(states.isEmpty()) // not ready yet: nothing shown, nothing fabricated
        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(listOf<PlatformActivityDetailState>(PlatformActivityDetailState.Unavailable), states)
    }

    @Test
    fun ownershipIsReResolvedPerEmission() = runTest {
        var ownership = CounterpartyOwnership.OWN
        val feed = MutableStateFlow<ShieldedActivitySnapshot?>(
            ShieldedActivitySnapshot(
                walletA,
                listOf(entry(ShieldedActivityStatus.PENDING, kindTag = ShieldedActivityKindTag.UNSHIELD))
            )
        )
        val states = mutableListOf<PlatformActivityDetailState>()
        backgroundScope.launch { platformActivityDetailFeed(service(feed) { ownership }, rowId).collect { states += it } }
        runCurrent()
        assertEquals(
            R.string.external_send_balance_name_platform,
            (states.last() as PlatformActivityDetailState.Shown).detail.toBalanceRes
        )
        ownership = CounterpartyOwnership.EXTERNAL
        feed.value = ShieldedActivitySnapshot(
            walletA,
            listOf(entry(ShieldedActivityStatus.CONFIRMED, kindTag = ShieldedActivityKindTag.UNSHIELD))
        )
        runCurrent()
        val detail = (states.last() as PlatformActivityDetailState.Shown).detail
        assertEquals(null, detail.toBalanceRes)
        assertEquals(R.string.transaction_details_amount_sent, detail.titleRes)
    }

    @Test
    fun malformedRowIdUnknownWithdrawalOrAFailingFeed_areUnavailable() = runTest {
        val ok = MutableStateFlow<ShieldedActivitySnapshot?>(
            ShieldedActivitySnapshot(walletA, listOf(entry(ShieldedActivityStatus.CONFIRMED)))
        )
        val collect = { svc: ShieldedBalanceService, id: String ->
            val out = mutableListOf<PlatformActivityDetailState>()
            launch { platformActivityDetailFeed(svc, id).collect { out += it } }
            out
        }
        val malformed = collect(service(ok), "shielded:0:nothex")
        val withdrawal = collect(
            service(
                MutableStateFlow(
                    ShieldedActivitySnapshot(
                        walletA,
                        listOf(entry(ShieldedActivityStatus.CONFIRMED, kindTag = ShieldedActivityKindTag.WITHDRAWAL))
                    )
                )
            ) { CounterpartyOwnership.UNKNOWN },
            rowId
        )
        val failing = collect(service(flow { throw IllegalStateException("db closed") }), rowId)
        runCurrent()
        assertEquals(listOf<PlatformActivityDetailState>(PlatformActivityDetailState.Unavailable), malformed)
        assertEquals(listOf<PlatformActivityDetailState>(PlatformActivityDetailState.Unavailable), withdrawal)
        assertEquals(listOf<PlatformActivityDetailState>(PlatformActivityDetailState.Unavailable), failing)
    }
}
