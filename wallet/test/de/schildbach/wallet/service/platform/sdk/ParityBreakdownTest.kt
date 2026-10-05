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

import org.bitcoinj.coinjoin.utils.CoinJoinTransactionType
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * The transaction-level parity breakdown: the pure grouping and set
 * arithmetic ([computeParityBreakdown]), its log/report text, the run
 * cadence, the small persisted store, and the one-pass dashj collector
 * against a real dashj wallet.
 */
class ParityBreakdownTest {

    private fun h(n: Int): Sha256Hash = Sha256Hash.wrap(String.format("%064x", n))

    private val last = 1_000

    private fun group(b: ParityBreakdown, g: DashjOnlyGroup) = b.group(g)

    // ── computeParityBreakdown ────────────────────────────────────────

    @Test
    fun emptyInputs_giveAnAllZeroBreakdown() {
        val b = computeParityBreakdown(emptyList(), emptyMap(), last)
        assertEquals(0, b.dashjConsidered)
        assertEquals(0, b.sdkTotal)
        assertEquals(0, b.shared)
        assertEquals(0, b.dashjOnly)
        assertEquals(0L, b.dashjOnlyNetDuffs)
        assertEquals(0, b.sdkOnly)
        assertTrue(b.movesMoney.isEmpty())
        assertTrue(b.sdkOnlyExamples.isEmpty())
        // Every group is present, so the report always lists all of them.
        assertEquals(DashjOnlyGroup.values().toList(), b.dashjOnlyGroups.keys.toList())
    }

    @Test
    fun eachDashjOnlyGroup_isCountedWithItsNet() {
        val txs = listOf(
            DashjTxFacts(h(1), 10, coinJoinType = CoinJoinTransactionType.Mixing),
            DashjTxFacts(h(2), 11, coinJoinType = CoinJoinTransactionType.Mixing),
            DashjTxFacts(h(3), 12, netDuffs = -226, coinJoinType = CoinJoinTransactionType.CreateDenomination),
            DashjTxFacts(h(4), 13, netDuffs = -200, coinJoinType = CoinJoinTransactionType.MakeCollateralInputs),
            DashjTxFacts(h(5), 14, netDuffs = -10_000, coinJoinType = CoinJoinTransactionType.MixingFee),
            DashjTxFacts(h(6), 15, netDuffs = -300, coinJoinType = CoinJoinTransactionType.CombineDust),
            DashjTxFacts(h(7), 16, netDuffs = 5_000_000, dashPayContact = true),
            DashjTxFacts(h(8), NO_BLOCK, netDuffs = -7_000),
            DashjTxFacts(h(9), 17, netDuffs = 42),
            DashjTxFacts(h(10), 18)
        )
        val b = computeParityBreakdown(txs, emptyMap(), last)

        assertEquals(GroupTotal(2, 0), group(b, DashjOnlyGroup.COINJOIN_MIXING))
        assertEquals(GroupTotal(1, -226), group(b, DashjOnlyGroup.COINJOIN_CREATE_DENOMINATIONS))
        assertEquals(GroupTotal(1, -200), group(b, DashjOnlyGroup.COINJOIN_MAKE_COLLATERAL))
        assertEquals(GroupTotal(1, -10_000), group(b, DashjOnlyGroup.COINJOIN_MIXING_FEE))
        assertEquals(GroupTotal(1, -300), group(b, DashjOnlyGroup.COINJOIN_COMBINE_DUST))
        assertEquals(GroupTotal(1, 5_000_000), group(b, DashjOnlyGroup.DASHPAY_CONTACT))
        assertEquals(GroupTotal(1, -7_000), group(b, DashjOnlyGroup.UNCONFIRMED))
        assertEquals(GroupTotal(1, 42), group(b, DashjOnlyGroup.MOVES_MONEY))
        assertEquals(GroupTotal(1, 0), group(b, DashjOnlyGroup.OTHER))
        assertEquals(10, b.dashjOnly)
        assertEquals(10, b.dashjConsidered)
        assertEquals(1, b.dashjUnconfirmed)
        assertEquals(5_000_000L - 226 - 200 - 10_000 - 300 - 7_000 + 42, b.dashjOnlyNetDuffs)
    }

