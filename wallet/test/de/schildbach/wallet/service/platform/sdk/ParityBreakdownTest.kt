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

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    /** An SDK entry with a height but no stored net — the shape these tests had before values were compared. */
    private fun sdkAt(height: Int): SdkTxFacts = SdkTxFacts(height, null)

    /** An SDK entry with both a height and a stored net. */
    private fun sdkAt(height: Int, net: Long): SdkTxFacts = SdkTxFacts(height, net)

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
        val sdk = mapOf(h(1) to sdkAt(10), h(2) to sdkAt(last + 50), h(3) to sdkAt(last + 1))
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
        val sdk = HashMap<Sha256Hash, SdkTxFacts>()
        (1..25).forEach { sdk[h(100 + it)] = sdkAt(last - it) } // at/below: heights 975..999
        sdk[h(200)] = sdkAt(last) // exactly at the last block counts as at/below
        sdk[h(201)] = sdkAt(last + 1)
        sdk[h(202)] = sdkAt(last + 500)
        sdk[h(203)] = sdkAt(0) // no height
        sdk[h(1)] = sdkAt(5) // shared with dashj
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
        val b = computeParityBreakdown(txs, mapOf(h(3) to sdkAt(0)), last)
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
    fun due_hourlyForTheDiagnosticRun() {
        val hour = 60 * 60_000L
        val t0 = 10 * 24 * hour
        assertTrue(parityBreakdownDue(t0, null))
        assertFalse(parityBreakdownDue(t0 + hour - 1, t0))
        assertTrue(parityBreakdownDue(t0 + hour, t0))
        // A clock that went back must not lock the run out for an hour.
        assertTrue(parityBreakdownDue(t0 - 1, t0))
    }

    @Test
    fun report_getsABreakdownOnceCommitted_orWithTheDiagnosticBeforeTheCutover() {
        assertTrue(parityBreakdownAppliesToReport(cutoverCommitted = true, dashjDiagnosticEnabled = false))
        assertTrue(parityBreakdownAppliesToReport(cutoverCommitted = true, dashjDiagnosticEnabled = true))
        assertTrue(parityBreakdownAppliesToReport(cutoverCommitted = false, dashjDiagnosticEnabled = true))
        assertFalse(parityBreakdownAppliesToReport(cutoverCommitted = false, dashjDiagnosticEnabled = false))
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
        val sdk = mapOf(h(7) to sdkAt(905), h(8) to sdkAt(950), h(9) to sdkAt(1_200), h(10) to sdkAt(0))
        return computeParityBreakdown(txs, sdk, last)
    }

    @Test
    fun logBlock_hasHeadlineGroupsMoneyListAndSdkOnly() {
        val text = parityBreakdownLog(
            sampleBreakdown(),
            computedAtMs = 1_759_665_600_123L,
            durationMs = 1_234,
            trigger = ParityBreakdownTrigger.REPORT.label
        )
        val h3 = h(3).toString()
        val h4 = h(4).toString()
        val h8 = h(8).toString()
        assertEquals(
            "ParityBreakdown computedAt=2025-10-05T12:00:00Z took=1234ms trigger=report dashjLastBlockSeenHeight=1000\n" +
                "  dashj txids considered=7 (unconfirmed=1, above last block excluded=0) | sdk txids=4 | shared=1\n" +
                "  dashj-only=6 net=+248987000 (dead=0)\n" +
                "    coinjoin=2 net=-10000: mixing=1 net=0, create-denominations=0 net=0, make-collateral=0 net=0," +
                " mixing-fee=1 net=-10000, combine-dust=0 net=0\n" +
                "    dashpay-contact=0 net=0\n" +
                "    unconfirmed=1 net=-3000\n" +
                "    moves-money=2 net=+249000000\n" +
                "    other=1 net=0\n" +
                "  value: compared=0 differs=0\n" +
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

    private val walletA = "ab".repeat(32)

    private val stored = StoredParityBreakdown(walletA, 42L, "ParityBreakdown body")

    @Test
    fun reportSection_fresh_carriesTheComputedTimeAndTheText() {
        assertEquals(
            "\n--- latest parity breakdown ---\ncomputed: at-42 (fresh, for this report)\nParityBreakdown body\n",
            parityBreakdownReportSection(ReportParityBreakdown(stored, null)) { "at-$it" }
        )
    }

    @Test
    fun reportSection_fallback_labelsTheStoredResultWithItsTimeAndTheReason() {
        assertEquals(
            "\n--- latest parity breakdown ---\n" +
                "not refreshed: SDK not synced — showing the last stored result\n" +
                "computed: at-42\nParityBreakdown body\n",
            parityBreakdownReportSection(ReportParityBreakdown(stored, "SDK not synced")) { "at-$it" }
        )
    }

    @Test
    fun reportSection_saysNoneWithTheReason_whenNothingIsStored_orItDoesNotApply() {
        assertEquals(
            "\n--- latest parity breakdown ---\nnone (timed out after 30s)\n",
            parityBreakdownReportSection(ReportParityBreakdown(null, "timed out after 30s")) { "unused" }
        )
        assertEquals(
            "\n--- latest parity breakdown ---\nnone (${ReportParityBreakdown.NOT_APPLICABLE_REASON})\n",
            parityBreakdownReportSection(ReportParityBreakdown.NOT_APPLICABLE) { "unused" }
        )
    }

    @Test
    fun reportSection_notShared_saysOmitted_andIsNotApplicable() {
        assertEquals(
            "\n--- latest parity breakdown ---\nomitted (application log not shared)\n",
            parityBreakdownReportSection(ReportParityBreakdown.NOT_SHARED) { "unused" }
        )
        assertFalse(ReportParityBreakdown.NOT_SHARED.applicable)
        // Even if a result were attached to it, an omitted breakdown shows none of it.
        val withText = ReportParityBreakdown.NOT_SHARED.copy(stored = stored)
        assertFalse(parityBreakdownReportSection(withText) { "unused" }.contains("ParityBreakdown body"))
    }

    // ── SDK still processing ──────────────────────────────────────────

    @Test
    fun sdkStillProcessing_untilTheCommittedWalletHeightReachesDashj() {
        assertEquals(
            "SDK still processing transactions (wallet height 990 < dashj 1000)",
            sdkStillProcessingReason(sdkWalletHeight = 990, dashjLastBlockSeenHeight = 1_000)
        )
        assertEquals(
            "SDK still processing transactions (wallet height unknown, dashj 1000)",
            sdkStillProcessingReason(sdkWalletHeight = 0, dashjLastBlockSeenHeight = 1_000)
        )
        assertNull(sdkStillProcessingReason(sdkWalletHeight = 1_000, dashjLastBlockSeenHeight = 1_000))
        // A held dashj far below the SDK: nothing to wait for.
        assertNull(sdkStillProcessingReason(sdkWalletHeight = 1_400_000, dashjLastBlockSeenHeight = 1_000))
        // dashj has seen no block. This is now REFUSED rather than waved
        // through: a breakdown computed against an empty dashj wallet reports
        // dashj-only=0 / sdk-only=0, which reads as perfect parity and means
        // nothing at all.
        assertNotNull(sdkStillProcessingReason(sdkWalletHeight = 0, dashjLastBlockSeenHeight = -1))
    }

    // ── Single flight ─────────────────────────────────────────────────

    @Test
    fun inFlight_includesAPublishedLazyRunThatHasNotStarted() = runBlocking {
        assertNull(inFlightParityBreakdown<Unit>(null))
        val lazy = async(start = CoroutineStart.LAZY) { }
        // The review's gap: published but not started reads as inactive.
        assertFalse(lazy.isActive)
        assertSame(lazy, inFlightParityBreakdown(lazy))
        lazy.start()
        lazy.await()
        assertNull(inFlightParityBreakdown(lazy))
        val cancelled = async(start = CoroutineStart.LAZY) { }.also { it.cancel() }
        assertNull(inFlightParityBreakdown(cancelled))
    }

    // ── Store ─────────────────────────────────────────────────────────

    @Test
    fun store_roundTrips_andReadsMissingOrCorruptAsNone() {
        val dir = Files.createTempDirectory("parity-breakdown").toFile()
        try {
            val file = dir.resolve("sub/breakdown.txt")
            val store = ParityBreakdownStore(file)
            assertNull(store.load())

            val stored = StoredParityBreakdown(walletA, 1_759_665_600_123L, "ParityBreakdown a\n  line two")
            store.save(stored)
            assertEquals(stored, store.load())
            assertEquals(stored, ParityBreakdownStore(file).load())
            assertTrue(file.readText().startsWith("1759665600123 $walletA\n"))

            file.writeText("not-a-number $walletA\ntext")
            assertNull(store.load())
            // The earlier format, without a wallet id: no wallet to match it to.
            file.writeText("1759665600123\ntext")
            assertNull(store.load())

            store.save(stored)
            store.clear()
            assertFalse(file.exists())
            assertFalse(dir.resolve("sub/breakdown.txt.tmp").exists())
            assertNull(store.load())
            store.clear() // nothing there: still fine
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
        assertTrue(c.valueKnown)
        assertFalse(c.dashPayContact)
        assertEquals(CoinJoinTransactionType.None, c.coinJoinType)

        val p = byTxid.getValue(pending.txId)
        assertEquals(NO_BLOCK, p.height)
        assertEquals(70_000L, p.netDuffs)

        // Shared: its height AND its value are read — the value is half of the
        // parity comparison. Only the CLASSIFICATION is skipped, because those
        // fields describe dashj-only rows.
        val s = byTxid.getValue(shared.txId)
        assertEquals(950, s.height)
        assertEquals(5_000L, s.netDuffs)
        assertTrue(s.valueKnown)
        assertFalse(s.dashPayContact)
        assertEquals(CoinJoinTransactionType.None, s.coinJoinType)

        val b = computeParityBreakdown(facts.txs, mapOf(shared.txId to sdkAt(950)), facts.lastBlockSeenHeight)
        assertEquals(1, b.shared)
        assertEquals(GroupTotal(1, 250_000_000), b.group(DashjOnlyGroup.MOVES_MONEY))
        assertEquals(GroupTotal(1, 70_000), b.group(DashjOnlyGroup.UNCONFIRMED))
    }

    // ── shared-txid VALUE comparison ──────────────────────────────────────
    //
    // The breakdown used to count a shared txid and drop it, so two engines
    // that disagreed about the money still reported a clean sheet. These pin
    // the comparison, and — just as important — pin the cases it must NOT
    // report, because a check that cries wolf on an ordinary wallet is a check
    // that gets ignored.

    @Test
    fun sharedTxid_withDifferentValues_isReported() {
        val dashj = listOf(DashjTxFacts(h(1), 900, netDuffs = -18_669L, valueKnown = true))
        val b = computeParityBreakdown(dashj, mapOf(h(1) to sdkAt(900, 7_781_409L)), last)
        assertEquals(1, b.shared)
        assertEquals(1, b.valueCompared)
        assertEquals(1, b.valueDiffers)
        // delta is SDK minus dashj: the SDK stores 7,800,078 more than dashj computes.
        assertEquals(7_800_078L, b.valueDeltaDuffs)
        val d = b.valueDiffExamples.single()
        assertEquals(-18_669L, d.dashjDuffs)
        assertEquals(7_781_409L, d.sdkDuffs)
        assertEquals(7_800_078L, d.deltaDuffs)
    }

    @Test
    fun sharedTxid_withEqualValues_isComparedAndNotReported() {
        val dashj = listOf(DashjTxFacts(h(1), 900, netDuffs = -18_669L, valueKnown = true))
        val b = computeParityBreakdown(dashj, mapOf(h(1) to sdkAt(900, -18_669L)), last)
        assertEquals(1, b.valueCompared)
        assertEquals(0, b.valueDiffers)
        assertEquals(0L, b.valueDeltaDuffs)
        assertTrue(b.valueDiffExamples.isEmpty())
    }

    @Test
    fun zeroDiffers_isDistinguishableFromNothingCompared() {
        // The whole point of reporting `compared` next to `differs`: a run that
        // compared nothing must not read like a run where both engines agreed.
        val agreed = computeParityBreakdown(
            listOf(DashjTxFacts(h(1), 900, netDuffs = 5L, valueKnown = true)),
            mapOf(h(1) to sdkAt(900, 5L)), last
        )
        val comparedNothing = computeParityBreakdown(
            listOf(DashjTxFacts(h(1), 900, netDuffs = 5L, valueKnown = false)),
            mapOf(h(1) to sdkAt(900, 5L)), last
        )
        assertEquals(0, agreed.valueDiffers)
        assertEquals(0, comparedNothing.valueDiffers)
        assertEquals(1, agreed.valueCompared)
        assertEquals(0, comparedNothing.valueCompared)
    }

    @Test
    fun sharedTxid_withUnknownSdkNet_isNotCompared() {
        // No `transactions` row means no stored net. Unknown is not zero: a
        // wallet mid-build would otherwise report every such row as a mismatch.
        val dashj = listOf(DashjTxFacts(h(1), 900, netDuffs = -18_669L, valueKnown = true))
        val b = computeParityBreakdown(dashj, mapOf(h(1) to sdkAt(900)), last)
        assertEquals(1, b.shared)
        assertEquals(0, b.valueCompared)
        assertEquals(0, b.valueDiffers)
    }

    @Test
    fun sharedTxid_whoseDashjValueThrew_isNotCompared() {
        // collectDashjBreakdownFacts leaves valueKnown=false when getValue throws.
        val dashj = listOf(DashjTxFacts(h(1), 900, netDuffs = 0L, valueKnown = false))
        val b = computeParityBreakdown(dashj, mapOf(h(1) to sdkAt(900, 7_781_409L)), last)
        assertEquals(0, b.valueCompared)
        assertEquals(0, b.valueDiffers)
    }

    @Test
    fun sharedUnconfirmedOrDeadTx_isNotCompared() {
        val unconfirmed = DashjTxFacts(h(1), NO_BLOCK, netDuffs = 5L, valueKnown = true)
        val deadTx = DashjTxFacts(h(2), 900, dead = true, netDuffs = 5L, valueKnown = true)
        val b = computeParityBreakdown(
            listOf(unconfirmed, deadTx),
            mapOf(h(1) to sdkAt(0, 99L), h(2) to sdkAt(900, 99L)),
            last
        )
        assertEquals(2, b.shared)
        assertEquals(0, b.valueCompared)
        assertEquals(0, b.valueDiffers)
    }

    @Test
    fun sharedTxAboveDashjLastBlock_isNotCompared() {
        // dashj cannot be expected to agree about a block it has not seen.
        val dashj = listOf(DashjTxFacts(h(1), last + 10, netDuffs = 5L, valueKnown = true))
        val b = computeParityBreakdown(dashj, mapOf(h(1) to sdkAt(last + 10, 99L)), last)
        assertEquals(0, b.shared)
        assertEquals(0, b.valueCompared)
        assertEquals(0, b.valueDiffers)
    }

    @Test
    fun valueDiffs_areOrderedByLargestAbsoluteDeltaAndCapped() {
        val dashj = (1..5).map { DashjTxFacts(h(it), 900 + it, netDuffs = 0L, valueKnown = true) }
        val sdk = mapOf(
            h(1) to sdkAt(901, 10L), h(2) to sdkAt(902, -5_000L), h(3) to sdkAt(903, 300L),
            h(4) to sdkAt(904, 7L), h(5) to sdkAt(905, 40L)
        )
        val b = computeParityBreakdown(dashj, sdk, last, maxValueDiffs = 3)
        assertEquals(5, b.valueDiffers)
        assertEquals(3, b.valueDiffExamples.size)
        assertEquals(listOf(-5_000L, 300L, 40L), b.valueDiffExamples.map { it.deltaDuffs })
        // every differing row still counts toward the total, not just the shown ones
        assertEquals(10L - 5_000L + 300L + 7L + 40L, b.valueDeltaDuffs)
    }

    @Test
    fun theRealDefect_isCaught() {
        // Regression guard, built from the measured mainnet case: a
        // self-consolidation of 125 CoinJoin denominations, typed Standard and
        // in no mixing group, that a restored SDK store recorded income-only.
        // dashj, holding the same keys, computes the fee correctly. Before this
        // change the breakdown reported a clean sheet on exactly this input.
        val txid = h(0x8add)
        val dashj = listOf(DashjTxFacts(txid, 2_072_687, netDuffs = -18_669L, valueKnown = true))
        val sdk = mapOf(txid to sdkAt(2_072_687, 7_781_409L))
        val b = computeParityBreakdown(dashj, sdk, 2_551_341)
        assertEquals(0, b.dashjOnly)
        assertEquals(0, b.sdkOnly)
        assertEquals(1, b.shared)
        assertEquals("the defect must not hide behind a clean set comparison", 1, b.valueDiffers)
        assertEquals(7_800_078L, b.valueDeltaDuffs)
        val text = parityBreakdownLog(b, 0L, 1L, "test")
        assertTrue("the report must name it", text.contains("differs=1"))
        assertTrue(text.contains("delta=+7800078"))
    }

    @Test
    fun aCleanWalletReportsComparedButNoDifferences() {
        val text = parityBreakdownLog(
            computeParityBreakdown(
                listOf(DashjTxFacts(h(1), 900, netDuffs = -1L, valueKnown = true)),
                mapOf(h(1) to sdkAt(900, -1L)), last
            ), 0L, 1L, "test"
        )
        assertTrue(text.contains("value: compared=1 differs=0"))
        assertFalse("no delta section when nothing differs", text.contains("delta="))
    }

    @Test
    fun dashjThatHasSeenNoBlocks_isRefusedRatherThanReported() {
        // A verdict computed against an empty dashj wallet reads as perfect
        // parity and means nothing. It must be refused, not published.
        assertNotNull(sdkStillProcessingReason(1_000L, 0))
        assertNotNull(sdkStillProcessingReason(1_000L, -1))
        assertNull(sdkStillProcessingReason(1_000L, 900))
    }
}
