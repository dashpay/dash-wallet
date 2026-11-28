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

import de.schildbach.wallet.service.platform.IdentityKeyChainBackfill.IdentityKeyRef
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Utils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-JVM tests for [IdentityKeyChainBackfill] — the pure logic behind
 * PlatformRepo.ensureIdentityChainKeys.
 *
 * Background: legacy dashj-platform signing (WalletSignerCallback.sign) resolves private keys
 * via AuthenticationKeyChain.findKeyFromPubKey, which only knows ISSUED chain keys. Identities
 * created by the Kotlin SDK (canonical 4-key set: keyId 0 MASTER, 1 CRITICAL, 2 HIGH,
 * 3 TRANSFER; derivation index == keyId on the DIP-13 identity-0 chain) reach dashj through
 * the restore path with zero issued keys, so signing a contact request (which uses the HIGH
 * key, keyId 2) fails with "signer callback returned 0". The backfill computes exactly which
 * chain indexes must be issued.
 */
class IdentityKeyChainBackfillTest {

    /** Deterministic fake "chain": index -> compressed public key. */
    private val chainKeys: Map<Int, ByteArray> = (0..5).associateWith { index ->
        // dashj's ECKey rejects the sentinel private keys 0 and 1, so offset by 2
        ECKey.fromPrivate(java.math.BigInteger.valueOf(index + 2L)).pubKey
    }

    private fun derive(index: Int): ByteArray? = chainKeys[index]

    /** The canonical SDK 4-key set: raw pubkey data, derivation index == keyId. */
    private fun sdkCanonicalKeys(): List<IdentityKeyRef> =
        (0..3).map { keyId -> IdentityKeyRef(keyId, chainKeys.getValue(keyId)) }

    // --- matchesKeyData ---

    @Test
    fun matchesKeyData_rawPublicKey() {
        val pub = chainKeys.getValue(2)
        assertTrue(IdentityKeyChainBackfill.matchesKeyData(pub, pub.copyOf()))
        assertFalse(IdentityKeyChainBackfill.matchesKeyData(pub, chainKeys.getValue(3)))
    }

    @Test
    fun matchesKeyData_hash160() {
        val pub = chainKeys.getValue(1)
        val hash160 = Utils.sha256hash160(pub)
        assertEquals(20, hash160.size)
        assertTrue(IdentityKeyChainBackfill.matchesKeyData(pub, hash160))
        assertFalse(
            IdentityKeyChainBackfill.matchesKeyData(pub, Utils.sha256hash160(chainKeys.getValue(0)))
        )
    }

    @Test
    fun matchesKeyData_wrongSizeNeverMatches() {
        val pub = chainKeys.getValue(0)
        assertFalse(IdentityKeyChainBackfill.matchesKeyData(pub, ByteArray(32)))
        assertFalse(IdentityKeyChainBackfill.matchesKeyData(pub, ByteArray(0)))
    }

    // --- indexesToIssue ---

    private fun indexesToIssue(
        keys: List<IdentityKeyRef>,
        isIssued: (ByteArray) -> Boolean = { false },
        derivePublicKey: (Int) -> ByteArray? = ::derive
    ) = IdentityKeyChainBackfill.indexesToIssue(keys, isIssued, derivePublicKey)

    @Test
    fun sdkIdentityWithNothingIssued_needsAllFourKeys() {
        // The live failure: a first-ever SDK-created identity restored into dashj; the
        // BLOCKCHAIN_IDENTITY chain has zero issued keys, so all 4 canonical keys are missing.
        assertEquals(listOf(0, 1, 2, 3), indexesToIssue(sdkCanonicalKeys()))
    }

    @Test
    fun legacyIdentityWithAllKeysIssued_isNoOpAndDerivesNothing() {
        // the PlatformRepo caller decrypts the chain only on the first derivation, so a fully
        // issued chain never needs the wallet's key
        val indexes = indexesToIssue(
            sdkCanonicalKeys(),
            isIssued = { true },
            derivePublicKey = { throw AssertionError("derived an already issued key") }
        )
        assertTrue(indexes.isEmpty())
    }

    @Test
    fun partiallyIssuedChain_onlyMissingIndexesAreDerivedAndIssued() {
        // e.g. key 0 became findable (used for recovery) but 1..3 were never issued
        val issuedPubs = setOf(chainKeys.getValue(0).toList())
        val derived = mutableListOf<Int>()
        val indexes = indexesToIssue(
            sdkCanonicalKeys(),
            isIssued = { data -> data.toList() in issuedPubs },
            derivePublicKey = { index -> derived += index; derive(index) }
        )
        assertEquals(listOf(1, 2, 3), indexes)
        assertEquals(listOf(1, 2, 3), derived)
    }