    @Test
    fun precedence_coinJoinThenDashPayThenUnconfirmedThenMoney() {
        // A pending mixing round with a contact output: CoinJoin wins.
        assertEquals(
            DashjOnlyGroup.COINJOIN_MIXING,
            dashjOnlyGroupOf(
                DashjTxFacts(h(1), NO_BLOCK, netDuffs = 5, coinJoinType = CoinJoinTransactionType.Mixing, dashPayContact = true)
            )
        )
        // A pending contact payment: DashPay wins over unconfirmed.
        assertEquals(
            DashjOnlyGroup.DASHPAY_CONTACT,
            dashjOnlyGroupOf(DashjTxFacts(h(2), NO_BLOCK, netDuffs = 5, dashPayContact = true))
        )
        // A pending payment: unconfirmed wins over moves-money.
        assertEquals(DashjOnlyGroup.UNCONFIRMED, dashjOnlyGroupOf(DashjTxFacts(h(3), NO_BLOCK, netDuffs = 5)))
        // A CoinJoin "send" is a real payment, not mixing.
        assertEquals(
            DashjOnlyGroup.MOVES_MONEY,
            dashjOnlyGroupOf(DashjTxFacts(h(4), 5, netDuffs = -5, coinJoinType = CoinJoinTransactionType.Send))
        )
        assertEquals(
            DashjOnlyGroup.OTHER,
            dashjOnlyGroupOf(DashjTxFacts(h(5), 5, coinJoinType = CoinJoinTransactionType.Unknown))
        )
    }

    @Test
    fun sharedTxids_areNotClassified_andDashjAboveItsLastBlockIsExcluded() {
        val txs = listOf(
            // In the SDK set: shared, whatever its facts say.
            DashjTxFacts(h(1), 10, netDuffs = 99, dashPayContact = true),
            // dashj unconfirmed, the SDK has it in a later block: still shared.
            DashjTxFacts(h(2), NO_BLOCK, netDuffs = -5),
            // Above dashj's own last block: excluded, and not SDK-only either.
            DashjTxFacts(h(3), last + 1, netDuffs = 7),
            DashjTxFacts(h(4), last, netDuffs = 8)
        )
        val sdk = mapOf(h(1) to 10, h(2) to last + 50, h(3) to last + 1)
        val b = computeParityBreakdown(txs, sdk, last)

        assertEquals(3, b.dashjConsidered)
        assertEquals(1, b.dashjAboveLastBlock)
        assertEquals(1, b.dashjUnconfirmed)
        assertEquals(2, b.shared)
        assertEquals(1, b.dashjOnly)
        assertEquals(GroupTotal(1, 8), group(b, DashjOnlyGroup.MOVES_MONEY))
        assertEquals(0, group(b, DashjOnlyGroup.DASHPAY_CONTACT).count)
        assertEquals(0, b.sdkOnly)
    }

    @Test
    fun movesMoney_isLargestAbsoluteNetFirst_andCapped() {
        val txs = (1..60).map { n ->
            // Alternate signs so the order is by |net|, not by net.
            DashjTxFacts(h(n), n, netDuffs = if (n % 2 == 0) n * 100L else -n * 100L, timeMs = 1_000L * n)
        } + DashjTxFacts(h(61), 61, netDuffs = 6_000) // ties with n=60: lower height first
        val b = computeParityBreakdown(txs, emptyMap(), last)

        assertEquals(61, group(b, DashjOnlyGroup.MOVES_MONEY).count)
        assertEquals(PARITY_BREAKDOWN_MAX_MONEY_TXS, b.movesMoney.size)
        assertEquals(listOf(6_000L, 6_000L, -5_900L, 5_800L), b.movesMoney.take(4).map { it.netDuffs })
        assertEquals(listOf(60, 61), b.movesMoney.take(2).map { it.height })
        assertEquals(h(60).toString(), b.movesMoney[0].txidHex)
        assertEquals("received", b.movesMoney[0].hint)
        assertEquals("sent", b.movesMoney[2].hint)
        // The smallest |net| fell off the end of the cap.
        assertEquals(1_200L, kotlin.math.abs(b.movesMoney.last().netDuffs))
    }

