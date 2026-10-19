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

import de.schildbach.wallet.service.platform.sdk.PaidContactAddressStore.ClaimResult
import de.schildbach.wallet.service.platform.sdk.PaidContactAddressStore.Frontier
import de.schildbach.wallet.util.WalletSaveTracker
import org.bitcoinj.wallet.FriendChainAccess.MarkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** [PaidContactAddressStore.markPaidHistoryKnown] with evidence read just now (no drop in between). */
internal fun PaidContactAddressStore.markPaidHistoryKnownNow(walletId: String): Boolean =
    markPaidHistoryKnown(walletId, paidHistoryGeneration())

class PaidContactAddressStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000_000_000_000L
    private val file: File get() = File(tmp.root, PaidContactAddressStore.FILE_NAME)
    private val saves = WalletSaveTracker()
    private val target = ContactPaymentTarget("ourId", "contactId", 7)

    private fun store() = PaidContactAddressStore(file, { now }, saves)

    /** One completed wallet save, started now. */
    private fun walletSave() = saves.saveCompleted(saves.saveStarting())

    private var nextIndex = 0

    /**
     * Records [address] at the channel's next index and settles it: dashj
     * advanced past it, then a wallet save landed.
     */
    private fun PaidContactAddressStore.recordSettled(address: String, index: Int = nextIndex++) {
        record(address, target, index)
        dashjMarked(address, MarkResult.MARKED)
        walletSave()
    }

    @Test
    fun recorded_survivesARestart() {
        store().record("yAddr1", target)

        assertEquals(setOf("yAddr1"), store().paid())
        assertEquals(listOf("yAddr1"), store().awaitingDashj("contactId", 7))
    }

    @Test
    fun unsettledEntries_neverExpire_byAgeOrByCount() {
        // PR #1608 review: a payment whose dashj save never landed must stay
        // excluded however long the app was closed and however many follow.
        val s = store()
        s.record("old", target)
        repeat(PaidContactAddressStore.MAX_ENTRIES + 3) {
            now++
            s.record("addr$it", target)
        }
        now += PaidContactAddressStore.RETENTION_MS * 10

        val paid = store().paid()
        assertTrue("old" in paid)
        assertEquals(PaidContactAddressStore.MAX_ENTRIES + 4, paid.size)
    }

    @Test
    fun settledEntries_expireAfterTheRetention_intoTheChannelFrontier() {
        val s = store()
        s.recordSettled("old")
        now += PaidContactAddressStore.RETENTION_MS / 2
        s.recordSettled("recent")
        s.record("pending", target, index = 2)
        now += PaidContactAddressStore.RETENTION_MS / 2 + 1
        s.record("trigger", target.copy(contactUserId = "other")) // a write expires what is due

        val after = store()
        assertNull("the expired entry is gone", after.isSettled("old"))
        assertEquals(true, after.isSettled("recent"))
        assertEquals(false, after.isSettled("pending"))
        // ...but what it protected is kept: the channel's frontier holds it
        assertEquals(Frontier(0, "old"), after.foldedFrontier("contactId", 7))
        assertEquals(setOf("old", "recent", "pending", "trigger"), after.paid())
        assertEquals("the live entries count too", 2, after.paidFrontier("contactId", 7))
    }

    @Test
    fun loadingExpiresNothing_onlyAWriteDoes() {
        val s = store()
        s.recordSettled("old")
        now += PaidContactAddressStore.RETENTION_MS * 2

        val loaded = store()
        assertEquals(true, loaded.isSettled("old"))
        assertNull(loaded.foldedFrontier("contactId", 7))
    }

    @Test
    fun keepsOnlyTheNewestSettledEntries_foldingTheRest() {
        val s = store()
        s.record("pending", target)
        repeat(PaidContactAddressStore.MAX_ENTRIES + 3) {
            now++
            s.recordSettled("addr$it")
        }
        s.record("trigger", target.copy(contactUserId = "other"))

        val after = store()
        assertNull(after.isSettled("addr0"))
        assertNull(after.isSettled("addr2"))
        assertEquals(true, after.isSettled("addr3"))
        assertEquals(false, after.isSettled("pending"))
        assertEquals(Frontier(2, "addr2"), after.foldedFrontier("contactId", 7))
    }

    @Test
    fun settledEntriesWithoutAnIndex_neverExpire() {
        // Without an index nothing could keep what it protected.
        val s = store()
        s.record("noIndex", target)
        s.dashjMarked("noIndex", MarkResult.MARKED)
        walletSave()
        s.record("legacy")
        now += PaidContactAddressStore.RETENTION_MS * 10
        repeat(PaidContactAddressStore.MAX_ENTRIES + 3) {
            now++
            s.recordSettled("addr$it")
        }

        val after = store()
        assertEquals(true, after.isSettled("noIndex"))
        assertEquals(false, after.isSettled("legacy"))
    }

    @Test
    fun frontier_survivesARestart_andRefusesClaimsAtOrBelowIt() {
        val s = store()
        s.recordSettled("a", index = 4)
        now += PaidContactAddressStore.RETENTION_MS + 1
        s.record("trigger", target.copy(contactUserId = "other"))

        val after = store()
        assertEquals(Frontier(4, "a"), after.foldedFrontier("contactId", 7))
        assertNull(
            "a reference proven to be another xpub has none",
            after.foldedFrontier("contactId", 8, distinctChains = setOf(7))
        )
        assertNull("another contact has none", after.foldedFrontier("other", 7))
        assertEquals(ClaimResult.ALREADY_PAID, after.claim("a", target, 9))
        assertEquals(ClaimResult.ALREADY_PAID, after.claim("lower", target, 3))
        assertEquals(ClaimResult.CLAIMED, after.claim("higher", target, 5))
        assertEquals(
            ClaimResult.CLAIMED,
            after.claim("otherChain", target.copy(accountReference = 8), 0, distinctChains = setOf(7))
        )
    }

    @Test
    fun frontier_refusesAClaimWithoutAnIndex_onItsChannelOnly() {
        // PR #1608 review: the frontier keeps only the channel's highest paid
        // address, so an earlier paid one is no longer listed; a claim whose
        // index is unknown cannot be shown to lie above it.
        val s = store()
        s.recordSettled("a", index = 4)
        now += PaidContactAddressStore.RETENTION_MS + 1
        s.record("trigger", target.copy(contactUserId = "other"))

        val after = store()
        assertEquals(Frontier(4, "a"), after.foldedFrontier("contactId", 7))
        assertEquals(ClaimResult.ALREADY_PAID, after.claim("earlierPaid", target, null))
        assertFalse("nothing recorded", after.contains("earlierPaid"))
        assertEquals(
            "a chain without a frontier still takes an unindexed claim",
            ClaimResult.CLAIMED,
            after.claim("otherChain", target.copy(accountReference = 8), null, distinctChains = setOf(7))
        )
    }

    @Test
    fun paidFrontier_includesClaimsInFlight_andARelease_lowersItAgain() {
        val s = store()
        s.recordSettled("a", index = 0)
        assertEquals(ClaimResult.CLAIMED, s.claim("b", target, 1))
        assertEquals(1, s.paidFrontier("contactId", 7))

        s.release("b")

        assertEquals(0, s.paidFrontier("contactId", 7))
        assertNull(s.paidFrontier("contactId", 8, distinctChains = setOf(7)))
        assertNull(s.paidFrontier("other", 7))
    }

    // ── one xpub, several references (PR #1608 review, round 15) ────────

    /**
     * Index 0 was claimed by a send whose outcome is unknown (it may still
     * reach the chain), index 1 was paid; both settle and fold into reference
     * 7's frontier at 1. The contact re-sends their request with the same xpub
     * under reference 8: dashj builds a new chain from index 0 and the SDK
     * rebuilds the same addresses. Address 0 must stay excluded.
     */
    private fun foldedAmbiguousAndPaid(): PaidContactAddressStore {
        val s = store()
        assertEquals(ClaimResult.CLAIMED, s.claim("ambiguous0", target, 0))
        s.sent("ambiguous0")
        assertEquals(ClaimResult.CLAIMED, s.claim("paid1", target, 1))
        s.sent("paid1")
        s.dashjMarked("ambiguous0", MarkResult.MARKED)
        s.dashjMarked("paid1", MarkResult.MARKED)
        walletSave()
        now += PaidContactAddressStore.RETENTION_MS + 1
        s.record("trigger", target.copy(contactUserId = "other"))
        return store()
    }

    @Test
    fun rotatedReference_sameXpub_inheritsTheFoldedFrontier_andRefusesTheEarlierAmbiguousAddress() {
        val after = foldedAmbiguousAndPaid()
        assertEquals(Frontier(1, "paid1"), after.foldedFrontier("contactId", 7))
        assertFalse("folded away: only the frontier address is listed", "ambiguous0" in after.paid())

        val rotated = target.copy(accountReference = 8)
        assertEquals(Frontier(1, "paid1"), after.foldedFrontier("contactId", 8))
        assertEquals(1, after.paidFrontier("contactId", 8))
        assertEquals(ClaimResult.ALREADY_PAID, after.claim("ambiguous0", rotated, 0))
        assertEquals(ClaimResult.ALREADY_PAID, after.claim("ambiguous0", rotated, null))
        assertFalse("nothing recorded", after.contains("ambiguous0"))
        assertEquals(ClaimResult.CLAIMED, after.claim("next2", rotated, 2))
    }

    @Test
    fun rotatedReference_provenAnotherXpub_doesNotInherit() {
        val after = foldedAmbiguousAndPaid()
        val rotated = target.copy(accountReference = 8)

        assertNull(after.foldedFrontier("contactId", 8, distinctChains = setOf(7)))
        assertNull(after.paidFrontier("contactId", 8, distinctChains = setOf(7)))
        assertEquals(
            "a new chain's index 0 is another address",
            ClaimResult.CLAIMED,
            after.claim("newChain0", rotated, 0, distinctChains = setOf(7))
        )
        assertEquals("the old chain keeps its own frontier", 1, after.paidFrontier("contactId", 7, setOf(8)))
    }

    @Test
    fun rotatedReference_inheritsLiveIndexedEntries_andTheHighestFrontier() {
        val s = store()
        s.recordSettled("a", index = 4)
        now += PaidContactAddressStore.RETENTION_MS + 1
        s.record("trigger", target.copy(contactUserId = "other"))
        s.record("live6", target.copy(accountReference = 8), 6)
        s.record("unindexed", target.copy(accountReference = 9))

        assertEquals(6, s.paidFrontier("contactId", 10))
        assertEquals(4, s.paidFrontier("contactId", 10, distinctChains = setOf(8)))
        assertEquals(6, s.paidFrontier("contactId", 10, distinctChains = setOf(7)))
        assertEquals(Frontier(4, "a"), s.foldedFrontier("contactId", 8))
    }

    @Test
    fun chainWitnesses_oneRecordedIndexPerOtherReference_preferringTheFoldedFrontier() {
        val s = store()
        s.recordSettled("a", index = 4)
        now += PaidContactAddressStore.RETENTION_MS + 1
        s.record("trigger", target.copy(contactUserId = "other"))
        s.record("live2", target, 2)
        s.record("live5", target.copy(accountReference = 8), 5)
        s.record("live3", target.copy(accountReference = 8), 3)
        s.record("unindexed", target.copy(accountReference = 9))

        assertEquals(
            mapOf(7 to Frontier(4, "a"), 8 to Frontier(5, "live5")),
            s.chainWitnesses("contactId", 10)
        )
        assertEquals(mapOf(8 to Frontier(5, "live5")), s.chainWitnesses("contactId", 7))
        assertEquals(emptyMap<Int, Frontier>(), s.chainWitnesses("nobody", 7))
    }

    @Test
    fun settles_onlyOnASaveStartedAfterTheDashjMark() {
        val s = store()
        s.record("a", target)
        // a save already in flight when dashj was advanced may have missed it
        val inFlight = saves.saveStarting()
        s.dashjMarked("a", MarkResult.MARKED)
        saves.saveCompleted(inFlight)
        assertFalse(store().isSettled("a") ?: true)
        assertFalse(s.isSettled("a") ?: true)

        walletSave()

        assertTrue(s.isSettled("a") ?: false)
        assertTrue("settled survives a restart", store().isSettled("a") ?: false)
        assertTrue(store().awaitingDashj("contactId", 7).isEmpty())
    }

    @Test
    fun aSaveWithoutADashjMark_settlesNothing() {
        val s = store()
        s.record("a", target)
        walletSave()
        s.dashjMarked("a", MarkResult.NO_CHAIN)
        s.dashjMarked("a", MarkResult.KEY_NOT_ON_CHAIN)
        s.dashjMarked("a", null)
        walletSave()

        assertFalse(store().isSettled("a") ?: true)
    }

    @Test
    fun alreadyBehindDashjsCurrentKey_settlesOnTheNextSave() {
        val s = store()
        s.record("a", target)
        s.dashjMarked("a", MarkResult.ALREADY_BEHIND_CURRENT)
        walletSave()

        assertTrue(store().isSettled("a") ?: false)
    }

    @Test
    fun noDashjChain_neverSettles_andIsReappliedOnEveryLookup() {
        // PR #1608 review, round 5: dashj may build the chain later, at index 0.
        val s = store()
        s.record("noChain", target)
        s.dashjMarked("noChain", MarkResult.NO_CHAIN)
        walletSave()
        now += PaidContactAddressStore.RETENTION_MS * 10

        val after = store()
        assertEquals(false, after.isSettled("noChain"))
        assertEquals(listOf("noChain"), after.awaitingDashj("contactId", 7))
        after.dashjMarked("noChain", MarkResult.NO_CHAIN)
        assertEquals(listOf("noChain"), after.awaitingDashj("contactId", 7))
    }

    @Test
    fun keyDashjCannotPlace_isNotRetriedInTheSameProcess_butStaysExcluded() {
        val s = store()
        s.record("a", target)
        s.dashjMarked("a", MarkResult.KEY_NOT_ON_CHAIN)

        assertTrue(s.awaitingDashj("contactId", 7).isEmpty())
        assertEquals(setOf("a"), s.paid())
        assertEquals(listOf("a"), store().awaitingDashj("contactId", 7))
    }

    // ── claims ──────────────────────────────────────────────────────────

    @Test
    fun claim_isExclusive_andWrittenBeforeReturning() {
        val s = store()

        assertEquals(ClaimResult.CLAIMED, s.claim("a", target))
        assertEquals("a second claim of the same address loses", ClaimResult.ALREADY_PAID, s.claim("a", target))
        assertEquals(setOf("a"), store().paid())
        assertEquals(
            "an address recorded paid cannot be claimed",
            ClaimResult.ALREADY_PAID,
            store().claim("a", target)
        )
    }

    @Test
    fun claim_concurrentClaimsOfOneAddress_exactlyOneWins() {
        val s = store()
        val winners = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(8) { Thread { if (s.claim("a", target) == ClaimResult.CLAIMED) winners.incrementAndGet() } }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertEquals(1, winners.get())
    }

    @Test
    fun release_givesTheAddressBack() {
        val s = store()
        s.claim("a", target)

        s.release("a")

        assertTrue(s.paid().isEmpty())
        assertTrue(store().paid().isEmpty())
        assertEquals(ClaimResult.CLAIMED, s.claim("a", target))
    }

    @Test
    fun aClaimInFlight_isNotReappliedToDashj_untilSent() {
        val s = store()
        s.claim("a", target)

        assertTrue(s.awaitingDashj("contactId", 7).isEmpty())
        s.sent("a")
        assertEquals(listOf("a"), s.awaitingDashj("contactId", 7))
        // a claim found on disk after a restart has an unknown outcome: re-applied
        val other = store()
        assertEquals(listOf("a"), other.awaitingDashj("contactId", 7))
    }

    @Test
    fun aWrite_neverLeavesItsTempFile() {
        store().claim("a", target)

        assertTrue(file.isFile)
        assertFalse(File(file.path + ".tmp").exists())
    }

    // ── wallet changes ──────────────────────────────────────────────────

    @Test
    fun walletChange_unsettlesEverything_andTheNewWalletMustSaveItAgain() {
        val s = store()
        s.recordSettled("a")
        s.record("b", target)
        s.dashjMarked("b", MarkResult.MARKED) // advanced on the old wallet, not yet saved

        saves.walletChanged()
        walletSave() // the new wallet's first save

        assertEquals(false, s.isSettled("a"))
        assertEquals(false, s.isSettled("b"))
        assertEquals(listOf("a", "b"), s.awaitingDashj("contactId", 7))
        now += PaidContactAddressStore.RETENTION_MS * 2
        assertEquals("unsettled on disk too", setOf("a", "b"), store().paid())

        // re-applied to the new wallet and saved: settled (and, this old,
        // folded into the frontier)
        s.dashjMarked("a", MarkResult.MARKED)
        walletSave()
        assertNull(store().isSettled("a"))
        assertEquals(Frontier(0, "a"), store().foldedFrontier("contactId", 7))
        assertEquals(false, store().isSettled("b"))
    }

    @Test
    fun walletChange_coldStore_expiredSettledEntry_isUnsettledBeforeAnythingExpires() {
        // PR #1608 review, round 6: recovery from the key backup creates the
        // app's store and reports the wallet change before any lookup. An
        // entry settled on the lost wallet long ago must not be expired by
        // that load — it is unsettled first, for the recovered wallet's dashj.
        val paidAt = now - PaidContactAddressStore.RETENTION_MS * 3
        file.writeText("$paidAt a settled contactId 7 0\n")
        val cold = store() // created, never read

        saves.walletChanged()

        assertEquals(false, cold.isSettled("a"))
        assertEquals(listOf("a"), cold.awaitingDashj("contactId", 7))
        assertNull("not folded either: it waits for the new wallet", cold.foldedFrontier("contactId", 7))
        assertEquals("unsettled on disk too", false, store().isSettled("a"))
    }

    @Test
    fun walletChange_beforeTheStoreWasLoaded_stillUnsettlesTheFile() {
        file.writeText("$now a settled contactId 7\n")
        val fresh = store() // created, never read

        saves.walletChanged()

        assertEquals(false, fresh.isSettled("a"))
        now += PaidContactAddressStore.RETENTION_MS * 2
        assertEquals(setOf("a"), store().paid())
    }

    @Test
    fun awaitingDashj_isPerChannel_andSkipsWhatDashjWasAdvancedPast() {
        val s = store()
        s.record("a", target)
        s.record("b", target)
        s.record("otherRef", target.copy(accountReference = 8))
        s.record("otherContact", target.copy(contactUserId = "someoneElse"))
        s.record("legacy")
        s.dashjMarked("b", MarkResult.MARKED)

        assertEquals(listOf("a"), s.awaitingDashj("contactId", 7))
        // a restart forgets the in-memory dashj state; "b" is retried until settled
        assertEquals(listOf("a", "b"), store().awaitingDashj("contactId", 7))
    }

    @Test
    fun earlierBuildsLines_readAsPendingWithoutAChannel() {
        file.writeText("${now - PaidContactAddressStore.RETENTION_MS * 2} yOld\n")

        val s = store()
        assertEquals(setOf("yOld"), s.paid())
        assertFalse(s.isSettled("yOld") ?: true)
        assertTrue(s.awaitingDashj("contactId", 7).isEmpty())
    }

    @Test
    fun unreadableLinesAreIgnored() {
        file.writeText(
            "garbage\n$now yGood pending c 1\n12 \n x y z\n$now yBadState maybe\n$now yBadRef pending c x\n"
        )

        assertEquals(setOf("yGood"), store().paid())
    }

    @Test
    fun noFile_isMemoryOnly() {
        val s = PaidContactAddressStore(file = null, nowMs = { now })
        s.record("a")

        assertEquals(setOf("a"), s.paid())
        assertEquals(emptySet<String>(), PaidContactAddressStore(file = null, nowMs = { now }).paid())
    }

    @Test
    fun unwritableFile_refusesTheClaim_andChangesNothing() {
        // PR #1608 review, round 6: a claim held only in memory does not
        // survive a process death — the send must not run.
        val dir = File(tmp.root, "blocked").apply { mkdirs() }
        File(dir, "x").writeText("")
        val s = PaidContactAddressStore(dir, nowMs = { now })

        assertEquals(ClaimResult.STORE_UNAVAILABLE, s.claim("a", target, 0))

        assertTrue(s.paid().isEmpty())
        assertNull(s.paidFrontier("contactId", 7))
    }

    @Test
    fun unwritableFile_keepsTheEntryInMemory() {
        // A directory where the file should be: every write fails.
        val dir = File(tmp.root, "blocked").apply { mkdirs() }
        File(dir, "x").writeText("")
        val s = PaidContactAddressStore(dir, nowMs = { now })

        s.record("a")

        assertEquals(setOf("a"), s.paid())
    }

    @Test
    fun unreadableFile_refusesEveryClaim_andIsNeverOverwritten() {
        // PR #1608 review, round 6: the exclusions it holds are unknown — even
        // an address it already holds could be claimed — so nothing is claimed.
        store().record("a", target)
        assertTrue(file.setReadable(false))
        // Root reads it anyway (container CI): the case cannot be set up there.
        assumeFalse(file.canRead())
        try {
            val s = store()
            assertEquals(ClaimResult.STORE_UNAVAILABLE, s.claim("a", target))
            assertEquals(ClaimResult.STORE_UNAVAILABLE, s.claim("b", target))
            assertTrue(s.paid().isEmpty())
        } finally {
            file.setReadable(true)
        }

        assertEquals("the file still holds what it held", setOf("a"), store().paid())
    }

    @Test
    fun frontierAndIndexedLines_roundTrip_andMalformedOnesAreIgnored() {
        file.writeText(
            "frontier contactId 7 3 yF\n" +
                "frontier contactId 7 x yBad\n" +
                "frontier contactId 7 -1 yNeg\n" +
                "$now yA pending contactId 7 4\n" +
                "$now yB pending contactId 7 nope\n"
        )

        val s = store()
        assertEquals(Frontier(3, "yF"), s.foldedFrontier("contactId", 7))
        assertEquals(setOf("yF", "yA"), s.paid())
        assertEquals(4, s.paidFrontier("contactId", 7))
        s.record("yC", target, 5)
        val after = store()
        assertEquals(Frontier(3, "yF"), after.foldedFrontier("contactId", 7))
        assertEquals(5, after.paidFrontier("contactId", 7))
    }

    // ── paid-address history (PR #1608 review, round 9) ─────────────────

    @Test
    fun paidHistory_unknownWithoutAFile_orInAFileWithoutTheRecord() {
        // New install, cleared data, a new device: no file at all.
        assertFalse(store().paidHistoryKnown("w1"))
        // A file written before the record existed.
        file.writeText("$now yA pending contactId 7 0\n")
        assertFalse(store().paidHistoryKnown("w1"))
    }

    @Test
    fun paidHistory_markedKnown_survivesARestart_forThatWalletOnly() {
        val s = store()
        s.record("yA", target, 0)

        s.markPaidHistoryKnownNow("w1")

        assertTrue(s.paidHistoryKnown("w1"))
        assertFalse("another SDK wallet's history is not known", s.paidHistoryKnown("w2"))
        val restarted = store()
        assertTrue(restarted.paidHistoryKnown("w1"))
        assertEquals("the entries are kept with it", setOf("yA"), restarted.paid())
    }

    @Test
    fun paidHistory_isUnknownAgainAfterAWalletChange_onDiskToo() {
        val s = store()
        s.markPaidHistoryKnownNow("w1")

        saves.walletChanged()

        assertFalse(s.paidHistoryKnown("w1"))
        assertFalse("written, though the store holds no entry", store().paidHistoryKnown("w1"))
    }

    @Test
    fun paidHistory_unreadableFile_isNeverKnown() {
        store().markPaidHistoryKnownNow("w1")
        assertTrue(file.setReadable(false))
        assumeFalse(file.canRead())
        try {
            assertFalse(store().paidHistoryKnown("w1"))
        } finally {
            file.setReadable(true)
        }
    }

    @Test
    fun paidHistory_unwritableFile_isKnownForThisProcessOnly() {
        val dir = File(tmp.root, "blocked").apply { mkdirs() }
        File(dir, "x").writeText("")
        val s = PaidContactAddressStore(dir, nowMs = { now })

        s.markPaidHistoryKnownNow("w1")

        assertTrue(s.paidHistoryKnown("w1"))
        assertFalse(PaidContactAddressStore(dir, nowMs = { now }).paidHistoryKnown("w1"))
    }

    // ── the SDK's transaction store rebuilt (PR #1608 review, round 12) ──

    @Test
    fun forgetPaidHistory_dropsTheMarkerOnDisk_andKeepsThePaidAddresses() {
        val s = store()
        s.record("yA", target, 0)
        s.markPaidHistoryKnownNow("w1")

        assertTrue(s.forgetPaidHistory("w1"))

        assertFalse(s.paidHistoryKnown("w1"))
        val restarted = store()
        assertFalse("the re-created wallet (same id) is not vouched for", restarted.paidHistoryKnown("w1"))
        assertEquals(setOf("yA"), restarted.paid())
        // Known again only once the rebuilt store has been processed through the tip.
        s.markPaidHistoryKnownNow("w1")
        assertTrue(store().paidHistoryKnown("w1"))
    }

    @Test
    fun forgetPaidHistory_ofAnotherWallet_keepsTheMarker() {
        val s = store()
        s.markPaidHistoryKnownNow("w1")

        assertTrue(s.forgetPaidHistory("w2"))

        assertTrue(store().paidHistoryKnown("w1"))
    }

    @Test
    fun forgetPaidHistory_unreadableFile_refuses() {
        store().markPaidHistoryKnownNow("w1")
        assertTrue(file.setReadable(false))
        assumeFalse(file.canRead())
        try {
            assertFalse("the file may hold the marker for the next process", store().forgetPaidHistory("w1"))
        } finally {
            file.setReadable(true)
        }
    }

    @Test
    fun forgetPaidHistory_unwritableFile_refuses_butForgetsInMemory() {
        val dir = File(tmp.root, "locked").apply { mkdirs() }
        val locked = File(dir, PaidContactAddressStore.FILE_NAME)
        PaidContactAddressStore(locked, nowMs = { now }).markPaidHistoryKnownNow("w1")
        val s = PaidContactAddressStore(locked, nowMs = { now })
        assertTrue(s.paidHistoryKnown("w1"))
        assertTrue(dir.setWritable(false))
        try {
            assumeFalse(File(dir, "probe").let { runCatching { it.createNewFile() }.getOrDefault(false) })

            assertFalse(s.forgetPaidHistory("w1"))

            assertFalse(s.paidHistoryKnown("w1"))
            // A retry in the same process: the marker is gone from memory but
            // still on disk, so it must write again rather than report a drop.
            assertFalse("the write still fails", s.forgetPaidHistory("w1"))
            assertTrue("still on disk", PaidContactAddressStore(locked, nowMs = { now }).paidHistoryKnown("w1"))
        } finally {
            dir.setWritable(true)
        }
        assertTrue("still on disk", PaidContactAddressStore(locked, nowMs = { now }).paidHistoryKnown("w1"))

        // Once the write succeeds, the retry reports the drop, and it is on disk.
        assertTrue(s.forgetPaidHistory("w1"))
        assertFalse(PaidContactAddressStore(locked, nowMs = { now }).paidHistoryKnown("w1"))
        assertTrue("nothing left to write", s.forgetPaidHistory("w1"))
    }

    @Test
    fun markPaidHistoryKnown_withEvidenceReadBeforeADrop_isRefused() {
        // A lookup reads the generation, then its evidence; the transaction
        // store is reset (marker dropped before and after deleting it) before
        // the lookup records the marker.
        val s = store()
        val generation = s.paidHistoryGeneration()
        assertTrue(s.forgetPaidHistory("w1"))
        assertTrue(s.forgetPaidHistory("w1"))

        assertFalse(s.markPaidHistoryKnown("w1", generation))

        assertFalse(s.paidHistoryKnown("w1"))
        assertFalse(store().paidHistoryKnown("w1"))
        // Evidence read after the reset is recorded.
        assertTrue(s.markPaidHistoryKnown("w1", s.paidHistoryGeneration()))
        assertTrue(store().paidHistoryKnown("w1"))
    }

    @Test
    fun markPaidHistoryKnown_duringAnInvalidation_isRefused_evenWithEvidenceReadAfterTheFirstDrop() {
        // PR #1608 review, round 14: a lookup that starts after the first
        // drop, while the old store is still there, reads the current
        // generation. It must not record the marker until the deletion ends.
        val s = store()
        s.markPaidHistoryKnownNow("w1")
        assertTrue(s.beginHistoryInvalidation("w1"))
        assertFalse(store().paidHistoryKnown("w1"))

        assertFalse(s.markPaidHistoryKnown("w1", s.paidHistoryGeneration()))
        assertFalse(s.paidHistoryKnown("w1"))
        assertFalse("not on disk for a process that dies now", store().paidHistoryKnown("w1"))

        assertTrue(s.endHistoryInvalidation("w1"))
        assertTrue("reopened", s.markPaidHistoryKnown("w1", s.paidHistoryGeneration()))
        assertTrue(store().paidHistoryKnown("w1"))
    }

    @Test
    fun endHistoryInvalidation_dropsAMarkerRecordedJustBeforeTheBegin() {
        val s = store()
        val generation = s.paidHistoryGeneration()
        assertTrue(s.markPaidHistoryKnown("w1", generation))
        assertTrue(s.beginHistoryInvalidation("w1"))
        assertTrue(s.endHistoryInvalidation("w1"))

        assertFalse(s.paidHistoryKnown("w1"))
        assertFalse(store().paidHistoryKnown("w1"))
    }

    @Test
    fun historyInvalidation_holdsUntilEveryBeginHasEnded_andAnUnpairedEndCannotOpenTheNext() {
        val s = store()
        // An end whose begin never ran (its caller was cancelled first).
        assertTrue(s.endHistoryInvalidation("w1"))
        assertTrue(s.beginHistoryInvalidation("w1"))
        assertFalse("the unpaired end did not pre-open this one", s.markPaidHistoryKnown("w1", s.paidHistoryGeneration()))

        assertTrue(s.beginHistoryInvalidation("w1"))
        assertTrue(s.endHistoryInvalidation("w1"))
        assertFalse("one still in progress", s.markPaidHistoryKnown("w1", s.paidHistoryGeneration()))
        assertTrue(s.endHistoryInvalidation("w1"))
        assertTrue(s.markPaidHistoryKnown("w1", s.paidHistoryGeneration()))
    }

    @Test
    fun historyInvalidation_whoseDropsFail_stillReopensAtItsEnd() {
        val dir = File(tmp.root, "locked").apply { mkdirs() }
        val locked = File(dir, PaidContactAddressStore.FILE_NAME)
        PaidContactAddressStore(locked, nowMs = { now }).markPaidHistoryKnownNow("w1")
        val s = PaidContactAddressStore(locked, nowMs = { now })
        assertTrue(dir.setWritable(false))
        try {
            assumeFalse(File(dir, "probe").let { runCatching { it.createNewFile() }.getOrDefault(false) })

            assertFalse("the deletion must be refused", s.beginHistoryInvalidation("w1"))
            assertFalse(s.endHistoryInvalidation("w1"))
        } finally {
            dir.setWritable(true)
        }
        assertTrue("reopened", s.markPaidHistoryKnown("w1", s.paidHistoryGeneration()))
        assertTrue(s.paidHistoryKnown("w1"))
    }

    @Test
    fun markPaidHistoryKnown_withEvidenceReadBeforeAWalletChange_isRefused() {
        val saves = WalletSaveTracker()
        val s = PaidContactAddressStore(file, { now }, saves)
        val generation = s.paidHistoryGeneration()
        saves.walletChanged()

        assertFalse(s.markPaidHistoryKnown("w1", generation))
        assertFalse(s.paidHistoryKnown("w1"))
    }

    @Test
    fun paidHistoryLine_isNotReadAsAnEntryOrFrontier() {
        val s = store()
        s.markPaidHistoryKnownNow("w1")
        s.record("yA", target, 2)

        val restarted = store()
        assertEquals(setOf("yA"), restarted.paid())
        assertNull(restarted.foldedFrontier("contactId", 7))
        assertTrue(file.readLines().first() == "history w1")
    }

    @Test
    fun paidOnChain_recordedLikeAPayment_survivesARestart_andRaisesTheFrontier() {
        val s = store()
        s.record("yMine", target, 0)

        s.recordPaidOnChain(target, linkedMapOf("yMine" to 9, "yChain1" to 1, "yChain2" to 2))

        val restarted = store()
        assertEquals(setOf("yMine", "yChain1", "yChain2"), restarted.paid())
        assertEquals(2, restarted.paidFrontier("contactId", 7))
        assertEquals(listOf("yMine", "yChain1", "yChain2"), restarted.awaitingDashj("contactId", 7))
        assertEquals(ClaimResult.ALREADY_PAID, restarted.claim("yChain2", target, 2))
        // settles like a sent payment's entry
        restarted.dashjMarked("yChain1", MarkResult.MARKED)
        saves.saveCompleted(saves.saveStarting())
        assertEquals(true, restarted.isSettled("yChain1"))
    }
}
