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

import org.bitcoinj.core.Address
import org.bitcoinj.crypto.ChildNumber
import org.bitcoinj.crypto.HDKeyDerivation
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkAddressPoolBackfillTest {

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    // BIP32 test vector 1, chain m/0H/1/2H → m/0H/1/2H/2 → m/0H/1/2H/2/1000000000:
    // two non-hardened levels below an account-like key, the shape of an
    // account → chain → address derivation.
    private val vectorSeed = hex("000102030405060708090a0b0c0d0e0f")
    private val accountPub = "0357bfe1e341d01c69fe5654309956cbea516822fba8a601743a012a7896ee8dc2"
    private val accountChainCode = "04466b9cc8e161e966409ca52986c584f07e9dc81f735db683c3ff6ec7b1503f"
    private val leafPub = "022a471424da5e657499d1ff51cb43c47481a03b1e77f951fe64cec9f5a48f7011"

    /**
     * key-wallet's bincode `ExtendedPubKey` layout: network, depth, parent
     * fingerprint, child number (enum tag + varint index), public key, chain
     * code. [childNumberVarint] varies in length with the index.
     */
    private fun sdkXpub(pub: String, chainCode: String, childNumberVarint: ByteArray = byteArrayOf(1, 2)) =
        byteArrayOf(0, 3) + ByteArray(4) { 0x5a } + childNumberVarint + hex(pub) + hex(chainCode)

    @Test
    fun accountKey_readsThePublicKeyAndChainCodeFromTheTail() {
        val key = SdkAddressPoolBackfill.accountKey(sdkXpub(accountPub, accountChainCode))
        assertNotNull(key)
        assertEquals(accountPub, key!!.pubKey.toHex())
        assertEquals(accountChainCode, key.chainCode.toHex())

        // A three-byte varint child number (index >= 251) shifts nothing.
        val wide = sdkXpub(accountPub, accountChainCode, byteArrayOf(1, 0xFB.toByte(), 0x2C, 0x01))
        assertEquals(accountPub, SdkAddressPoolBackfill.accountKey(wide)!!.pubKey.toHex())
    }

    @Test
    fun accountKey_rejectsBytesThatAreNotAnEcdsaXpub() {
        assertNull(SdkAddressPoolBackfill.accountKey(null))
        assertNull(SdkAddressPoolBackfill.accountKey(ByteArray(40)))
        // A BLS operator key (89 bytes) does not end in a 02/03-prefixed point.
        assertNull(SdkAddressPoolBackfill.accountKey(ByteArray(89) { 0x11 }))
    }

    @Test
    fun derive_matchesTheBip32TestVector() {
        val account = SdkAddressPoolBackfill.accountKey(sdkXpub(accountPub, accountChainCode))!!
        val row = SdkAddressPoolBackfill.derive(account, 2, 1_000_000_000, "m/0'/1/2'/2/", MainNetParams.get())

        assertEquals(leafPub, row.publicKey.toHex())
        assertEquals("m/0'/1/2'/2/1000000000", row.derivationPath)
        assertEquals(1_000_000_000, row.addressIndex)
        assertEquals(
            Address.fromKey(MainNetParams.get(), HDKeyDerivation.deriveChildKey(
                HDKeyDerivation.deriveChildKey(account, ChildNumber(2, false)),
                ChildNumber(1_000_000_000, false)
            )).toBase58(),
            row.address
        )
    }

    @Test
    fun derive_fromThePublicKeyMatchesPrivateDerivationFromTheSeed() {
        // Public derivation from the stored xpub must land on the same
        // addresses the seed derives, or the rows would watch the wrong keys.
        var key = HDKeyDerivation.createMasterPrivateKey(vectorSeed)
        for (child in listOf(ChildNumber(0, true), ChildNumber(1, false), ChildNumber(2, true))) {
            key = HDKeyDerivation.deriveChildKey(key, child)
        }
        assertEquals(accountPub, key.pubKey.toHex())
        val account = SdkAddressPoolBackfill.accountKey(sdkXpub(accountPub, accountChainCode))!!
        val params = TestNet3Params.get()
        for (chain in 0..1) {
            for (index in listOf(0, 637, 999)) {
                val expected = HDKeyDerivation.deriveChildKey(
                    HDKeyDerivation.deriveChildKey(key, ChildNumber(chain, false)),
                    ChildNumber(index, false)
                )
                assertEquals(
                    Address.fromKey(params, expected).toBase58(),
                    SdkAddressPoolBackfill.derive(account, chain, index, "m/", params).address
                )
            }
        }
    }

    private fun row(index: Int, used: Boolean = false, path: String = "m/44'/5'/0'/1/$index", address: String = "a$index") =
        SdkAddressPoolBackfill.StoredRow(index, address, used, path)

    @Test
    fun plan_fillsHolesBelowTheStoredMaxAndTheWidenedTail() {
        // Rows 0..2, 5 and 8 stored; 8 is the highest used. Gap 3 → the
        // engine holds 0..11 in memory.
        val rows = listOf(row(0, used = true), row(1), row(2), row(5), row(8, used = true))
        val plan = SdkAddressPoolBackfill.plan(rows, gapLimit = 3)

        assertEquals(listOf(3, 4, 6, 7, 9, 10, 11), plan.missing)
        assertEquals("3, 4, 6, 7 are holes the reload lost", 4, plan.holesBelowStoredMax)
    }

    @Test
    fun plan_fieldShape_holesBetweenTheUsedFrontierAndTheSyncDerivedRows() {
        // The field pool: used up to 922, rows persisted by sync from 1000
        // to 1922 (used + 1000), the widened 923..999 never stored.
        val rows = (0..922).map { row(it, used = true) } + (1000..1922).map { row(it) }
        val plan = SdkAddressPoolBackfill.plan(rows, gapLimit = 1000)

        assertEquals((923..999).toList(), plan.missing)
        assertEquals(77, plan.holesBelowStoredMax)
    }

    @Test
    fun plan_contiguousPool_needsNothing_andANarrowerGapNeverShrinksIt() {
        val rows = (0..1922).map { row(it, used = it <= 922) }
        assertTrue(SdkAddressPoolBackfill.plan(rows, gapLimit = 1000).missing.isEmpty())
        assertTrue(SdkAddressPoolBackfill.plan(rows, gapLimit = 100).missing.isEmpty())
        assertTrue(SdkAddressPoolBackfill.plan(emptyList(), gapLimit = 100).missing.isEmpty())
    }

    @Test
    fun plan_unusedPool_isFilledThroughTheGap() {
        // A BIP32 account nobody used: 30 rows from creation, gap 100.
        val plan = SdkAddressPoolBackfill.plan((0..29).map { row(it) }, gapLimit = 100)
        assertEquals((30..99).toList(), plan.missing)
        assertEquals(0, plan.holesBelowStoredMax)
    }

    @Test
    fun pathPrefix_requiresThePathToEndInTheRowsIndex() {
        assertEquals("m/44'/5'/0'/1/", SdkAddressPoolBackfill.pathPrefix(row(637)))
        assertEquals("m/9'/5'/4'/0'/1/", SdkAddressPoolBackfill.pathPrefix(row(101, path = "m/9'/5'/4'/0'/1/101")))
        assertNull(SdkAddressPoolBackfill.pathPrefix(row(637, path = "m/44'/5'/0'/1/636")))
        assertNull(SdkAddressPoolBackfill.pathPrefix(row(7, path = "m/44'/5'/0'/1/17")))
    }

    @Test
    fun matchesStoredRows_refusesAKeyThatDoesNotReproduceThePool() {
        val params = MainNetParams.get()
        val account = SdkAddressPoolBackfill.accountKey(sdkXpub(accountPub, accountChainCode))!!
        val chain = HDKeyDerivation.deriveChildKey(account, ChildNumber(1, false))
        val real = (0..4).map {
            val d = SdkAddressPoolBackfill.derive(chain, it, "m/", params)
            row(it, path = d.derivationPath, address = d.address)
        }
        assertTrue(SdkAddressPoolBackfill.matchesStoredRows(chain, real, "m/", params))

        val otherChain = HDKeyDerivation.deriveChildKey(account, ChildNumber(0, false))
        assertFalse(SdkAddressPoolBackfill.matchesStoredRows(otherChain, real, "m/", params))
        assertFalse(SdkAddressPoolBackfill.matchesStoredRows(chain, emptyList(), "m/", params))
    }

    @Test
    fun verificationSamples_spreadAcrossThePool() {
        val samples = SdkAddressPoolBackfill.verificationSamples(listOf(row(9), row(0), row(4), row(1922)))
        assertEquals(listOf(0, 9, 1922), samples.map { it.addressIndex })
        assertEquals(listOf(5), SdkAddressPoolBackfill.verificationSamples(listOf(row(5))).map { it.addressIndex })
    }
}
