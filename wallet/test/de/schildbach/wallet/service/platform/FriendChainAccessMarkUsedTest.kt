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

package de.schildbach.wallet.service.platform

import org.bitcoinj.core.Address
import org.bitcoinj.core.Context
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.DeterministicKey
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.evolution.EvolutionContact
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.DeterministicSeed
import org.bitcoinj.wallet.FriendChainAccess
import org.bitcoinj.wallet.FriendChainAccess.MarkResult
import org.bitcoinj.wallet.FriendKeyChain
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletProtobufSerializer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [FriendChainAccess.markSendingAddressUsed] against a real dashj wallet with a
 * DIP-15 SENDING chain: after a payment the SDK made (dashj never sees it), dashj's
 * current sending address moves past the paid one — in-session AND after the wallet
 * is reloaded, where dashj rebuilds "current" as the last issued key.
 */
class FriendChainAccessMarkUsedTest {

    private val params = TestNet3Params.get()

    // Fixture phrase already used by other tests in this repo — carries no funds.
    private val mnemonic =
        "weapon elder job emotion aunt include deer owner salon census half divide"

    private val ourId = Sha256Hash.wrap(ByteArray(32) { 1 })
    private val contactId = Sha256Hash.wrap(ByteArray(32) { 2 })
    private val accountReference = 0x55

    /** The chain key, exactly as BlockchainIdentity.addPaymentKeyChainToContact builds it. */
    private val contact = EvolutionContact(ourId, 0, contactId, accountReference)

    /**
     * The contact's account xpub, in the form production builds it: the 69-byte
     * DIP-15 compact key (fingerprint, chain code, pubkey) decrypted from their
     * request, through `DeterministicKey.deserializeContactPub`
     * (BlockchainIdentity.decryptExtendedPublicKey).
     */
    private val contactXpub: String = run {
        val key = HDKeyDerivation.deriveChildKey(
            HDKeyDerivation.createMasterPrivateKey(ByteArray(32) { 7 }),
            ChildNumber(0, true)
        )
        val compact = ByteArray(4) { 9 } + key.chainCode + key.pubKey
        DeterministicKey.deserializeContactPub(params, compact).serializePubB58(params)
    }

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

    private fun current(wallet: Wallet): Address =
        wallet.currentAddress(contact, FriendKeyChain.KeyChainType.SENDING_CHAIN)

    private fun reload(wallet: Wallet): Wallet {
        val serializer = WalletProtobufSerializer()
        return serializer.readWallet(params, null, serializer.walletToProto(wallet))
    }

    @Test
    fun fixture_currentSendingAddressIsTheContactsFirst() {
        assertEquals(contactAddress(0), current(newWallet()))
    }

    @Test
    fun markingTheCurrentAddress_advancesIt_inSessionAndAfterReload() {
        val wallet = newWallet()
        val paid = current(wallet)

        assertEquals(MarkResult.MARKED, FriendChainAccess.markSendingAddressUsed(wallet, contact, paid.hash))

        assertEquals(contactAddress(1), current(wallet))
        assertEquals(contactAddress(1), current(reload(wallet)))
    }

    @Test
    fun markingAnAddressAheadOfDashj_skipsPastIt_inSessionAndAfterReload() {
        // The SDK paid index 3 while dashj still points at index 0. Marking only
        // the key would leave dashj on 0 now and — since reload rebuilds current
        // as the last ISSUED key — on 3 (the paid one) after a restart.
        val wallet = newWallet()
        assertEquals(contactAddress(0), current(wallet))

        assertEquals(
            MarkResult.MARKED,
            FriendChainAccess.markSendingAddressUsed(wallet, contact, contactAddress(3).hash)
        )

        assertEquals(contactAddress(4), current(wallet))
        assertEquals(contactAddress(4), current(reload(wallet)))
    }

    @Test
    fun anAddressBeyondTheForwardScan_isNotMarked() {
        val wallet = newWallet()

        assertEquals(
            MarkResult.KEY_NOT_ON_CHAIN,
            FriendChainAccess.markSendingAddressUsed(wallet, contact, contactAddress(150).hash)
        )
        assertEquals(contactAddress(0), current(wallet))
    }

    @Test
    fun markingTwice_isANoOp() {
        val wallet = newWallet()
        val paid = current(wallet)
        FriendChainAccess.markSendingAddressUsed(wallet, contact, paid.hash)

        assertEquals(
            MarkResult.ALREADY_BEHIND_CURRENT,
            FriendChainAccess.markSendingAddressUsed(wallet, contact, paid.hash)
        )
        assertEquals(contactAddress(1), current(wallet))
    }

    @Test
    fun anotherChainsAddress_isNotMarked() {
        val wallet = newWallet()
        val stranger = Address.fromKey(params, org.bitcoinj.core.ECKey())

        assertEquals(
            MarkResult.KEY_NOT_ON_CHAIN,
            FriendChainAccess.markSendingAddressUsed(wallet, contact, stranger.hash)
        )
        assertEquals(contactAddress(0), current(wallet))
    }

    @Test
    fun noChainForThatAccountReference() {
        val wallet = newWallet()
        val otherReference = EvolutionContact(ourId, 0, contactId, accountReference + 1)

        assertEquals(
            MarkResult.NO_CHAIN,
            FriendChainAccess.markSendingAddressUsed(wallet, otherReference, contactAddress(0).hash)
        )
    }
}
