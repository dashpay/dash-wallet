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
import org.junit.Test

class ContactPaymentAddressProviderTest {

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
        val params: org.bitcoinj.core.NetworkParameters
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
            params = params
        )
    }

    private fun harness(
        cutover: Boolean = true,
        sdkAccount: SdkContactSendingAccount? = account()
    ) = Harness(cutover, sdkAccount, dashjAddress, params)

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
}
