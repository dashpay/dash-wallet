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

import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Address
import org.bitcoinj.core.ECKey
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.wallet.FriendChainAccess
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContactPaymentAddressProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val params = TestNet3Params.get()

    private val ourUserId = Identifier.from(ByteArray(32) { 1 }).toString()
    private val contactUserId = Identifier.from(ByteArray(32) { 2 }).toString()

    /** The accountReference of the request the send screen selected. */
    private val selectedReference = 0x1234

    private val target = ContactPaymentTarget(ourUserId, contactUserId, selectedReference)

    private fun newAddress(): Address = Address.fromKey(params, ECKey())

    private val sdkPool = List(4) { newAddress() }
    private val dashjAddress = newAddress()

    private fun pool(vararg used: Boolean) = used.mapIndexed { i, isUsed ->
        SdkContactPoolAddress(sdkPool[i].toBase58(), i, isUsed, poolTypeTag = 2)
    }

    private fun account(
        externalAccountReference: Int? = selectedReference,
        paymentChannelBroken: Boolean = false,
        incomingRequestFound: Boolean = true,
        addresses: List<SdkContactPoolAddress>? = pool(true, false, false)
    ) = SdkContactSendingAccount(incomingRequestFound, paymentChannelBroken, externalAccountReference, addresses)

    // ── source selection ────────────────────────────────────────────────

    private class Harness(
        var cutover: Boolean,
        var sdkAccount: SdkContactSendingAccount?,
        val dashjAddress: Address?,
        val params: org.bitcoinj.core.NetworkParameters,
        /** dashj's persisted sending cursor for the contact; null = no dashj chain. */
        var dashjCursor: Int? = null,
        paidFile: File? = null
    ) {
        var sdkReads = 0
        val sdkReadIds = mutableListOf<Pair<ByteArray, ByteArray>>()
        val dashjReads = mutableListOf<Pair<String, Int>>()
        val marks = mutableListOf<Triple<String, Int, Address>>()
        var markResult: FriendChainAccess.MarkResult? = FriendChainAccess.MarkResult.MARKED
        var markThrows = false

        val provider = ContactPaymentAddressProvider(
            cutoverCommitted = { cutover },
            sdkAccountReader = { ourId, contactId ->
                sdkReads++
                sdkReadIds += ourId to contactId
                sdkAccount
            },
            dashjNextAddress = { userId, ref ->
                dashjReads += userId to ref
                dashjAddress
            },
            dashjMarkUsed = { userId, ref, address ->
                if (markThrows) throw IllegalStateException("boom")
                marks += Triple(userId, ref, address)
                markResult
            },
            params = params,
            dashjSendingCursor = { _, _ -> dashjCursor },
            paidAddresses = PaidContactAddressStore(paidFile)
        )
    }

    private fun harness(
        cutover: Boolean = true,
        sdkAccount: SdkContactSendingAccount? = account(),
        dashjCursor: Int? = null,
        paidFile: File? = null
    ) = Harness(cutover, sdkAccount, dashjAddress, params, dashjCursor, paidFile)

    @Test
    fun postCutover_usesTheSdkSendingAccountAddress() = runBlocking {
        val h = harness()

        val result = h.provider.nextAddress(target)

        assertEquals(ContactAddressSource.SDK, result?.source)
        assertEquals(sdkPool[1], result?.address) // index 0 is used
        assertTrue("dashj must not be consulted when the SDK answers", h.dashjReads.isEmpty())
        // The SDK is asked for THIS identity pair, as raw 32-byte ids.
        val (ourId, contactId) = h.sdkReadIds.single()
        assertTrue(ourId.contentEquals(ByteArray(32) { 1 }))
        assertTrue(contactId.contentEquals(ByteArray(32) { 2 }))
    }

    @Test
    fun preCutover_usesDashjAndNeverReadsTheSdk() = runBlocking {
        val h = harness(cutover = false)

        val result = h.provider.nextAddress(target)

        assertEquals(ContactAddressSource.DASHJ, result?.source)
        assertEquals(dashjAddress, result?.address)
        assertEquals(0, h.sdkReads)
        assertEquals(listOf(contactUserId to selectedReference), h.dashjReads)
    }

    @Test
    fun postCutover_fallsBackToDashj_whenTheSdkHasNoSendingAccount() = runBlocking {
        val h = harness(sdkAccount = account(addresses = null))

        val result = h.provider.nextAddress(target)

        assertEquals(ContactAddressSource.DASHJ, result?.source)
        assertEquals(dashjAddress, result?.address)
        assertEquals(listOf(contactUserId to selectedReference), h.dashjReads)
    }

    @Test
    fun postCutover_fallsBackToDashj_whenTheSdkMarkedTheChannelBroken() = runBlocking {
        val h = harness(sdkAccount = account(paymentChannelBroken = true))

        assertEquals(ContactAddressSource.DASHJ, h.provider.nextAddress(target)?.source)
    }

    @Test
    fun postCutover_fallsBackToDashj_whenTheSdkStoreIsUnavailable() = runBlocking {
        val h = harness(sdkAccount = null)

        assertEquals(ContactAddressSource.DASHJ, h.provider.nextAddress(target)?.source)
    }

    @Test
    fun postCutover_fallsBackToDashj_whenTheSdkReadThrows() = runBlocking {
        val provider = ContactPaymentAddressProvider(
            cutoverCommitted = { true },
            sdkAccountReader = { _, _ -> throw IllegalStateException("db closed") },
            dashjNextAddress = { _, _ -> dashjAddress },
            dashjMarkUsed = { _, _, _ -> null },
            params = params
        )

        assertEquals(ContactAddressSource.DASHJ, provider.nextAddress(target)?.source)
    }

    @Test
    fun postCutover_fallsBackToDashj_whenTheSdkAddressIsForAnotherNetwork() = runBlocking {
        val mainnet = Address.fromKey(org.bitcoinj.params.MainNetParams.get(), ECKey())
        val h = harness(
            sdkAccount = account(addresses = listOf(SdkContactPoolAddress(mainnet.toBase58(), 0, false, 2)))
        )

        assertEquals(ContactAddressSource.DASHJ, h.provider.nextAddress(target)?.source)
    }

    @Test
    fun nullWhenNeitherStackHasAnAddress() = runBlocking {
        val h = Harness(cutover = true, sdkAccount = account(addresses = null), dashjAddress = null, params = params)

        assertNull(h.provider.nextAddress(target))
    }

    // ── account-reference matching ──────────────────────────────────────

    @Test
    fun postCutover_fallsBackToDashj_whenTheSdkAccountWasBuiltFromAnotherRequest() = runBlocking {
        // The contact re-issued their request: the SDK's sending account is from
        // the old reference, the send screen picked the newest. A different
        // reference means a different xpub — never pay the SDK's address then.
        val h = harness(sdkAccount = account(externalAccountReference = selectedReference + 1))

        val result = h.provider.nextAddress(target)

        assertEquals(ContactAddressSource.DASHJ, result?.source)
        assertEquals(listOf(contactUserId to selectedReference), h.dashjReads)
    }

    @Test
    fun selection_requiresTheSelectedAccountReference() {
        val matching = selectSdkContactAddress(account(), selectedReference, emptySet())
        val other = selectSdkContactAddress(account(), selectedReference + 1, emptySet())

        assertTrue(matching is SdkContactAddressSelection.Selected)
        assertTrue(other is SdkContactAddressSelection.Unavailable)
        assertTrue((other as SdkContactAddressSelection.Unavailable).reason.contains("accountReference"))
    }

    @Test
    fun selection_unknownBuildReference_isUnavailable() {
        val result = selectSdkContactAddress(account(externalAccountReference = null), selectedReference, emptySet())

        assertTrue(result is SdkContactAddressSelection.Unavailable)
    }

    @Test
    fun selection_noIncomingRequest_isUnavailable() {
        val result = selectSdkContactAddress(account(incomingRequestFound = false), selectedReference, emptySet())

        assertTrue(result is SdkContactAddressSelection.Unavailable)
    }

    // ── which address in the pool ───────────────────────────────────────

    @Test
    fun selection_takesTheLowestUnusedIndex_regardlessOfRowOrder() {
        val rows = pool(true, true, false, false).reversed()

        val result = selectSdkContactAddress(account(addresses = rows), selectedReference, emptySet())

        assertEquals(SdkContactAddressSelection.Selected(sdkPool[2].toBase58(), 2), result)
    }

    @Test
    fun selection_skipsAddressesThisProcessAlreadyPaid() {
        val result = selectSdkContactAddress(
            account(addresses = pool(true, false, false)),
            selectedReference,
            setOf(sdkPool[1].toBase58())
        )

        assertEquals(SdkContactAddressSelection.Selected(sdkPool[2].toBase58(), 2), result)
    }

    @Test
    fun selection_allUsed_isUnavailable() {
        val result = selectSdkContactAddress(account(addresses = pool(true, true)), selectedReference, emptySet())

        assertTrue(result is SdkContactAddressSelection.Unavailable)
    }

    @Test
    fun selection_mixedPools_isUnavailable() {
        val rows = listOf(
            SdkContactPoolAddress(sdkPool[0].toBase58(), 0, false, poolTypeTag = 0),
            SdkContactPoolAddress(sdkPool[1].toBase58(), 0, false, poolTypeTag = 1)
        )

        val result = selectSdkContactAddress(account(addresses = rows), selectedReference, emptySet())

        assertTrue(result is SdkContactAddressSelection.Unavailable)
    }

    // ── marking used only after a successful send ───────────────────────

    @Test
    fun successfulSend_marksTheDashjKeyUsed_andTheNextPickMovesOn() = runBlocking {
        val h = harness()
        val first = h.provider.nextAddress(target)

        val sent = h.provider.sendThenMarkUsed(first) { "txid" }

        assertEquals("txid", sent)
        assertEquals(listOf(Triple(contactUserId, selectedReference, sdkPool[1])), h.marks)
        // The SDK mirror still says index 1 is unused (it trails the engine) —
        // the next payment must still get a new address.
        val second = h.provider.nextAddress(target)
        assertEquals(sdkPool[2], second?.address)
    }

    @Test
    fun failedSend_marksNothing() = runBlocking {
        val h = harness()
        val first = h.provider.nextAddress(target)

        assertThrows(IllegalStateException::class.java) {
            runBlocking { h.provider.sendThenMarkUsed(first) { throw IllegalStateException("not broadcast") } }
        }

        assertTrue(h.marks.isEmpty())
        // ...and the same address is offered again: nothing was paid.
        assertEquals(sdkPool[1], h.provider.nextAddress(target)?.address)
    }

    @Test
    fun cancelledSend_marksNothing() = runBlocking {
        val h = harness()
        val first = h.provider.nextAddress(target)

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking {
                h.provider.sendThenMarkUsed(first) { throw kotlinx.coroutines.CancellationException("aborted") }
            }
        }

        assertTrue(h.marks.isEmpty())
    }

    @Test
    fun dashjSourcedPayment_isMarkedToo() = runBlocking {
        val h = harness(cutover = false)
        val payment = h.provider.nextAddress(target)

        h.provider.sendThenMarkUsed(payment) { Unit }

        assertEquals(listOf(Triple(contactUserId, selectedReference, dashjAddress)), h.marks)
    }

    @Test
    fun nonContactSend_marksNothing() = runBlocking {
        val h = harness()

        h.provider.sendThenMarkUsed(null) { Unit }

        assertTrue(h.marks.isEmpty())
    }

    @Test
    fun markFailure_doesNotFailTheSentPayment() = runBlocking {
        val h = harness()
        h.markThrows = true
        val payment = h.provider.nextAddress(target)

        val sent = h.provider.sendThenMarkUsed(payment) { "txid" }

        assertEquals("txid", sent)
        // Still remembered as paid in-process, so the SDK path moves on.
        assertFalse(h.provider.nextAddress(target)?.address == payment?.address)
    }

    // ── paid addresses survive a restart (PR #1608 review) ──────────────

    @Test
    fun selection_skipsIndicesBelowDashjsCursor() {
        val result = selectSdkContactAddress(
            account(addresses = pool(true, false, false, false)),
            selectedReference,
            emptySet(),
            dashjCursor = 2
        )

        assertEquals(SdkContactAddressSelection.Selected(sdkPool[2].toBase58(), 2), result)
    }

    @Test
    fun selection_dashjCursorBeyondThePool_isUnavailable() {
        val result = selectSdkContactAddress(
            account(addresses = pool(true, false, false)),
            selectedReference,
            emptySet(),
            dashjCursor = 3
        )

        assertTrue(result is SdkContactAddressSelection.Unavailable)
        assertTrue((result as SdkContactAddressSelection.Unavailable).reason.contains("cursor"))
    }

    @Test
    fun restart_unchangedSdkPool_advancedDashjCursor_choosesANewAddress() = runBlocking {
        // Process 1: pay the SDK's lowest unused address (index 1). Marking it on
        // dashj moves dashj's persisted cursor past it, to 2.
        val before = harness(dashjCursor = 1)
        val paid = before.provider.nextAddress(target)
        assertEquals(sdkPool[1], paid?.address)
        before.provider.sendThenMarkUsed(paid) { "txid" }
        assertEquals(listOf(Triple(contactUserId, selectedReference, sdkPool[1])), before.marks)

        // Process 2: nothing remembered in memory, and the SDK mirror has not
        // flagged index 1 yet (pool unchanged) — but dashj's cursor was saved.
        val after = harness(dashjCursor = 2)

        val next = after.provider.nextAddress(target)

        assertEquals(ContactAddressSource.SDK, next?.source)
        assertEquals(sdkPool[2], next?.address)
    }

    @Test
    fun restart_beforeDashjSaved_thePaidAddressFileStillSkipsIt() = runBlocking {
        // The process dies after the payment but before dashj's autosave wrote
        // the advanced cursor: dashj comes back at its old cursor (1) and the
        // SDK mirror still has index 1 unused. The paid-address file was written
        // at broadcast time.
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val before = harness(dashjCursor = 1, paidFile = file)
        val paid = before.provider.nextAddress(target)
        before.provider.sendThenMarkUsed(paid) { "txid" }

        val after = harness(dashjCursor = 1, paidFile = file)

        assertEquals(sdkPool[2], after.provider.nextAddress(target)?.address)
    }

    /**
     * A dashj SENDING chain as the provider sees it: [current] is served until
     * a mark of it advances to the next key (FriendChainAccess.markSendingAddressUsed
     * issuing a fresh current key). [advances] false: marks change nothing.
     */
    private class FakeDashjChain(
        val keys: List<Address>,
        var current: Int,
        val markResult: FriendChainAccess.MarkResult? = FriendChainAccess.MarkResult.MARKED,
        val advances: Boolean = true
    ) {
        val marks = mutableListOf<Address>()

        fun next(): Address? = keys.getOrNull(current)

        fun mark(address: Address): FriendChainAccess.MarkResult? {
            marks += address
            if (markResult == FriendChainAccess.MarkResult.MARKED && advances && keys.indexOf(address) == current) {
                current++
            }
            return markResult
        }
    }

    /** A process restart: a fresh provider over [paidFile] and an un-advanced dashj [chain]. */
    private fun restartedProvider(
        chain: FakeDashjChain,
        paidFile: File,
        sdkAccount: SdkContactSendingAccount?
    ) = ContactPaymentAddressProvider(
        cutoverCommitted = { true },
        sdkAccountReader = { _, _ -> sdkAccount },
        dashjNextAddress = { _, _ -> chain.next() },
        dashjMarkUsed = { _, _, address -> chain.mark(address) },
        params = params,
        paidAddresses = PaidContactAddressStore(paidFile)
    )

    private fun paidFileWith(vararg addresses: Address): File {
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val store = PaidContactAddressStore(file)
        addresses.forEach { store.record(it.toBase58()) }
        return file
    }

    @Test
    fun restart_dashjNotSaved_brokenSdkChannel_advancesDashjPastThePaidAddress() = runBlocking {
        // Process 1 paid dashj's key 0 through the dashj fallback and recorded it;
        // it died before dashj's saveLater, so dashj restarts at key 0 again.
        val keys = List(3) { newAddress() }
        val file = paidFileWith(keys[0])
        val chain = FakeDashjChain(keys, current = 0)
        val provider = restartedProvider(chain, file, account(paymentChannelBroken = true))

        val result = provider.nextAddress(target)

        assertEquals(ContactAddressSource.DASHJ, result?.source)
        assertEquals(keys[1], result?.address)
        assertEquals(listOf(keys[0]), chain.marks)
    }

    @Test
    fun restart_dashjNotSaved_severalPaid_advancesPastEachOne() = runBlocking {
        val keys = List(4) { newAddress() }
        val file = paidFileWith(keys[0], keys[1])
        val chain = FakeDashjChain(keys, current = 0)
        val provider = restartedProvider(chain, file, sdkAccount = null)

        assertEquals(keys[2], provider.nextAddress(target)?.address)
        assertEquals(listOf(keys[0], keys[1]), chain.marks)
    }

    @Test
    fun restart_dashjServesAPaidAddress_andCannotAdvance_noAddress() = runBlocking {
        val keys = List(3) { newAddress() }
        val file = paidFileWith(keys[0])

        // the mark is refused (e.g. no dashj identity / chain)
        val refused = FakeDashjChain(keys, current = 0, markResult = null)
        assertNull(restartedProvider(refused, file, sdkAccount = null).nextAddress(target))

        // the mark reports success but dashj keeps serving the paid key: bounded
        val stuck = FakeDashjChain(keys, current = 0, advances = false)
        assertNull(restartedProvider(stuck, file, account(paymentChannelBroken = true)).nextAddress(target))
        assertEquals(1, stuck.marks.size) // bounded by the one paid record
    }

    @Test
    fun dashjServesAnUnpaidAddress_isNotMarkedAhead() = runBlocking {
        val keys = List(2) { newAddress() }
        val file = paidFileWith(newAddress()) // another contact's payment
        val chain = FakeDashjChain(keys, current = 0)

        assertEquals(keys[0], restartedProvider(chain, file, sdkAccount = null).nextAddress(target)?.address)
        assertTrue(chain.marks.isEmpty())
    }

    @Test
    fun restart_dashjCursorPastTheSdkPool_fallsBackToDashj() = runBlocking {
        val h = harness(dashjCursor = 3)

        val result = h.provider.nextAddress(target)

        assertEquals(ContactAddressSource.DASHJ, result?.source)
        assertEquals(dashjAddress, result?.address)
    }

    @Test
    fun dashjCursorReadFailure_fallsBackToTheSdkPoolAlone() = runBlocking {
        val provider = ContactPaymentAddressProvider(
            cutoverCommitted = { true },
            sdkAccountReader = { _, _ -> account() },
            dashjNextAddress = { _, _ -> dashjAddress },
            dashjMarkUsed = { _, _, _ -> null },
            params = params,
            dashjSendingCursor = { _, _ -> throw IllegalStateException("wallet closed") }
        )

        assertEquals(sdkPool[1], provider.nextAddress(target)?.address)
    }

    // ── marking at the broadcast, not at the end of the send ────────────

    @Test
    fun broadcastSignalled_thenTheSendThrows_isStillMarked() = runBlocking {
        val h = harness()
        val payment = h.provider.nextAddress(target)

        assertThrows(RuntimeException::class.java) {
            runBlocking {
                h.provider.sendThenMarkUsed(payment) { onBroadcast ->
                    onBroadcast("txid")
                    throw RuntimeException("sent, but the display bridge failed")
                }
            }
        }

        assertEquals(listOf(Triple(contactUserId, selectedReference, sdkPool[1])), h.marks)
        assertEquals(sdkPool[2], h.provider.nextAddress(target)?.address)
    }

    @Test
    fun unknownOutcomeSignalled_thenTheSendThrows_isStillMarked() = runBlocking {
        // The post-cutover SDK Ambiguous path: no txid, then the cause is rethrown.
        val h = harness()
        val payment = h.provider.nextAddress(target)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                h.provider.sendThenMarkUsed(payment) { onBroadcast ->
                    onBroadcast(null)
                    throw IllegalStateException("broadcast outcome unknown")
                }
            }
        }

        assertEquals(listOf(Triple(contactUserId, selectedReference, sdkPool[1])), h.marks)
        // the possibly-paid address is never offered again
        assertEquals(sdkPool[2], h.provider.nextAddress(target)?.address)
    }

    @Test
    fun broadcastSignalled_thenCancelled_isStillMarked() = runBlocking {
        val h = harness()
        val payment = h.provider.nextAddress(target)

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking {
                h.provider.sendThenMarkUsed(payment) { onBroadcast ->
                    onBroadcast("txid")
                    throw kotlinx.coroutines.CancellationException("left the screen")
                }
            }
        }

        assertEquals(1, h.marks.size)
    }

    @Test
    fun broadcastSignalled_andReturned_marksOnce() = runBlocking {
        val h = harness()
        val payment = h.provider.nextAddress(target)

        h.provider.sendThenMarkUsed(payment) { onBroadcast ->
            onBroadcast("txid")
            onBroadcast("txid")
            "txid"
        }

        assertEquals(1, h.marks.size)
    }
}