    @Test
    fun isIssued_isAskedWithTheIdentityKeyData() {
        // HASH160 key data is looked up by hash (findKeyFromPubHash), raw data by public key
        val hash3 = Utils.sha256hash160(chainKeys.getValue(3))
        val keys = listOf(IdentityKeyRef(0, chainKeys.getValue(0)), IdentityKeyRef(3, hash3))
        val asked = mutableListOf<List<Byte>>()
        val indexes = indexesToIssue(keys, isIssued = { data -> asked += data.toList(); data.size == 20 })
        assertEquals(listOf(0), indexes)
        assertEquals(listOf(chainKeys.getValue(0).toList(), hash3.toList()), asked)
    }

    @Test
    fun hash160KeyData_matchesAndIsIssued() {
        // TRANSFER or auth keys registered as ECDSA_HASH160 carry hash160(pubkey) as data
        val keys = listOf(
            IdentityKeyRef(0, chainKeys.getValue(0)),
            IdentityKeyRef(3, Utils.sha256hash160(chainKeys.getValue(3)))
        )
        assertEquals(listOf(0, 3), indexesToIssue(keys))
    }

    @Test
    fun foreignKeyData_thatDoesNotDeriveFromChain_isSkipped() {
        // an identity key whose data does not match the key derived at its keyId must never
        // cause a blind import (e.g. a key added from another wallet or device)
        val foreign = ECKey().pubKey
        val keys = listOf(
            IdentityKeyRef(0, chainKeys.getValue(0)),
            IdentityKeyRef(1, foreign)
        )
        assertEquals(listOf(0), indexesToIssue(keys))
    }

    @Test
    fun keyDataNoDerivedKeyCanMatch_isSkippedWithoutDerivation() {
        // e.g. a 48-byte BLS key: neither a compressed public key nor a HASH160
        val keys = listOf(IdentityKeyRef(1, ByteArray(48) { 1 }))
        val indexes = indexesToIssue(
            keys,
            derivePublicKey = { throw AssertionError("derived for unmatchable key data") }
        )
        assertTrue(indexes.isEmpty())
    }

    @Test
    fun underivableIndex_isSkipped() {
        val keys = listOf(
            IdentityKeyRef(0, chainKeys.getValue(0)),
            IdentityKeyRef(99, chainKeys.getValue(1))
        )
        assertEquals(listOf(0), indexesToIssue(keys)) // derive returns null for 99
    }

    @Test
    fun derivationException_propagatesInsteadOfLookingLikeAForeignKey() {
        // a strict caller (PlatformRepo.requireIdentityChainKeys) relies on this to fail closed: a
        // key that could not be derived must not be reported as "not this wallet's key"
        val keys = listOf(
            IdentityKeyRef(0, chainKeys.getValue(0)),
            IdentityKeyRef(1, ECKey().pubKey), // mismatch: skipped, not an error
            IdentityKeyRef(2, chainKeys.getValue(2))
        )
        val failure = IllegalStateException("derivation failed")
        val thrown = assertThrows(IllegalStateException::class.java) {
            indexesToIssue(keys, derivePublicKey = { index -> if (index == 2) throw failure else derive(index) })
        }
        assertSame(failure, thrown)
    }

    @Test
    fun negativeKeyId_isSkipped() {
        val keys = listOf(IdentityKeyRef(-1, chainKeys.getValue(0)))
        val indexes = indexesToIssue(
            keys,
            derivePublicKey = { index ->
                if (index >= 0) derive(index) else throw AssertionError("derived negative index")
            }
        )
        assertTrue(indexes.isEmpty())
    }

    @Test
    fun duplicatesAndUnorderedInput_yieldDistinctAscendingIndexes() {
        // ascending order keeps AuthenticationKeyChain.addNewKey's issued-key counter sane
        val keys = listOf(
            IdentityKeyRef(3, chainKeys.getValue(3)),
            IdentityKeyRef(1, chainKeys.getValue(1)),
            IdentityKeyRef(3, chainKeys.getValue(3)),
            IdentityKeyRef(0, chainKeys.getValue(0))
        )
        assertEquals(listOf(0, 1, 3), indexesToIssue(keys))
    }
}
