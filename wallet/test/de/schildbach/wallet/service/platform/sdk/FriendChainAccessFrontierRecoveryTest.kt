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
import kotlinx.coroutines.runBlocking
import org.bitcoinj.core.Address
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.evolution.EvolutionContact
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.FriendChainAccess
import org.bitcoinj.wallet.FriendKeyChain
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletProtobufSerializer
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * [ContactPaymentAddressProvider]'s dashj fallback against a real dashj wallet
 * with a DIP-15 SENDING chain, wired to [FriendChainAccess] exactly as
 * IdentityRepositoryImpl wires it (PR #1608 review): a channel whose paid
 * records folded into a frontier far ahead of a restored chain — beyond
 * [FriendChainAccess.markSendingAddressUsed]'s forward scan — must still
 * reach an address above the frontier, never one at or below it.
 */
class FriendChainAccessFrontierRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val params = TestNet3Params.get()

    // Fixture phrase already used by other tests in this repo — carries no funds.
    private val mnemonic =
        "weapon elder job emotion aunt include deer owner salon census half divide"

    private val ourId = Sha256Hash.wrap(ByteArray(32) { 1 })
    private val contactId = Sha256Hash.wrap(ByteArray(32) { 2 })
    private val accountReference = 0x55

    /** The chain key, exactly as BlockchainIdentity.addPaymentKeyChainToContact builds it. */
    private val contact = EvolutionContact(ourId, 0, contactId, accountReference)

    private val target = ContactPaymentTarget(
        Identifier.from(ourId.bytes).toString(),
        Identifier.from(contactId.bytes).toString(),
        accountReference
    )

    /** The contact's account xpub, built as production decrypts it (see FriendChainAccessMarkUsedTest). */
    private val contactXpub: String = run {
        val key = HDKeyDerivation.deriveChildKey(
            HDKeyDerivation.createMasterPrivateKey(ByteArray(32) { 7 }),
            ChildNumber(0, true)
        )
        val compact = ByteArray(4) { 9 } + key.chainCode + key.pubKey
        DeterministicKey.deserializeContactPub(params, compact).serializePubB58(params)
    }

    /** The SDK cannot answer for the contact: it marked the channel broken. */
    private val brokenSdkChannel = SdkContactSendingAccount(
        incomingRequestFound = true,
        paymentChannelBroken = true,
        externalAccountReference = accountReference,
        addresses = null
    )

    @Before
    fun propagateDashjContext() {
        Context.propagate(Context.getOrCreate(params))
    }

    private fun newWallet(): Wallet = Wallet.fromSeed(
        params,
        DeterministicSeed(mnemonic, null, "", System.currentTimeMillis() / 1000),
        Script.ScriptType.P2PKH
    ).also {
        it.addSendingToFriendKeyChain(contactXpub, ourId, 0, contactId, accountReference)
    }

    /** The contact's i-th payment address, derived from their xpub alone. */
    private fun contactAddress(i: Int): Address = Address.fromKey(
        params,
        HDKeyDerivation.deriveChildKey(DeterministicKey.deserializeB58(contactXpub, params), ChildNumber(i, false))
    )

    private fun reload(wallet: Wallet): Wallet {
        val serializer = WalletProtobufSerializer()
        return serializer.readWallet(params, null, serializer.walletToProto(wallet))
    }

    /** The SDK's view of the chain: caught up and covered; [paid] holds the payments it has seen. */
    private class ChainView : ContactPaymentChainView {
        val paid: MutableSet<String> = Collections.synchronizedSet(HashSet())
        override fun walletId(): String = "w1"
        override suspend fun scanState(walletId: String) =
            ContactChainScanState(tipHeight = 1, processedHeight = 1, scanSettled = true)
        override suspend fun contactHistoryUncoveredBecause(walletId: String, ourUserId: String): String? = null
        override fun paysAddress(address: Address): Boolean = address.toBase58() in paid
    }

    /** The provider over [wallet], wired to [FriendChainAccess] as IdentityRepositoryImpl does. */
    private fun provider(
        wallet: Wallet,
        store: PaidContactAddressStore,
        chain: ContactPaymentChainView
    ) = ContactPaymentAddressProvider(
        cutoverCommitted = { true },
        sdkAccountReader = { _, _ -> brokenSdkChannel },
        dashjNextAddress = { _, _ -> wallet.currentAddress(contact, FriendKeyChain.KeyChainType.SENDING_CHAIN) },
        dashjMarkUsed = { _, _, address -> FriendChainAccess.markSendingAddressUsed(wallet, contact, address.hash) },
        params = params,
        dashjSendingCursor = { _, _ -> FriendChainAccess.sendingCursorOrNull(wallet, contact) },
        dashjAdvancePast = { _, _, index, address ->
            FriendChainAccess.advanceSendingChainPast(wallet, contact, index, address.hash)
        },
        paidAddresses = store,
        chain = chain
    )

    @Test
    fun distantFoldedFrontier_unbroadcastEarlierAddress_brokenSdk_restoredChain_reachesAboveTheFrontier() =
        runBlocking {
            var now = 1_000_000_000_000L
            val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
            val chain = ChainView()

            // ── the primary wallet ──
            val saves = WalletSaveTracker()
            val primary = newWallet()
            val store = PaidContactAddressStore(file, { now }, saves)
            val before = provider(primary, store, chain)

            // 1. An ambiguous send consumes index 0; it never reaches the chain.
            val ambiguous = requireNotNull(before.nextAddress(target))
            assertEquals(0, ambiguous.index)
            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    before.sendThenMarkUsed(ambiguous) { signals ->
                        signals.broadcast(null)
                        throw IllegalStateException("broadcast outcome unknown")
                    }
                }
            }

            // 2. Later payments advance the channel to index 150.
            repeat(150) {
                val payment = requireNotNull(before.nextAddress(target))
                before.sendThenMarkUsed(payment) { "txid" }
                chain.paid += payment.address.toBase58()
            }
            assertEquals(151, FriendChainAccess.sendingCursorOrNull(primary, contact))
            // dashj's advances reach disk; past the retention the settled
            // records fold into one frontier at 150 (any write expires them).
            saves.saveCompleted(saves.saveStarting())
            now += PaidContactAddressStore.RETENTION_MS + TimeUnit.DAYS.toMillis(1)
            store.record(Address.fromKey(params, ECKey()).toBase58())
            val folded = PaidContactAddressStore(file, { now })
            assertEquals(
                PaidContactAddressStore.Frontier(150, contactAddress(150).toBase58()),
                folded.foldedFrontier(target.contactUserId, accountReference)
            )
            assertFalse(
                "index 0 is no longer excluded on its own",
                contactAddress(0).toBase58() in folded.paid()
            )

            // 3. Local recovery from an older key backup: dashj is back at index 0,
            //    the paid-address file survives; the SDK channel stays broken.
            val restoredSaves = WalletSaveTracker()
            val restoredStore = PaidContactAddressStore(file, { now }, restoredSaves)
            restoredSaves.walletChanged()
            val restored = newWallet()
            assertEquals(0, FriendChainAccess.sendingCursorOrNull(restored, contact))
            val after = provider(restored, restoredStore, chain)

            // 4. Repeated resolution reaches an address above the frontier.
            val resolved = (1..3).firstNotNullOfOrNull { after.nextAddress(target) }
            assertNotNull("resolution never reached an address", resolved)
            requireNotNull(resolved)
            assertEquals(ContactAddressSource.DASHJ, resolved.source)
            assertEquals(151, resolved.index)
            assertEquals(contactAddress(151), resolved.address)
            // ...and it is payable: the claim accepts it above the frontier.
            after.sendThenMarkUsed(resolved) { "txid" }
            assertEquals(152, FriendChainAccess.sendingCursorOrNull(reload(restored), contact))
            assertEquals(contactAddress(152), after.nextAddress(target)?.address)
        }

    @Test
    fun liveRecordAboveADistantFrontier_isPlacedOnTheRestoredChain_andSettles() = runBlocking {
        // A pending record at 200 above a frontier at 150; the restored chain is
        // at 0, so 200 is beyond the mark's forward scan from there. Advancing
        // to the frontier first brings it within reach: dashj is moved past it
        // and the record can settle (and later fold) instead of waiting forever.
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        file.writeText(
            "frontier ${target.contactUserId} $accountReference 150 ${contactAddress(150).toBase58()}\n" +
                "1 ${contactAddress(200).toBase58()} pending ${target.contactUserId} $accountReference 200\n"
        )
        val saves = WalletSaveTracker()
        val store = PaidContactAddressStore(file, { 2L }, saves)
        val restored = newWallet()

        val next = provider(restored, store, ChainView()).nextAddress(target)

        assertEquals(contactAddress(201), next?.address)
        assertEquals(201, next?.index)
        saves.saveCompleted(saves.saveStarting())
        assertEquals(true, store.isSettled(contactAddress(200).toBase58()))
    }

    @Test
    fun frontierAddressNotTheKeyAtItsIndex_dashjIsNotAdvanced_andNothingAtOrBelowItIsServed() = runBlocking {
        // The frontier names index 150 but records the chain's address at 149:
        // the index cannot be trusted, so dashj is not moved and the restored
        // chain's index 0 is never handed out.
        val file = File(tmp.root, PaidContactAddressStore.FILE_NAME)
        file.writeText("frontier ${target.contactUserId} $accountReference 150 ${contactAddress(149).toBase58()}\n")
        val restored = newWallet()
        val provider = provider(restored, PaidContactAddressStore(file), ChainView())

        repeat(3) { assertNull(provider.nextAddress(target)) }

        assertEquals(0, FriendChainAccess.sendingCursorOrNull(restored, contact))
        assertEquals(0, FriendChainAccess.sendingCursorOrNull(reload(restored), contact))
        val stale = ContactPaymentAddress(target, contactAddress(0), ContactAddressSource.DASHJ, index = 0)
        var sent = false
        assertThrows(ContactAddressAlreadyPaidException::class.java) {
            runBlocking { provider.sendThenMarkUsed(stale) { sent = true } }
        }
        assertFalse("the send callback never ran", sent)
        assertEquals(0, FriendChainAccess.sendingCursorOrNull(restored, contact))
    }

    // ── one xpub, several references (PR #1608 review, round 15) ────────

    private val rotatedReference = accountReference + (1 shl 28)
    private val rotatedContact = EvolutionContact(ourId, 0, contactId, rotatedReference)
    private val rotatedTarget = target.copy(accountReference = rotatedReference)

    /** Another contact xpub, for a re-sent request that is a genuinely new chain. */
    private val otherXpub: String = run {
        val key = HDKeyDerivation.deriveChildKey(
            HDKeyDerivation.createMasterPrivateKey(ByteArray(32) { 8 }),
            ChildNumber(0, true)
        )
        DeterministicKey.deserializeContactPub(params, ByteArray(4) { 9 } + key.chainCode + key.pubKey)
            .serializePubB58(params)
    }

    /** The provider for the rotated reference over [wallet], wired as IdentityRepositoryImpl does. */
    private fun rotatedProvider(wallet: Wallet, store: PaidContactAddressStore) = ContactPaymentAddressProvider(
        cutoverCommitted = { true },
        sdkAccountReader = { _, _ -> brokenSdkChannel },
        dashjNextAddress = { _, _ -> wallet.currentAddress(rotatedContact, FriendKeyChain.KeyChainType.SENDING_CHAIN) },
        dashjMarkUsed = { _, _, address ->
            FriendChainAccess.markSendingAddressUsed(wallet, rotatedContact, address.hash)
        },
        params = params,
        dashjSendingCursor = { _, _ -> FriendChainAccess.sendingCursorOrNull(wallet, rotatedContact) },
        dashjAdvancePast = { _, _, index, address ->
            FriendChainAccess.advanceSendingChainPast(wallet, rotatedContact, index, address.hash)
        },
        dashjAddressAt = { _, _, index ->
            FriendChainAccess.sendingPubKeyHashAtOrNull(wallet, rotatedContact, index)
                ?.let { Address.fromPubKeyHash(params, it) }
        },
        paidAddresses = store,
        chain = ChainView()
    )

    /** Index 0 (ambiguous, never on chain) and 1 (paid) folded into the old reference's frontier at 1. */
    private fun foldedFrontierFile(): File = File(tmp.root, PaidContactAddressStore.FILE_NAME).apply {
        writeText("frontier ${target.contactUserId} $accountReference 1 ${contactAddress(1).toBase58()}\n")
    }

    @Test
    fun sendingPubKeyHashAt_derivesTheChainsKey_withoutIssuingOne() {
        val wallet = newWallet()

        assertEquals(
            contactAddress(7),
            Address.fromPubKeyHash(params, requireNotNull(FriendChainAccess.sendingPubKeyHashAtOrNull(wallet, contact, 7)))
        )
        assertEquals("nothing issued", 0, FriendChainAccess.sendingCursorOrNull(wallet, contact))
        assertNull("no chain for that reference", FriendChainAccess.sendingPubKeyHashAtOrNull(wallet, rotatedContact, 0))
        assertNull(FriendChainAccess.sendingPubKeyHashAtOrNull(wallet, contact, -1))
    }

    @Test
    fun rotatedRequest_sameXpub_newDashjChainIsAdvancedPastTheOldReferencesFrontier() = runBlocking {
        val wallet = newWallet()
        // The contact re-sent their request with the same xpub: dashj builds a
        // second chain under the new reference, at index 0 — the ambiguous one.
        wallet.addSendingToFriendKeyChain(contactXpub, ourId, 0, contactId, rotatedReference)
        assertEquals(0, FriendChainAccess.sendingCursorOrNull(wallet, rotatedContact))
        val provider = rotatedProvider(wallet, PaidContactAddressStore(foldedFrontierFile()))

        val next = provider.nextAddress(rotatedTarget)

        assertEquals(ContactAddressSource.DASHJ, next?.source)
        assertEquals(contactAddress(2), next?.address)
        val stale = ContactPaymentAddress(rotatedTarget, contactAddress(0), ContactAddressSource.DASHJ, index = 0)
        assertThrows(ContactAddressAlreadyPaidException::class.java) {
            runBlocking { provider.sendThenMarkUsed(stale) { "txid" } }
        }
        assertEquals(2, next?.index)
    }

    @Test
    fun rotatedRequest_anotherXpub_newDashjChainStartsAtItsOwnIndexZero() = runBlocking {
        val wallet = newWallet()
        wallet.addSendingToFriendKeyChain(otherXpub, ourId, 0, contactId, rotatedReference)
        val provider = rotatedProvider(wallet, PaidContactAddressStore(foldedFrontierFile()))

        val next = provider.nextAddress(rotatedTarget)

        val otherZero = Address.fromKey(
            params,
            HDKeyDerivation.deriveChildKey(DeterministicKey.deserializeB58(otherXpub, params), ChildNumber(0, false))
        )
        assertEquals(otherZero, next?.address)
        assertEquals(0, next?.index)
    }
}