    @Test
    fun sdkOnly_isSplitAtDashjsLastBlock_withCappedLowestHeightExamples() {
        val sdk = HashMap<Sha256Hash, Int>()
        (1..25).forEach { sdk[h(100 + it)] = last - it } // at/below: heights 975..999
        sdk[h(200)] = last // exactly at the last block counts as at/below
        sdk[h(201)] = last + 1
        sdk[h(202)] = last + 500
        sdk[h(203)] = 0 // no height
        sdk[h(1)] = 5 // shared with dashj
        val b = computeParityBreakdown(listOf(DashjTxFacts(h(1), 5)), sdk, last)

        assertEquals(30, b.sdkTotal)
        assertEquals(1, b.shared)
        assertEquals(26, b.sdkOnlyAtOrBelow)
        assertEquals(2, b.sdkOnlyAbove)
        assertEquals(1, b.sdkOnlyNoHeight)
        assertEquals(29, b.sdkOnly)
        assertEquals(PARITY_BREAKDOWN_MAX_SDK_EXAMPLES, b.sdkOnlyExamples.size)
        assertEquals(975, b.sdkOnlyExamples.first().height)
        assertEquals(h(125).toString(), b.sdkOnlyExamples.first().txidHex)
        assertTrue(b.sdkOnlyExamples.all { it.height <= last })
    }

    @Test
    fun deadDashjOnlyTransactions_areCountedAcrossGroups() {
        val txs = listOf(
            DashjTxFacts(h(1), NO_BLOCK, dead = true, netDuffs = -10),
            DashjTxFacts(h(2), NO_BLOCK, dead = true, coinJoinType = CoinJoinTransactionType.Mixing),
            DashjTxFacts(h(3), NO_BLOCK, dead = true) // shared: not counted
        )
        val b = computeParityBreakdown(txs, mapOf(h(3) to 0), last)
        assertEquals(2, b.dashjOnlyDead)
        assertEquals(1, group(b, DashjOnlyGroup.UNCONFIRMED).count)
    }

    @Test
    fun hint_combinesSpecialCoinJoinSendAndDirection() {
        assertEquals("-", dashjTxHint(DashjTxFacts(h(1), 1)))
        assertEquals("received", dashjTxHint(DashjTxFacts(h(1), 1, netDuffs = 1)))
        assertEquals(
            "coinjoin-send/sent",
            dashjTxHint(DashjTxFacts(h(1), 1, netDuffs = -1, coinJoinType = CoinJoinTransactionType.Send))
        )
        assertEquals("asset_lock/sent", dashjTxHint(DashjTxFacts(h(1), 1, netDuffs = -1, special = "asset_lock")))
    }

    // ── Cadence ───────────────────────────────────────────────────────

    @Test
    fun due_hourlyWithTheProbe_dailyWhileHeld() {
        val hour = 60 * 60_000L
        val t0 = 10 * 24 * hour
        assertTrue(parityBreakdownDue(ParityBreakdownTrigger.PROBE, t0, null))
        assertFalse(parityBreakdownDue(ParityBreakdownTrigger.PROBE, t0 + hour - 1, t0))
        assertTrue(parityBreakdownDue(ParityBreakdownTrigger.PROBE, t0 + hour, t0))
        assertFalse(parityBreakdownDue(ParityBreakdownTrigger.HELD, t0 + hour, t0))
        assertFalse(parityBreakdownDue(ParityBreakdownTrigger.HELD, t0 + 24 * hour - 1, t0))
        assertTrue(parityBreakdownDue(ParityBreakdownTrigger.HELD, t0 + 24 * hour, t0))
        // A clock that went back must not lock the breakdown out for a day.
        assertTrue(parityBreakdownDue(ParityBreakdownTrigger.HELD, t0 - 1, t0))
    }

