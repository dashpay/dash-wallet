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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ContactPaymentAddressProviderTest {

    companion object {
        /** The header tip the fake chain views start at. */
        private const val TIP = 1_000_000L

        /**
         * The L1 sync progress production reports during the live chase:
         * headers at [tip], the filter scan and the wallet's committed cursor
         * at [processed] (the SDK's aggregate below the iOS threshold, so only
         * the height test decides).
         */
        fun liveProgress(tip: Long, processed: Long) = ShadowSyncProgress(
            phase = ShadowSyncPhase.FILTERS,
            overallPercent = 0.99,
            headerHeight = tip,
            headerTarget = tip,
            filterHeight = processed,
            filterTarget = tip,
            walletSyncedHeight = processed
        )
    }

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

        /** The persisted cutover state cannot be read (production: [SdkL1SendService.cutoverCommittedOrThrow]). */
        var cutoverUnreadable = false

        val provider = ContactPaymentAddressProvider(
            cutoverCommitted = {
                if (cutoverUnreadable) throw java.io.IOException("preferences unreadable")
                cutover
            },
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
    fun unreadableCutoverState_handsOutNothing_andSendsNothing() = runBlocking {
        // PR #1608 review (self-review): an unreadable state used to read as
        // "not committed" — the pre-cutover dashj path, which skips the
        // post-cutover paid-history and SDK-store checks.
        val h = harness()
        val payment = h.provider.nextAddress(target)
        h.cutoverUnreadable = true

        assertThrows(java.io.IOException::class.java) { runBlocking { h.provider.nextAddress(target) } }
        var sent = false
        assertThrows(java.io.IOException::class.java) {
            runBlocking { h.provider.sendThenMarkUsed(payment) { sent = true } }
        }
        assertFalse("nothing is sent", sent)
        assertTrue("nothing is marked", h.marks.isEmpty())
        assertTrue("dashj was not asked for an address", h.dashjReads.isEmpty())
    }

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
    fun provenPreBroadcastFailure_marksNothing_andTheAddressIsOfferedAgain() = runBlocking {
        val h = harness()
        val first = h.provider.nextAddress(target)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                h.provider.sendThenMarkUsed(first) { signals ->
                    signals.notBroadcast()
                    throw IllegalStateException("not broadcast")
                }
            }
        }

        assertTrue(h.marks.isEmpty())
        // ...and the same address is offered again: nothing was paid.
        assertEquals(sdkPool[1], h.provider.nextAddress(target)?.address)
        assertFalse(h.provider.isClaimedOrPaid(requireNotNull(first)))
    }

    @Test
    fun failureWithoutProof_isAnUnknownOutcome_theAddressIsKeptAndMarked() = runBlocking {
        val h = harness()
        val first = requireNotNull(h.provider.nextAddress(target))

        assertThrows(IllegalStateException::class.java) {
            runBlocking { h.provider.sendThenMarkUsed(first) { throw IllegalStateException("unexpected") } }
        }

        assertEquals(listOf(Triple(contactUserId, selectedReference, sdkPool[1])), h.marks)
        assertTrue(h.provider.isClaimedOrPaid(first))
        assertEquals(sdkPool[2], h.provider.nextAddress(target)?.address)
    }

    @Test
    fun cancelledSend_isAnUnknownOutcome_theAddressIsKeptAndMarked() = runBlocking {
        // A cancellation can land while the SDK's native send already broadcast
        // (SdkL1SendService rethrows it): never give the address back.
        val h = harness()
        val first = h.provider.nextAddress(target)

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking {
                h.provider.sendThenMarkUsed(first) { throw kotlinx.coroutines.CancellationException("aborted") }
            }
        }

        assertEquals(1, h.marks.size)
        assertEquals(sdkPool[2], h.provider.nextAddress(target)?.address)
    }

    @Test
    fun cancelledAfterAProvenPreBroadcastFailure_marksNothing() = runBlocking {
        val h = harness()
        val first = h.provider.nextAddress(target)

        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking {
                h.provider.sendThenMarkUsed(first) { signals ->
                    signals.notBroadcast()
                    throw kotlinx.coroutines.CancellationException("aborted")
                }
            }
        }

        assertTrue(h.marks.isEmpty())
        assertEquals(sdkPool[1], h.provider.nextAddress(target)?.address)
    }

    // ── one address, one send: the claim (PR #1608 review, round 5) ─────

    @Test
    fun concurrentSendOfTheSameAddress_isRefused_untilTheFirstFailsBeforeBroadcast() = runBlocking {
        // Two send screens resolved the same address. The first send is paused
        // inside the send, before its broadcast signal.
        val h = harness()
        val payment = requireNotNull(h.provider.nextAddress(target))
        val firstStarted = CompletableDeferred<Unit>()
        val firstMayFinish = CompletableDeferred<Boolean>()
        var secondSent = false

        val first = async(Dispatchers.Default) {
            runCatching {
                h.provider.sendThenMarkUsed(payment) { signals ->
                    firstStarted.complete(Unit)
                    if (!firstMayFinish.await()) {
                        signals.notBroadcast()
                        throw IllegalStateException("insufficient funds")
                    }
                    "txid"
                }
            }
        }
        firstStarted.await()

        // The second send of that address is refused before it runs...
        val refused = assertThrows(ContactAddressAlreadyPaidException::class.java) {
            runBlocking { h.provider.sendThenMarkUsed(payment) { secondSent = true } }
        }
        assertFalse("the second send never ran", secondSent)
        assertEquals(payment, refused.payment)
        assertTrue(h.provider.isClaimedOrPaid(payment))
        // ...and resolving again gives the second screen another address.
        assertEquals(sdkPool[2], h.provider.nextAddress(target)?.address)

        // The first send fails provably before broadcasting: the address is free again.
        firstMayFinish.complete(false)
        assertTrue(first.await().isFailure)
        assertTrue(h.marks.isEmpty())
        assertFalse(h.provider.isClaimedOrPaid(payment))
        assertEquals("txid", h.provider.sendThenMarkUsed(payment) { "txid" })
        assertEquals(listOf(Triple(contactUserId, selectedReference, sdkPool[1])), h.marks)
    }

    @Test
    fun concurrentSendOfTheSameAddress_isRefused_whenTheFirstIsBroadcast() = runBlocking<Unit> {
        val h = harness()
        val payment = requireNotNull(h.provider.nextAddress(target))
        val firstStarted = CompletableDeferred<Unit>()
        val firstMayFinish = CompletableDeferred<Unit>()

        val first = async(Dispatchers.Default) {
            h.provider.sendThenMarkUsed(payment) {
                firstStarted.complete(Unit)
                firstMayFinish.await()
                "txid"
            }
        }
        firstStarted.await()
        assertThrows(ContactAddressAlreadyPaidException::class.java) {
            runBlocking { h.provider.sendThenMarkUsed(payment) { "second" } }
        }
        firstMayFinish.complete(Unit)

        assertEquals("txid", first.await())
        assertEquals(1, h.marks.size)
        assertThrows(ContactAddressAlreadyPaidException::class.java) {
            runBlocking { h.provider.sendThenMarkUsed(payment) { "third" } }
        }
    }

    @Test
    fun processDeathMidSend_theClaimSurvives() = runBlocking {
        // The process dies inside the send (after the SDK may have broadcast,
        // before any callback): the claim was written before the send began.
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val before = harness(dashjCursor = 1, paidFile = file)
        val payment = requireNotNull(before.provider.nextAddress(target))
        val inSend = CompletableDeferred<Unit>()
        val dying = async(Dispatchers.Default) {
            before.provider.sendThenMarkUsed(payment) {
                inSend.complete(Unit)
                CompletableDeferred<Unit>().await() // never returns: the process is gone
            }
        }
        inSend.await()

        val after = harness(dashjCursor = 1, paidFile = file)
        assertEquals(sdkPool[2], after.provider.nextAddress(target)?.address)
        // dashj is advanced past it on the restarted process's first lookup
        assertEquals(listOf(Triple(contactUserId, selectedReference, sdkPool[1])), after.marks)
        dying.cancel()
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
     * [advancePast] is FriendChainAccess.advanceSendingChainPast: index-aware,
     * any distance ahead, refused when the key at the index is not the address.
     */
    private class FakeDashjChain(
        val keys: List<Address>,
        var current: Int,
        val markResult: FriendChainAccess.MarkResult? = FriendChainAccess.MarkResult.MARKED,
        val advances: Boolean = true,
        /** A mark of a key AHEAD of the current one moves past it too (FriendChainAccess does). */
        val jumps: Boolean = false
    ) {
        val marks = mutableListOf<Address>()

        val frontierAdvances = mutableListOf<Pair<Int, Address>>()

        fun next(): Address? = keys.getOrNull(current)

        fun advancePast(index: Int, address: Address): FriendChainAccess.AdvanceResult {
            frontierAdvances += index to address
            return when {
                keys.getOrNull(index) != address -> FriendChainAccess.AdvanceResult.MISMATCH
                current > index -> FriendChainAccess.AdvanceResult.ALREADY_PAST
                else -> {
                    current = index + 1
                    FriendChainAccess.AdvanceResult.ADVANCED
                }
            }
        }

        fun mark(address: Address): FriendChainAccess.MarkResult? {
            marks += address
            val index = keys.indexOf(address)
            if (markResult == FriendChainAccess.MarkResult.MARKED && advances &&
                (index == current || (jumps && index > current))
            ) {
                current = index + 1
            }
            return markResult
        }
    }

    /** A process restart: a fresh provider over [paidFile] and an un-advanced dashj [chain]. */
    private fun restartedProvider(
        chain: FakeDashjChain,
        paidFile: File,
        sdkAccount: SdkContactSendingAccount?,
        store: PaidContactAddressStore = PaidContactAddressStore(paidFile),
        chainView: ContactPaymentChainView = ContactPaymentChainView.NOTHING_PAID
    ) = ContactPaymentAddressProvider(
        cutoverCommitted = { true },
        sdkAccountReader = { _, _ -> sdkAccount },
        dashjNextAddress = { _, _ -> chain.next() },
        dashjMarkUsed = { _, _, address -> chain.mark(address) },
        params = params,
        dashjSendingCursor = { _, _ -> chain.current },
        dashjAdvancePast = { _, _, index, address -> chain.advancePast(index, address) },
        paidAddresses = store,
        chain = chainView
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
                h.provider.sendThenMarkUsed(payment) { signals ->
                    signals.broadcast("txid")
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
                h.provider.sendThenMarkUsed(payment) { signals ->
                    signals.broadcast(null)
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
                h.provider.sendThenMarkUsed(payment) { signals ->
                    signals.broadcast("txid")
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

        h.provider.sendThenMarkUsed(payment) { signals ->
            signals.broadcast("txid")
            signals.broadcast("txid")
            "txid"
        }

        assertEquals(1, h.marks.size)
    }

    // ── exclusions are kept until a stack has durably recorded the payment ──

    @Test
    fun restart_brokenChannel_expiredRecord_unadvancedDashj_neverReturnsThePaidAddress() = runBlocking {
        // PR #1608 review: process 1 pays dashj's key 0 through the broken-
        // channel fallback, records it, and dies before dashj's wallet save.
        // The app stays closed past the retention; dashj reloads at key 0.
        var now = 1_000_000_000_000L
        val keys = List(3) { newAddress() }
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val broken = account(paymentChannelBroken = true)

        val before = FakeDashjChain(keys, current = 0)
        val process1 = restartedProvider(before, file, broken, PaidContactAddressStore(file, { now }))
        val paid = process1.nextAddress(target)
        assertEquals(keys[0], paid?.address)
        process1.sendThenMarkUsed(paid) { "txid" } // dashj advanced in memory only — never saved

        now += PaidContactAddressStore.RETENTION_MS + TimeUnit.DAYS.toMillis(1)
        val saves = WalletSaveTracker()
        val store = PaidContactAddressStore(file, { now }, saves)
        val after = FakeDashjChain(keys, current = 0) // the un-advanced, persisted chain
        val process2 = restartedProvider(after, file, broken, store)

        val next = process2.nextAddress(target)

        assertEquals(ContactAddressSource.DASHJ, next?.source)
        assertEquals(keys[1], next?.address)
        assertTrue(keys[0].toBase58() in store.paid())
        // dashj's lost mark was re-applied; the next wallet save makes it durable
        assertEquals(listOf(keys[0]), after.marks)
        assertEquals(false, store.isSettled(keys[0].toBase58()))
        saves.saveCompleted(saves.saveStarting())
        // settled, and past the retention: the entry is folded into the
        // channel's frontier, which keeps index 0 excluded for any wallet
        assertNull(store.isSettled(keys[0].toBase58()))
        assertEquals(
            PaidContactAddressStore.Frontier(0, keys[0].toBase58()),
            PaidContactAddressStore(file, { now }).foldedFrontier(contactUserId, selectedReference)
        )
    }

    @Test
    fun settledRecord_expires_onceDashjsSavedChainIsPastIt() = runBlocking {
        var now = 1_000_000_000_000L
        val keys = List(3) { newAddress() }
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val saves = WalletSaveTracker()
        val chain = FakeDashjChain(keys, current = 0)
        val provider = restartedProvider(chain, file, sdkAccount = null, PaidContactAddressStore(file, { now }, saves))
        val paid = provider.nextAddress(target)
        provider.sendThenMarkUsed(paid) { "txid" }
        saves.saveCompleted(saves.saveStarting()) // dashj's advanced chain reached disk

        now += PaidContactAddressStore.RETENTION_MS + 1
        PaidContactAddressStore(file, { now }).record(newAddress().toBase58()) // a write expires what is due
        val store = PaidContactAddressStore(file, { now })

        assertNull("redundant once settled, so it may expire", store.isSettled(keys[0].toBase58()))
        assertEquals(0, store.foldedFrontier(contactUserId, selectedReference)?.index)
        // ...and dashj, reloaded from that save, is past it anyway
        assertEquals(keys[1], restartedProvider(chain, file, sdkAccount = null, store).nextAddress(target)?.address)
    }

    @Test
    fun sdkOnlyPayment_noDashjChain_thenChainBuiltLater_dashjFallbackNeverReturnsThePaidAddress() = runBlocking {
        // PR #1608 review, round 5: a payment through the SDK while dashj has
        // no sending chain for the contact; the SDK mirror then flags it used.
        // Long after (past the retention), dashj builds the chain — starting at
        // index 0, the paid address — and the SDK can no longer answer.
        var now = 1_000_000_000_000L
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val saves = WalletSaveTracker()
        var sdkAccount: SdkContactSendingAccount? = account(addresses = pool(false, false, false))
        var dashjChain: FakeDashjChain? = null // no chain yet
        fun provider(store: PaidContactAddressStore) = ContactPaymentAddressProvider(
            cutoverCommitted = { true },
            sdkAccountReader = { _, _ -> sdkAccount },
            dashjNextAddress = { _, _ -> dashjChain?.next() },
            dashjMarkUsed = { _, _, address ->
                dashjChain?.mark(address) ?: FriendChainAccess.MarkResult.NO_CHAIN
            },
            params = params,
            paidAddresses = store
        )
        val process1 = provider(PaidContactAddressStore(file, { now }, saves))
        val paid = process1.nextAddress(target)
        assertEquals(sdkPool[0], paid?.address)
        process1.sendThenMarkUsed(paid) { "txid" }
        // the SDK mirror flags it used; wallet saves land; the SDK keeps working
        sdkAccount = account(addresses = pool(true, false, false))
        saves.saveCompleted(saves.saveStarting())
        assertEquals(sdkPool[1], process1.nextAddress(target)?.address)
        assertEquals(
            "no stack has it durably: kept",
            false,
            PaidContactAddressStore(file, { now }).isSettled(sdkPool[0].toBase58())
        )

        // Restart past the retention; dashj has built the chain since, at index 0.
        now += PaidContactAddressStore.RETENTION_MS * 3
        dashjChain = FakeDashjChain(sdkPool, current = 0)
        sdkAccount = null // SDK store unavailable: the dashj fallback answers
        val saves2 = WalletSaveTracker()
        val store2 = PaidContactAddressStore(file, { now }, saves2)
        val process2 = provider(store2)

        val next = process2.nextAddress(target)

        assertEquals(ContactAddressSource.DASHJ, next?.source)
        assertEquals(sdkPool[1], next?.address)
        assertEquals(listOf(sdkPool[0]), dashjChain?.marks)
        // the next wallet save makes dashj's advance durable; only then may it
        // expire — into the channel's frontier
        saves2.saveCompleted(saves2.saveStarting())
        val after = PaidContactAddressStore(file, { now })
        assertNull(after.isSettled(sdkPool[0].toBase58()))
        assertEquals(0, after.foldedFrontier(contactUserId, selectedReference)?.index)
    }

    @Test
    fun dashjFallback_skipsWhatTheSdkMirrorFlagsUsed() = runBlocking {
        // e.g. a restored wallet: dashj's chain restarted at 0, the SDK mirror
        // knows index 0 and 1 are used, the paid file does not (expired).
        val chain = FakeDashjChain(sdkPool, current = 0)
        val provider = restartedProvider(
            chain,
            File(tmp.root, PaidContactAddressStore.FILE_NAME),
            account(paymentChannelBroken = true, addresses = pool(true, true, false))
        )

        val next = provider.nextAddress(target)

        assertEquals(sdkPool[2], next?.address)
        assertEquals(listOf(sdkPool[0], sdkPool[1]), chain.marks)
    }

    @Test
    fun sendThenMarkUsed_refusesAnAddressAlreadyRecordedPaid() = runBlocking {
        val h = harness()
        val payment = h.provider.nextAddress(target)
        h.provider.sendThenMarkUsed(payment) { "txid" }
        var sentAgain = false

        val refused = assertThrows(ContactAddressAlreadyPaidException::class.java) {
            runBlocking { h.provider.sendThenMarkUsed(payment) { sentAgain = true } }
        }

        assertFalse("nothing may be sent to a paid address", sentAgain)
        assertEquals(payment, refused.payment)
        assertEquals(1, h.marks.size)
    }

    // ── a claim that cannot be made durable refuses the send (round 6) ──

    @Test
    fun unreadablePaidAddressFile_theSendNeverRuns() = runBlocking {
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        PaidContactAddressStore(file).record(newAddress().toBase58(), target, 0)
        val h = harness(paidFile = file)
        assertTrue(file.setReadable(false))
        // Root reads it anyway (container CI): the case cannot be set up there.
        assumeFalse(file.canRead())
        try {
            val payment = requireNotNull(h.provider.nextAddress(target))
            var sent = false

            val refused = assertThrows(ContactPaymentNotRecordableException::class.java) {
                runBlocking { h.provider.sendThenMarkUsed(payment) { sent = true } }
            }

            assertFalse("the send callback never ran", sent)
            assertEquals(payment, refused.payment)
            assertTrue(h.marks.isEmpty())
        } finally {
            file.setReadable(true)
        }
    }

    @Test
    fun unwritablePaidAddressFile_theSendNeverRuns() = runBlocking {
        // A directory where the file should be: the claim's write fails.
        val blocked = File(tmp.root, "blocked").apply { mkdirs() }
        File(blocked, "x").writeText("")
        val h = harness(paidFile = blocked)
        val payment = requireNotNull(h.provider.nextAddress(target))
        var sent = false

        assertThrows(ContactPaymentNotRecordableException::class.java) {
            runBlocking { h.provider.sendThenMarkUsed(payment) { sent = true } }
        }

        assertFalse("the send callback never ran", sent)
        assertTrue(h.marks.isEmpty())
        assertFalse("nothing claimed", h.provider.isClaimedOrPaid(payment))
    }

    // ── recovery after the records expired: the channel frontier (round 6) ──

    @Test
    fun coldRecovery_expiredSettledRecord_olderDashjChain_neverReturnsThePaidAddress() = runBlocking {
        // PR #1608 review, round 6: address 0 was paid and SETTLED on the lost
        // primary wallet more than the retention ago; its record is still in
        // the file. Recovery from the older key backup creates the app's store
        // and reports the wallet change before any lookup; the backup's chain
        // is back at key 0, and the SDK cannot answer.
        val now = 1_000_000_000_000L
        val keys = List(3) { newAddress() }
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val paidAt = now - PaidContactAddressStore.RETENTION_MS * 3
        file.writeText("$paidAt ${keys[0].toBase58()} settled $contactUserId $selectedReference 0\n")
        val saves = WalletSaveTracker()
        val store = PaidContactAddressStore(file, { now }, saves) // created cold, never read

        saves.walletChanged() // restoreWalletFromBackup

        val recovered = FakeDashjChain(keys, current = 0)
        val next = restartedProvider(recovered, file, sdkAccount = null, store).nextAddress(target)

        assertEquals(keys[1], next?.address)
        assertEquals(listOf(keys[0]), recovered.marks)
        assertEquals(1, next?.index)
    }

    @Test
    fun recordAlreadyFolded_rebuiltDashjChain_isAdvancedPastTheFrontier() = runBlocking {
        // The records of indices 0..2 expired long ago (folded into the
        // frontier); then the wallet is replaced by one whose chain is back at
        // key 0 — nothing but the frontier remembers those payments.
        val keys = List(5) { newAddress() }
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        file.writeText("frontier $contactUserId $selectedReference 2 ${keys[2].toBase58()}\n")
        val rebuilt = FakeDashjChain(keys, current = 0, jumps = true)

        val next = restartedProvider(rebuilt, file, sdkAccount = null).nextAddress(target)

        assertEquals(keys[3], next?.address)
        assertEquals(listOf(2 to keys[2]), rebuilt.frontierAdvances)
        assertTrue(rebuilt.marks.isEmpty())
    }

    @Test
    fun recordAlreadyFolded_sdkPick_isAboveTheFrontier_evenWithoutADashjChain() = runBlocking {
        // The SDK mirror lost its used flags (pool unused), dashj has no chain:
        // only the frontier knows index 0 and 1 were paid.
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        file.writeText("frontier $contactUserId $selectedReference 1 ${sdkPool[1].toBase58()}\n")
        val h = harness(sdkAccount = account(addresses = pool(false, false, false, false)), paidFile = file)

        val next = h.provider.nextAddress(target)

        assertEquals(ContactAddressSource.SDK, next?.source)
        assertEquals(sdkPool[2], next?.address)
        assertEquals(2, next?.index)
    }

    @Test
    fun frontierDashjCannotBeAdvancedPast_noAddress() = runBlocking {
        // The frontier's recorded address is not dashj's key at its index (a
        // corrupt record, another xpub): dashj is not advanced, and nothing at
        // or below the frontier is served.
        val keys = List(5) { newAddress() }
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        file.writeText("frontier $contactUserId $selectedReference 2 ${newAddress().toBase58()}\n")
        val stuck = FakeDashjChain(keys, current = 0, markResult = FriendChainAccess.MarkResult.KEY_NOT_ON_CHAIN)

        assertNull(restartedProvider(stuck, file, sdkAccount = null).nextAddress(target))
        assertEquals(0, stuck.current)
    }

    @Test
    fun frontierDashjCannotBeAdvancedPast_indexUnknown_noAddress_andTheClaimIsRefused() = runBlocking {
        // PR #1608 review: keys 0..2 were paid and folded into the frontier
        // (only keys[2] is listed). A restored dashj chain is back at key 0,
        // the frontier is beyond its forward scan (the mark cannot advance
        // it), dashj's cursor cannot be read and the SDK is unavailable.
        // keys[0] passes the explicit exclusions; it must still never be paid.
        val keys = List(5) { newAddress() }
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        file.writeText("frontier $contactUserId $selectedReference 2 ${keys[2].toBase58()}\n")
        val restored = FakeDashjChain(keys, current = 0, markResult = FriendChainAccess.MarkResult.KEY_NOT_ON_CHAIN)
        val provider = ContactPaymentAddressProvider(
            cutoverCommitted = { true },
            sdkAccountReader = { _, _ -> null },
            dashjNextAddress = { _, _ -> restored.next() },
            dashjMarkUsed = { _, _, address -> restored.mark(address) },
            params = params,
            dashjSendingCursor = { _, _ -> null },
            paidAddresses = PaidContactAddressStore(file)
        )

        assertNull(provider.nextAddress(target))

        // ...and a payment to it built with no index (e.g. resolved before the
        // records were folded) is refused at the claim: nothing is sent.
        val stale = ContactPaymentAddress(target, keys[0], ContactAddressSource.DASHJ, index = null)
        var sent = false
        assertThrows(ContactAddressAlreadyPaidException::class.java) {
            runBlocking { provider.sendThenMarkUsed(stale) { sent = true } }
        }
        assertFalse("the send callback never ran", sent)
        assertFalse("nothing claimed", provider.isClaimedOrPaid(stale))
        assertEquals(0, restored.current)
    }

    @Test
    fun frontier_dashjAddressMovesBetweenTheIndexReads_noAddress() = runBlocking {
        // dashj's cursor is readable but its current key moves between the two
        // reads of [dashjServedIndex]: the served index is unknown, so with a
        // frontier the address is not used.
        val keys = List(5) { newAddress() }
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        file.writeText("frontier $contactUserId $selectedReference 2 ${keys[2].toBase58()}\n")
        var reads = 0
        val provider = ContactPaymentAddressProvider(
            cutoverCommitted = { true },
            sdkAccountReader = { _, _ -> null },
            dashjNextAddress = { _, _ -> keys[minOf(3 + reads++, keys.lastIndex)] },
            dashjMarkUsed = { _, _, _ -> FriendChainAccess.MarkResult.ALREADY_BEHIND_CURRENT },
            params = params,
            dashjSendingCursor = { _, _ -> 3 },
            paidAddresses = PaidContactAddressStore(file)
        )

        assertNull(provider.nextAddress(target))
    }

    @Test
    fun claimsRecordTheIndex_soTheFrontierOutlivesTheRecord() = runBlocking {
        var now = 1_000_000_000_000L
        val keys = List(4) { newAddress() }
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val saves = WalletSaveTracker()
        val chain = FakeDashjChain(keys, current = 0)
        val provider = restartedProvider(chain, file, sdkAccount = null, PaidContactAddressStore(file, { now }, saves))
        provider.sendThenMarkUsed(provider.nextAddress(target)) { "txid" }
        provider.sendThenMarkUsed(provider.nextAddress(target)) { "txid" }
        saves.saveCompleted(saves.saveStarting())
        now += PaidContactAddressStore.RETENTION_MS + 1
        PaidContactAddressStore(file, { now }).record(newAddress().toBase58()) // a write expires what is due

        val store = PaidContactAddressStore(file, { now })
        assertEquals(PaidContactAddressStore.Frontier(1, keys[1].toBase58()), store.foldedFrontier(contactUserId, selectedReference))
        // a wallet at key 0 again (an older backup): advanced past both payments
        val older = FakeDashjChain(keys, current = 0, jumps = true)
        assertEquals(keys[2], restartedProvider(older, file, sdkAccount = null, store).nextAddress(target)?.address)
    }

    // ── recovery: the chain is the source of truth (PR #1608 review, round 9) ──

    /**
     * What the SDK shows of the chain: [paid] addresses have a transaction
     * paying them in its store; [progress] and [durableSyncedHeight] are what
     * production reads for the recovery barrier, turned into a
     * [ContactChainScanState] by the production [contactChainScanState].
     */
    private class FakeChainView(
        caughtUp: Boolean = false,
        @Volatile var walletId: String? = "w1",
        /** False: the SDK's transaction store cannot be read. */
        @Volatile var readable: Boolean = true
    ) : ContactPaymentChainView {
        val paid: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

        /** The app's L1 sync progress, as [L1ShadowSyncService] reports it. */
        @Volatile var progress: ShadowSyncProgress = liveProgress(TIP, if (caughtUp) TIP else TIP - 5_000)

        /** The SDK store's `WalletEntity.syncedHeight`; null when unknown. */
        @Volatile var durableSyncedHeight: Long? = if (caughtUp) TIP else TIP - 5_000

        @Volatile var rescanArmed: Boolean = false

        /**
         * Why the contact accounts' history is not covered yet
         * ([ContactPaymentChainView.contactHistoryUncoveredBecause]); null: covered.
         */
        @Volatile var uncovered: String? = null

        /** Processed through the tip, or mid-scan thousands of blocks short. */
        var caughtUp: Boolean
            get() = contactChainScanState(progress, durableSyncedHeight, rescanArmed).processedThroughTip()
            set(value) = processedTo(if (value) progress.headerTarget else progress.headerTarget - 5_000)

        /** The engine's cursors and the store's synced height at [height], headers at [tip]. */
        fun processedTo(height: Long, tip: Long = progress.headerTarget) {
            progress = liveProgress(tip, height)
            durableSyncedHeight = height
        }

        /** Runs inside [scanState] before it answers: a test pauses a lookup here. */
        @Volatile var duringScanState: (suspend () -> Unit)? = null

        override fun walletId(): String? = walletId
        override suspend fun scanState(walletId: String): ContactChainScanState {
            val state = contactChainScanState(progress, durableSyncedHeight, rescanArmed)
            duringScanState?.invoke()
            return state
        }
        override suspend fun contactHistoryUncoveredBecause(walletId: String, ourUserId: String): String? = uncovered
        override fun paysAddress(address: Address): Boolean? =
            if (readable) address.toBase58() in paid else null
    }

    @Test
    fun recoveredWallet_sdkNotCaughtUp_noAddress_thenCaughtUp_theNextAddress() = runBlocking {
        // A wallet restored from its recovery phrase on a new device: no paid
        // file. Index 0 was paid from the lost device. dashj's restored chain
        // is at index 0 and the SDK mirror is absent; the SDK's scan has not
        // reached the tip, so nothing shows index 0 paid yet.
        val paidFile = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val dashj = FakeDashjChain(sdkPool, current = 0)
        val view = FakeChainView(caughtUp = false)
        var sdkAccount: SdkContactSendingAccount? = null
        fun provider() = ContactPaymentAddressProvider(
            cutoverCommitted = { true },
            sdkAccountReader = { _, _ -> sdkAccount },
            dashjNextAddress = { _, _ -> dashj.next() },
            dashjMarkUsed = { _, _, address -> dashj.mark(address) },
            params = params,
            dashjSendingCursor = { _, _ -> dashj.current },
            paidAddresses = PaidContactAddressStore(paidFile),
            chain = view
        )
        val provider = provider()

        assertNull("not the paid index-0 address from dashj", provider.nextAddress(target))
        assertTrue(dashj.marks.isEmpty())

        // The mirror appears with index 0 flagged used, but the scan is still
        // short of the tip: still nothing.
        sdkAccount = account(addresses = pool(true, false, false))
        assertNull(provider.nextAddress(target))

        // The scan reaches the tip: index 0 is paid on chain and flagged used.
        view.caughtUp = true
        view.paid += sdkPool[0].toBase58()
        val next = provider.nextAddress(target)
        assertEquals(ContactAddressSource.SDK, next?.source)
        assertEquals(sdkPool[1], next?.address)
        assertEquals(1, next?.index)

        // Known from now on, across a restart, even while the SDK trails the
        // tip again (a later block not scanned yet).
        view.caughtUp = false
        assertEquals(sdkPool[1], provider().nextAddress(target)?.address)
    }

    @Test
    fun recoveredWallet_aResetWhileAPromotionIsPaused_doesNotMarkTheClearedStore() = runBlocking {
        // A lookup reads full coverage and a through-tip scan state, then is
        // descheduled. The shadow reset (or a wallet re-creation, same id)
        // drops the marker, deletes the SDK's transaction store and drops it
        // again. The lookup resumes with evidence about the deleted store and
        // must not record the marker for the empty one (PR #1608 review, round 13).
        val paidFile = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val store = PaidContactAddressStore(paidFile)
        val view = FakeChainView(caughtUp = true)
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        view.duringScanState = {
            paused.complete(Unit)
            resume.await()
        }
        val provider = restartedProvider(FakeDashjChain(sdkPool, current = 0), paidFile, account(), store, view)

        val lookup = async(Dispatchers.Default) { provider.nextAddress(target) }
        paused.await()
        assertTrue("dropped before the store is deleted", store.forgetPaidHistory("w1"))
        view.caughtUp = false // the store is rebuilt from the birth height
        assertTrue("dropped again after", store.forgetPaidHistory("w1"))
        view.duringScanState = null
        resume.complete(Unit)

        assertNull(lookup.await())
        assertFalse(store.paidHistoryKnown("w1"))
        assertFalse("not on disk", PaidContactAddressStore(paidFile).paidHistoryKnown("w1"))

        // The rebuilt store, once through the tip, is promoted as usual.
        view.caughtUp = true
        assertEquals(sdkPool[1], provider.nextAddress(target)?.address)
        assertTrue(PaidContactAddressStore(paidFile).paidHistoryKnown("w1"))
    }

    @Test
    fun recoveredWallet_aLookupStartingWhileTheStoreIsBeingDeleted_doesNotMarkIt() = runBlocking {
        // PR #1608 review, round 14: the marker has been dropped, but the SDK's
        // transaction store is not deleted yet and still reads through the tip.
        // A lookup that starts now reads the current generation; it must not
        // record the marker, or a process that dies before the second drop
        // keeps a marker vouching for the deleted store.
        val paidFile = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val store = PaidContactAddressStore(paidFile)
        val view = FakeChainView(caughtUp = true)
        val provider = restartedProvider(FakeDashjChain(sdkPool, current = 0), paidFile, account(), store, view)
        assertTrue(store.beginHistoryInvalidation("w1"))

        assertNull(provider.nextAddress(target))
        assertFalse(store.paidHistoryKnown("w1"))
        assertFalse("not on disk", PaidContactAddressStore(paidFile).paidHistoryKnown("w1"))

        view.caughtUp = false // the store is rebuilt from the birth height
        assertTrue(store.endHistoryInvalidation("w1"))
        assertNull(provider.nextAddress(target))

        // The rebuilt store, once through the tip, is promoted as usual.
        view.caughtUp = true
        assertEquals(sdkPool[1], provider.nextAddress(target)?.address)
        assertTrue(PaidContactAddressStore(paidFile).paidHistoryKnown("w1"))
    }

    @Test
    fun recoveredWallet_noSdkWalletLoaded_noAddress() = runBlocking {
        val view = FakeChainView(caughtUp = true, walletId = null)
        val provider = restartedProvider(
            FakeDashjChain(sdkPool, current = 0),
            File(tmp.root, PaidContactAddressStore.FILE_NAME),
            account(),
            chainView = view
        )

        assertNull(provider.nextAddress(target))
    }

    @Test
    fun walletChange_historyUnknownAgain_untilTheNewWalletsScanIsCaughtUp() = runBlocking {
        val saves = WalletSaveTracker()
        val paidFile = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val store = PaidContactAddressStore(paidFile, walletSaves = saves)
        val view = FakeChainView(caughtUp = true)
        val provider = restartedProvider(
            FakeDashjChain(sdkPool, current = 0), paidFile, account(), store, view
        )
        assertEquals(sdkPool[1], provider.nextAddress(target)?.address)

        // A restore replaces the wallet; the SDK rescans it.
        saves.walletChanged()
        view.caughtUp = false

        assertNull(provider.nextAddress(target))
        view.caughtUp = true
        assertEquals(sdkPool[1], provider.nextAddress(target)?.address)
    }

    @Test
    fun sdkPick_paidOnChainButNotFlaggedUsed_isSkipped() = runBlocking {
        // The mirror's used flags lag (or the account was built after the
        // payment was processed): the chain still shows index 1 paid.
        val view = FakeChainView(caughtUp = true).apply { paid += sdkPool[1].toBase58() }
        val provider = restartedProvider(
            FakeDashjChain(sdkPool, current = 0),
            File(tmp.root, PaidContactAddressStore.FILE_NAME),
            account(addresses = pool(true, false, false, false)),
            chainView = view
        )

        val next = provider.nextAddress(target)

        assertEquals(ContactAddressSource.SDK, next?.source)
        assertEquals(sdkPool[2], next?.address)
    }

    @Test
    fun dashjFallback_recoveredChainAtPaidIndices_isAdvancedPastWhatTheChainShowsPaid() = runBlocking {
        // A channel the SDK cannot pay on (marked broken): dashj's restored
        // chain is at index 0; indices 0 and 1 were paid before the recovery.
        val keys = List(4) { newAddress() }
        val dashj = FakeDashjChain(keys, current = 0)
        val view = FakeChainView(caughtUp = true).apply {
            paid += keys[0].toBase58()
            paid += keys[1].toBase58()
        }
        val provider = restartedProvider(
            dashj,
            File(tmp.root, PaidContactAddressStore.FILE_NAME),
            account(paymentChannelBroken = true),
            chainView = view
        )

        val next = provider.nextAddress(target)

        assertEquals(ContactAddressSource.DASHJ, next?.source)
        assertEquals(keys[2], next?.address)
        assertEquals(listOf(keys[0], keys[1]), dashj.marks)
    }

    @Test
    fun postCutover_transactionStoreUnreadable_noAddress_fromEitherStack() = runBlocking {
        val view = FakeChainView(caughtUp = true, readable = false)
        val sdk = restartedProvider(
            FakeDashjChain(sdkPool, current = 0),
            File(tmp.root, "sdk.txt"),
            account(),
            chainView = view
        )
        val dashjFallback = restartedProvider(
            FakeDashjChain(sdkPool, current = 0),
            File(tmp.root, "dashj.txt"),
            account(paymentChannelBroken = true),
            chainView = view
        )

        assertNull(sdk.nextAddress(target))
        assertNull(dashjFallback.nextAddress(target))
    }

    @Test
    fun preCutover_dashjAddressPaidOnChain_isSkipped_andAnUnreadableStoreIsNoObstacle() = runBlocking {
        val keys = List(3) { newAddress() }
        val dashj = FakeDashjChain(keys, current = 0)
        val view = FakeChainView(caughtUp = false).apply { paid += keys[0].toBase58() }
        fun provider() = ContactPaymentAddressProvider(
            cutoverCommitted = { false },
            sdkAccountReader = { _, _ -> null },
            dashjNextAddress = { _, _ -> dashj.next() },
            dashjMarkUsed = { _, _, address -> dashj.mark(address) },
            params = params,
            dashjSendingCursor = { _, _ -> dashj.current },
            chain = view
        )

        assertEquals("the scan state does not gate dashj, the live engine", keys[1], provider().nextAddress(target)?.address)
        view.readable = false
        assertEquals(keys[1], provider().nextAddress(target)?.address)
    }

    // ── recovery barrier and claim-time chain check (PR #1608 review, round 10) ──

    @Test
    fun scanState_cursorsTwoBlocksShortOfTheTip_isNotProcessedThroughTheTip() {
        val h = TIP
        // Production values: headers at H, the filter scan and the wallet's
        // committed cursor at H-2 — what the balance-save gate accepts.
        val progress = liveProgress(tip = h, processed = h - 2)
        assertTrue("the balance-save gate passes", progress.scanCaughtUpToTip && !progress.blockPipelineLagging)

        assertFalse(contactChainScanState(progress, durableSyncedHeight = h - 2, rescanArmed = false).processedThroughTip())
        // The engine committed H, but the SDK's store has not: not yet queryable.
        assertFalse(
            contactChainScanState(progress.copy(walletSyncedHeight = h), durableSyncedHeight = h - 2, rescanArmed = false)
                .processedThroughTip()
        )
        assertFalse(
            contactChainScanState(progress.copy(walletSyncedHeight = h), durableSyncedHeight = null, rescanArmed = false)
                .processedThroughTip()
        )
        val atTip = liveProgress(tip = h, processed = h)
        assertTrue(contactChainScanState(atTip, durableSyncedHeight = h, rescanArmed = false).processedThroughTip())
        assertFalse(contactChainScanState(atTip, durableSyncedHeight = h, rescanArmed = true).processedThroughTip())
        assertFalse(
            "below the tip first seen unknown",
            contactChainScanState(atTip, durableSyncedHeight = h, rescanArmed = false).processedThroughTip(floor = h + 1)
        )
        assertFalse(
            "no header tip known",
            contactChainScanState(ShadowSyncProgress.IDLE, durableSyncedHeight = h, rescanArmed = false).processedThroughTip()
        )
    }

    @Test
    fun recoveredWallet_paymentInABlockTheCursorsHaveNotReached_noAddress_untilProcessedThroughTheTip() = runBlocking {
        // Recovered on a new device: no paid file. Index 0 is flagged used;
        // index 1 was paid from the lost device in block H-1, which the
        // cursors (H-2) have not processed — nothing shows it paid yet.
        val paidFile = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val view = FakeChainView().apply { processedTo(TIP - 2, tip = TIP) }
        val provider = restartedProvider(
            FakeDashjChain(sdkPool, current = 0),
            paidFile,
            account(addresses = pool(true, false, false, false)),
            chainView = view
        )

        assertNull("index 1 must not be offered", provider.nextAddress(target))
        assertFalse(PaidContactAddressStore(paidFile).paidHistoryKnown("w1"))

        // Processed through H: the payment in H-1 is in the store.
        view.paid += sdkPool[1].toBase58()
        view.processedTo(TIP)
        val next = provider.nextAddress(target)
        assertEquals(sdkPool[2], next?.address)
        assertEquals(2, next?.index)
        assertTrue(PaidContactAddressStore(paidFile).paidHistoryKnown("w1"))
    }

    @Test
    fun recoveredWallet_headerTipLowerThanWhenHistoryWasFirstUnknown_waitsForTheEarlierTip() = runBlocking {
        val view = FakeChainView().apply { processedTo(TIP - 2, tip = TIP) }
        val provider = restartedProvider(
            FakeDashjChain(sdkPool, current = 0),
            File(tmp.root, PaidContactAddressStore.FILE_NAME),
            account(),
            chainView = view
        )
        assertNull(provider.nextAddress(target)) // history first seen unknown at tip H

        // The header chain now reports a lower tip (a reset header store) and
        // the cursors have reached it: still short of H.
        view.processedTo(TIP - 10, tip = TIP - 10)
        assertNull(provider.nextAddress(target))

        view.processedTo(TIP, tip = TIP)
        assertEquals(sdkPool[1], provider.nextAddress(target)?.address)
    }

    @Test
    fun claim_addressPaidOnChainByClaimTime_isRefused_nothingClaimedOrSent() = runBlocking {
        val paidFile = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val store = PaidContactAddressStore(paidFile)
        val view = FakeChainView(caughtUp = true)
        val provider = restartedProvider(FakeDashjChain(sdkPool, current = 0), paidFile, account(), store, view)
        val resolved = provider.nextAddress(target)
        assertEquals(sdkPool[1], resolved?.address)

        // The store shows it paid by the time the send claims it (a payment
        // imported after the resolution).
        view.paid += sdkPool[1].toBase58()
        var sent = false
        assertThrows(ContactAddressAlreadyPaidException::class.java) {
            runBlocking { provider.sendThenMarkUsed(resolved) { sent = true } }
        }
        assertFalse(sent)
        assertFalse(store.contains(sdkPool[1].toBase58()))
    }

    @Test
    fun claim_chainCheckedInsideTheClaimGate() = runBlocking {
        val view = FakeChainView(caughtUp = true)
        val provider = restartedProvider(
            FakeDashjChain(sdkPool, current = 0),
            File(tmp.root, PaidContactAddressStore.FILE_NAME),
            account(),
            chainView = view
        )
        val resolved = provider.nextAddress(target)

        var sent = false
        assertThrows(ContactAddressAlreadyPaidException::class.java) {
            runBlocking {
                provider.sendThenMarkUsed(
                    resolved,
                    claimGate = { claim ->
                        // imported while the send waited for the gate
                        view.paid += sdkPool[1].toBase58()
                        claim()
                    }
                ) { sent = true }
            }
        }
        assertFalse(sent)
    }

    @Test
    fun claim_addressResolvedBeforeCatchUp_isRefused_untilTheChainVouchesForIt() = runBlocking {
        // Resolved from dashj before the cutover committed, while the SDK
        // had not processed the recovered wallet: dashj's restored key 0.
        val keys = List(3) { newAddress() }
        val dashj = FakeDashjChain(keys, current = 0)
        val view = FakeChainView(caughtUp = false)
        var cutover = false
        val store = PaidContactAddressStore(File(tmp.root, PaidContactAddressStore.FILE_NAME))
        val provider = ContactPaymentAddressProvider(
            cutoverCommitted = { cutover },
            sdkAccountReader = { _, _ -> null },
            dashjNextAddress = { _, _ -> dashj.next() },
            dashjMarkUsed = { _, _, address -> dashj.mark(address) },
            params = params,
            dashjSendingCursor = { _, _ -> dashj.current },
            paidAddresses = store,
            chain = view
        )
        val resolved = provider.nextAddress(target)
        assertEquals(keys[0], resolved?.address)
        cutover = true

        var sends = 0
        assertThrows("history unknown", ContactAddressAlreadyPaidException::class.java) {
            runBlocking { provider.sendThenMarkUsed(resolved) { sends++ } }
        }
        // Processed through the tip: key 0 was paid before the recovery.
        view.paid += keys[0].toBase58()
        view.caughtUp = true
        assertThrows("paid on chain", ContactAddressAlreadyPaidException::class.java) {
            runBlocking { provider.sendThenMarkUsed(resolved) { sends++ } }
        }
        assertEquals(0, sends)
        assertFalse(store.contains(keys[0].toBase58()))
    }

    @Test
    fun claim_unreadableTransactionStorePostCutover_isRefused() = runBlocking {
        val view = FakeChainView(caughtUp = true)
        val provider = restartedProvider(
            FakeDashjChain(sdkPool, current = 0),
            File(tmp.root, PaidContactAddressStore.FILE_NAME),
            account(),
            chainView = view
        )
        val resolved = provider.nextAddress(target)
        view.readable = false

        var sent = false
        assertThrows(ContactAddressAlreadyPaidException::class.java) {
            runBlocking { provider.sendThenMarkUsed(resolved) { sent = true } }
        }
        assertFalse(sent)
    }

    @Test
    fun manyPaymentsPaidOnChainBeyondOneLookupsBound_repeatedLookupsReachTheUnusedAddress() = runBlocking {
        // A recovered channel: indices 0 .. paidCount-1 were paid before the
        // recovery; the SDK mirror flags none of them, the chain shows all.
        val paidCount = 2 * ContactPaymentAddressProvider.MAX_DASHJ_PAID_SKIPS + 3
        val keys = List(paidCount + 3) { newAddress() }
        val poolRows = keys.mapIndexed { i, a -> SdkContactPoolAddress(a.toBase58(), i, isUsed = false, poolTypeTag = 2) }
        val view = FakeChainView(caughtUp = true).apply { keys.take(paidCount).forEach { paid += it.toBase58() } }
        val paidFile = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val dashj = FakeDashjChain(keys, current = 0)
        fun lookup() = runBlocking {
            // a fresh provider and store each time: the progress is on disk
            restartedProvider(dashj, paidFile, account(addresses = poolRows), chainView = view).nextAddress(target)
        }

        val results = mutableListOf(lookup())
        while (results.last() == null && results.size < 10) {
            results += lookup()
        }
        val attempts = results.size

        val found = results.last()
        assertEquals("reached the first unused address", keys[paidCount], found?.address)
        assertEquals(paidCount, found?.index)
        assertTrue("more than one lookup was needed", attempts > 1)
        assertTrue("never a paid address", results.filterNotNull().none { it.address.toBase58() in view.paid })
        // Each lookup recorded what it verified, so each one made progress.
        // (each lookup checks up to MAX_DASHJ_PAID_SKIPS + 1 addresses)
        assertEquals(paidCount / (ContactPaymentAddressProvider.MAX_DASHJ_PAID_SKIPS + 1) + 1, attempts)
        val store = PaidContactAddressStore(paidFile)
        assertEquals(paidCount - 1, store.paidFrontier(contactUserId, selectedReference))
        assertTrue(keys.take(paidCount).all { store.contains(it.toBase58()) })
    }

    // ── contact-account coverage (PR #1608 review, round 11) ──

    @Test
    fun recoveredWallet_cursorsAtTheTip_contactAccountsNotCovered_contactPaymentsBlocked_untilCovered() = runBlocking {
        // Recovered on a new device: no paid file. dashj's key 0 was paid from
        // the lost device by a transaction funded entirely from a DIP-15
        // receiving account (no BIP44 change). L1 sync started while contact
        // bring-up was still running: that receiving account and our sending
        // account to the payee are not registered, so the SDK's store has no
        // trace of the payment — yet both cursors are at the tip.
        val keys = List(3) { newAddress() }
        val dashj = FakeDashjChain(keys, current = 0)
        val paidFile = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        val store = PaidContactAddressStore(paidFile)
        val view = FakeChainView(caughtUp = true).apply { uncovered = "the SDK's contact discovery has not finished" }
        var cutover = false
        val provider = ContactPaymentAddressProvider(
            cutoverCommitted = { cutover },
            sdkAccountReader = { _, _ -> null },
            dashjNextAddress = { _, _ -> dashj.next() },
            dashjMarkUsed = { _, _, address -> dashj.mark(address) },
            params = params,
            dashjSendingCursor = { _, _ -> dashj.current },
            paidAddresses = store,
            chain = view
        )
        // A payee resolved before the cutover committed (dashj live): key 0.
        val resolvedEarlier = provider.nextAddress(target)
        assertEquals(keys[0], resolvedEarlier?.address)
        cutover = true
        var sends = 0
        fun assertSendRefused(payment: ContactPaymentAddress?) {
            assertThrows(ContactAddressAlreadyPaidException::class.java) {
                runBlocking { provider.sendThenMarkUsed(payment) { sends++ } }
            }
        }

        assertNull("contact discovery unfinished", provider.nextAddress(target))
        assertSendRefused(resolvedEarlier)

        // Discovered, but a receiving account is not registered or not yet
        // covered by the SDK's backfill.
        view.uncovered = "1 receiving account(s) not yet covered by the SDK's backfill record"
        assertNull(provider.nextAddress(target))
        assertSendRefused(resolvedEarlier)

        // Covered: the SDK rewound for the account and its replay climbs.
        view.uncovered = null
        view.processedTo(TIP - 200_000)
        assertNull("the covering replay has not reached the tip", provider.nextAddress(target))
        assertSendRefused(resolvedEarlier)

        assertEquals(0, sends)
        assertFalse(store.contains(keys[0].toBase58()))
        assertFalse(PaidContactAddressStore(paidFile).paidHistoryKnown("w1"))
        assertTrue("dashj never served past what the store could vouch for", dashj.marks.isEmpty())

        // The replay reaches the tip and has imported the payment.
        view.paid += keys[0].toBase58()
        view.processedTo(TIP)
        assertSendRefused(resolvedEarlier)
        val next = provider.nextAddress(target)
        assertEquals(keys[1], next?.address)
        assertTrue(PaidContactAddressStore(paidFile).paidHistoryKnown("w1"))
        provider.sendThenMarkUsed(next) { sends++ }
        assertEquals(1, sends)
    }

    private val ourIdBytes = ByteArray(32) { 1 }
    private val contactA = ByteArray(32) { 2 }
    private val contactB = ByteArray(32) { 3 }
    private fun hex(id: ByteArray) = org.bitcoinj.core.Utils.HEX.encode(id)

    @Test
    fun contactHistoryUncovered_needsDiscoveryAccountsAndBackfillCoverage() {
        val a = hex(contactA)
        val b = hex(contactB)
        fun uncovered(
            discovered: Boolean = true,
            sent: Set<String> = setOf(a, b),
            accounts: List<Pair<String, Int>> = listOf(a to 0, b to 0),
            coverSet: Map<Pair<String, Int>, Long>? = mapOf((a to 0) to 499L, (b to 0) to 700L)
        ) = contactHistoryUncoveredBecause(
            discoveryCompleted = discovered,
            sentRequestContacts = sent,
            requestFloors = mapOf(a to 500L, b to 700L),
            birthHeight = 100,
            receivalAccounts = accounts,
            coverSet = coverSet
        )

        assertNull("discovered, registered and covered", uncovered())
        assertTrue(uncovered(discovered = false)!!.contains("discovery"))
        assertTrue(
            "a contact we sent a request to has no receiving account",
            uncovered(accounts = listOf(a to 0))!!.contains("no receiving account")
        )
        assertTrue(uncovered(coverSet = null)!!.contains("cannot be read"))
        assertTrue(
            "registered after the record was written",
            uncovered(coverSet = mapOf((a to 0) to 499L))!!.contains("not yet covered")
        )
        assertTrue(
            "covered only from above the contact's earliest request",
            uncovered(coverSet = mapOf((a to 0) to 501L, (b to 0) to 700L))!!.contains("not yet covered")
        )
        assertTrue(
            "coverage is per receiving account, not per contact",
            uncovered(
                accounts = listOf(a to 0, a to 1, b to 0),
                coverSet = mapOf((a to 0) to 499L, (b to 0) to 700L)
            )!!.contains("not yet covered")
        )
        assertNull(
            "no contacts: nothing to cover",
            uncovered(sent = emptySet(), accounts = emptyList(), coverSet = emptyMap())
        )
        assertNull(
            "covered from the wallet's birth checkpoint (the SDK's checkpoint for a rotated request)",
            contactHistoryUncoveredBecause(
                discoveryCompleted = true,
                sentRequestContacts = setOf(a),
                requestFloors = mapOf(a to 50L),
                birthHeight = 100,
                receivalAccounts = listOf(a to 0),
                coverSet = mapOf((a to 0) to 99L)
            )
        )
    }

    private fun coverEntry(owner: ByteArray, contact: ByteArray, index: Int?, coveredFrom: Int): ByteArray {
        fun le(v: Int) = ByteArray(4) { i -> (v ushr (8 * i)).toByte() }
        return owner + contact + (index?.let(::le) ?: ByteArray(0)) + le(coveredFrom)
    }

    @Test
    fun decodeCoverSet_readsOurEntries_andRefusesWhatItCannotDecode() {
        val other = ByteArray(32) { 9 }
        val blob = coverEntry(ourIdBytes, contactA, 0, 1_226_329) +
            coverEntry(ourIdBytes, contactB, 1, 0x01020304) +
            coverEntry(other, contactA, 0, 5)
        assertEquals(
            mapOf((hex(contactA) to 0) to 1_226_329L, (hex(contactB) to 1) to 0x01020304L),
            decodeDashPayBackfillCoverSet(blob, ourIdBytes)
        )
        assertEquals(emptyMap<Pair<String, Int>, Long>(), decodeDashPayBackfillCoverSet(null, ourIdBytes))
        assertEquals(emptyMap<Pair<String, Int>, Long>(), decodeDashPayBackfillCoverSet(ByteArray(0), ourIdBytes))
        assertNull("not a whole number of entries", decodeDashPayBackfillCoverSet(blob.copyOf(blob.size - 1), ourIdBytes))
        // A height past Int.MAX_VALUE stays unsigned.
        assertEquals(
            mapOf((hex(contactA) to 0) to 0xFFFF_FFFFL),
            decodeDashPayBackfillCoverSet(coverEntry(ourIdBytes, contactA, 0, -1), ourIdBytes)
        )
    }

    @Test
    fun decodeCoverSet_int24ToInt26Layout_readsOnAccountIndexZero() {
        val legacy = coverEntry(ourIdBytes, contactA, null, 1_000) + coverEntry(ourIdBytes, contactB, null, 2_000)
        assertEquals(
            mapOf((hex(contactA) to 0) to 1_000L, (hex(contactB) to 0) to 2_000L),
            decodeDashPayBackfillCoverSet(legacy, ourIdBytes)
        )
        // 18 legacy entries (1224 bytes) are also 17 current ones: the
        // decoding whose every owner is ours wins.
        val ambiguous = (0 until 18).fold(ByteArray(0)) { acc, n ->
            acc + coverEntry(ourIdBytes, ByteArray(32) { n.toByte() }, null, 1_000 + n)
        }
        assertEquals(1224, ambiguous.size)
        val decoded = decodeDashPayBackfillCoverSet(ambiguous, ourIdBytes)
        assertEquals(18, decoded?.size)
        assertEquals(1_017L, decoded?.get(hex(ByteArray(32) { 17 }) to 0))
    }
}
