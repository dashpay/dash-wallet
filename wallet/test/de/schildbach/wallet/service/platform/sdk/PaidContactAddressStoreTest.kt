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

import de.schildbach.wallet.util.WalletSaveTracker
import org.bitcoinj.wallet.FriendChainAccess.MarkResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private val saves = WalletSaveTracker()
    private val target = ContactPaymentTarget("ourId", "contactId", 7)

    private fun store() = PaidContactAddressStore(file, { now }, saves)

    /** One completed wallet save, started now. */
    private fun walletSave() = saves.saveCompleted(saves.saveStarting())

    /** Records [address] and settles it: dashj advanced past it, then a wallet save landed. */
    private fun PaidContactAddressStore.recordSettled(address: String) {
        record(address, target)
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
    fun settledEntries_expireAfterTheRetention() {
        val s = store()
        s.recordSettled("old")
        now += PaidContactAddressStore.RETENTION_MS / 2
        s.recordSettled("recent")
        s.record("pending", target)
        now += PaidContactAddressStore.RETENTION_MS / 2 + 1

        assertEquals(setOf("recent", "pending"), store().paid())
    }

    @Test
    fun keepsOnlyTheNewestSettledEntries() {
        val s = store()
        s.record("pending", target)
        repeat(PaidContactAddressStore.MAX_ENTRIES + 3) {
            now++
            s.recordSettled("addr$it")
        }

        val paid = store().paid()
        assertEquals(PaidContactAddressStore.MAX_ENTRIES + 1, paid.size)
        assertTrue("pending" in paid)
        assertTrue("addr0" !in paid)
        assertTrue("addr${PaidContactAddressStore.MAX_ENTRIES + 2}" in paid)
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
    fun sdkUsedFlag_settlesOnlyWhenDashjHasNoChain() {
        val s = store()
        s.record("dashjHasIt", target)
        s.record("noChain", target)
        s.record("unknown", target)
        s.dashjMarked("dashjHasIt", MarkResult.MARKED)
        s.dashjMarked("noChain", MarkResult.NO_CHAIN)

        s.sdkMarkedUsed(listOf("dashjHasIt", "noChain", "unknown"))

        val after = store()
        assertTrue(after.isSettled("noChain") ?: false)
        assertFalse("dashj still has to save it", after.isSettled("dashjHasIt") ?: true)
        assertFalse("dashj could still serve it", after.isSettled("unknown") ?: true)
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
    fun unwritableFile_keepsTheEntryInMemory() {
        // A directory where the file should be: every write fails.
        val dir = File(tmp.root, "blocked").apply { mkdirs() }
        File(dir, "x").writeText("")
        val s = PaidContactAddressStore(dir, nowMs = { now })

        s.record("a")

        assertEquals(setOf("a"), s.paid())
    }
}