    // ── Text ──────────────────────────────────────────────────────────

    private fun sampleBreakdown(): ParityBreakdown {
        val txs = listOf(
            DashjTxFacts(h(1), 900, coinJoinType = CoinJoinTransactionType.Mixing),
            DashjTxFacts(h(2), 901, netDuffs = -10_000, coinJoinType = CoinJoinTransactionType.MixingFee),
            DashjTxFacts(h(3), 902, netDuffs = 250_000_000, timeMs = 1_700_000_000_000L),
            DashjTxFacts(
                h(4), 903, netDuffs = -1_000_000, timeMs = 1_700_000_100_000L,
                coinJoinType = CoinJoinTransactionType.Send
            ),
            DashjTxFacts(h(5), NO_BLOCK, netDuffs = -3_000),
            DashjTxFacts(h(6), 904),
            DashjTxFacts(h(7), 905)
        )
        val sdk = mapOf(h(7) to 905, h(8) to 950, h(9) to 1_200, h(10) to 0)
        return computeParityBreakdown(txs, sdk, last)
    }

    @Test
    fun logBlock_hasHeadlineGroupsMoneyListAndSdkOnly() {
        val text = parityBreakdownLog(
            sampleBreakdown(),
            computedAtMs = 1_759_665_600_123L,
            durationMs = 1_234,
            trigger = ParityBreakdownTrigger.HELD.label
        )
        val h3 = h(3).toString()
        val h4 = h(4).toString()
        val h8 = h(8).toString()
        assertEquals(
            "ParityBreakdown computedAt=2025-10-05T12:00:00Z took=1234ms trigger=held dashjLastBlockSeenHeight=1000\n" +
                "  dashj txids considered=7 (unconfirmed=1, above last block excluded=0) | sdk txids=4 | shared=1\n" +
                "  dashj-only=6 net=+248987000 (dead=0)\n" +
                "    coinjoin=2 net=-10000: mixing=1 net=0, create-denominations=0 net=0, make-collateral=0 net=0," +
                " mixing-fee=1 net=-10000, combine-dust=0 net=0\n" +
                "    dashpay-contact=0 net=0\n" +
                "    unconfirmed=1 net=-3000\n" +
                "    moves-money=2 net=+249000000\n" +
                "    other=1 net=0\n" +
                "  moves-money txids (2 of 2, largest |net| first):\n" +
                "    $h3 height=902 net=+250000000 date=2023-11-14T22:13:20Z received\n" +
                "    $h4 height=903 net=-1000000 date=2023-11-14T22:15:00Z coinjoin-send/sent\n" +
                "  sdk-only=3: at/below dashj last block=1, above=1, no height=1\n" +
                "  sdk-only at/below examples (1 of 1, lowest height first):\n" +
                "    $h8 height=950",
            text
        )
    }

    @Test
    fun logBlock_saysNoneForEmptyLists_andShowsClassifyFailures() {
        val text = parityBreakdownLog(
            computeParityBreakdown(emptyList(), emptyMap(), last),
            computedAtMs = 0L,
            durationMs = 0,
            trigger = ParityBreakdownTrigger.PROBE.label,
            classifyFailures = 3
        )
        assertTrue(text, text.startsWith("ParityBreakdown computedAt=unknown took=0ms trigger=probe"))
        assertTrue(text, text.contains("dashj-only=0 net=0 (dead=0, classify failures=3)"))
        assertTrue(text, text.contains("moves-money txids (0 of 0, largest |net| first): none"))
        assertTrue(text, text.contains("sdk-only at/below examples (0 of 0, lowest height first): none"))
    }

    @Test
    fun reportSection_carriesTheComputedTimeAndTheText() {
        assertEquals(
            "\n--- latest parity breakdown ---\nnone\n",
            parityBreakdownReportSection(null) { "unused" }
        )
        assertEquals(
            "\n--- latest parity breakdown ---\ncomputed: at-42\nParityBreakdown body\n",
            parityBreakdownReportSection(StoredParityBreakdown(42L, "ParityBreakdown body")) { "at-$it" }
        )
    }

    // ── Store ─────────────────────────────────────────────────────────

    @Test
    fun store_roundTrips_andReadsMissingOrCorruptAsNone() {
        val dir = Files.createTempDirectory("parity-breakdown").toFile()
        try {
            val file = dir.resolve("sub/breakdown.txt")
            val store = ParityBreakdownStore(file)
            assertNull(store.load())

            val stored = StoredParityBreakdown(1_759_665_600_123L, "ParityBreakdown a\n  line two")
            store.save(stored)
            assertEquals(stored, store.load())
            assertEquals(stored, ParityBreakdownStore(file).load())

            file.writeText("not-a-number\ntext")
            assertNull(store.load())
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── dashj collector ───────────────────────────────────────────────

    @Test
    fun collector_readsHeightsAndNet_andClassifiesOnlyWhatTheSdkLacks() {
        val params = TestNet3Params.get()
        val context = Context.getOrCreate(params)
        Context.propagate(context)
        val wallet = Wallet.createDeterministic(context, Script.ScriptType.P2PKH)
        val foreign = ECKey()

        fun receive(seed: Int, duffs: Long): Transaction = Transaction(params).apply {
            addInput(h(10_000 + seed), 0, ScriptBuilder.createEmpty())
            addOutput(Coin.valueOf(duffs), wallet.freshReceiveAddress())
            addOutput(Coin.valueOf(123_456_789), foreign)
        }

        val confirmed = receive(1, 250_000_000).also {
            it.getConfidence(context).setAppearedAtChainHeight(900)
            wallet.addWalletTransaction(WalletTransaction(WalletTransaction.Pool.UNSPENT, it))
        }
        val pending = receive(2, 70_000).also {
            it.getConfidence(context).confidenceType = TransactionConfidence.ConfidenceType.PENDING
            wallet.addWalletTransaction(WalletTransaction(WalletTransaction.Pool.PENDING, it))
        }
        val shared = receive(3, 5_000).also {
            it.getConfidence(context).setAppearedAtChainHeight(950)
            wallet.addWalletTransaction(WalletTransaction(WalletTransaction.Pool.UNSPENT, it))
        }
        wallet.lastBlockSeenHeight = 1_000

        val facts = collectDashjBreakdownFacts(wallet, setOf(shared.txId))
        assertEquals(1_000, facts.lastBlockSeenHeight)
        assertEquals(0, facts.classifyFailures)
        val byTxid = facts.txs.associateBy { it.txid }
        assertEquals(3, byTxid.size)

        val c = byTxid.getValue(confirmed.txId)
        assertEquals(900, c.height)
        assertEquals(250_000_000L, c.netDuffs)
        assertFalse(c.dashPayContact)
        assertEquals(CoinJoinTransactionType.None, c.coinJoinType)

        val p = byTxid.getValue(pending.txId)
        assertEquals(NO_BLOCK, p.height)
        assertEquals(70_000L, p.netDuffs)

        // Shared: its height is read, its classification is skipped.
        val s = byTxid.getValue(shared.txId)
        assertEquals(950, s.height)
        assertEquals(0L, s.netDuffs)

        val b = computeParityBreakdown(facts.txs, mapOf(shared.txId to 950), facts.lastBlockSeenHeight)
        assertEquals(1, b.shared)
        assertEquals(GroupTotal(1, 250_000_000), b.group(DashjOnlyGroup.MOVES_MONEY))
        assertEquals(GroupTotal(1, 70_000), b.group(DashjOnlyGroup.UNCONFIRMED))
    }
}
