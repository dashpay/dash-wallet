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

import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.payments.parsers.Base58
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalSendModelsTest {

    private val main = AddressNetwork.DASH_MAINNET
    private val test = AddressNetwork.DASH_TESTNET
    private val hash20 = ByteArray(20) { (it + 1).toByte() }
    private val raw43 = ByteArray(43) { (it * 3 + 7).toByte() }
    private val id32 = ByteArray(32) { (255 - it).toByte() }

    private val coreMainP2pkh = Base58.encodeChecked(main.addressHeader, hash20)
    private val coreMainP2sh = Base58.encodeChecked(main.p2shHeader, hash20)
    private val coreTestP2pkh = Base58.encodeChecked(test.addressHeader, hash20)
    private val coreTestP2sh = Base58.encodeChecked(test.p2shHeader, hash20)

    private fun bech(hrp: String, type: Int, payload: ByteArray): String =
        requireNotNull(Bech32m.encode(hrp, byteArrayOf(type.toByte()) + payload))

    private val platformMainP2pkh = bech("dash", 0xb0, hash20)
    private val platformMainP2sh = bech("dash", 0x80, hash20)
    private val platformTestP2pkh = bech("tdash", 0xb0, hash20)
    private val platformTestP2sh = bech("tdash", 0x80, hash20)
    private val shieldedMain = bech("dash", 0x10, raw43)
    private val shieldedTest = bech("tdash", 0x10, raw43)
    private val identityId = Base58.encode(id32)

    private fun valid(input: String, network: AddressNetwork): SendDestination {
        val parsed = detectSendDestination(input, network)
        assertTrue("expected Valid, got $parsed", parsed is DestinationParse.Valid)
        return (parsed as DestinationParse.Valid).destination
    }

    // ── Core ──────────────────────────────────────────────────────────

    @Test
    fun core_bothTypesBothNetworks_areValid() {
        assertEquals(coreMainP2pkh.first(), 'X')
        assertEquals(coreMainP2sh.first(), '7')
        assertEquals(coreTestP2pkh.first(), 'y')
        assertEquals(coreTestP2sh.first(), '8')
        assertEquals(SendDestination.Core(coreMainP2pkh), valid(coreMainP2pkh, main))
        assertEquals(SendDestination.Core(coreMainP2sh), valid(coreMainP2sh, main))
        assertEquals(SendDestination.Core(coreTestP2pkh), valid(coreTestP2pkh, test))
        assertEquals(SendDestination.Core(coreTestP2sh), valid(coreTestP2sh, test))
        // Devnets share testnet's address space.
        assertEquals(SendDestination.Core(coreTestP2pkh), valid(coreTestP2pkh, AddressNetwork.DASH_DEVNET))
    }

    @Test
    fun core_otherNetwork_isWrongNetwork() {
        assertEquals(DestinationParse.WrongNetwork, detectSendDestination(coreTestP2pkh, main))
        assertEquals(DestinationParse.WrongNetwork, detectSendDestination(coreMainP2sh, test))
    }

    @Test
    fun core_badChecksum_isInvalid() {
        val last = coreMainP2pkh.last()
        val tampered = coreMainP2pkh.dropLast(1) + (if (last == 'a') 'b' else 'a')
        assertEquals(DestinationParse.Invalid, detectSendDestination(tampered, main))
    }

    @Test
    fun core_bitcoinAddress_isInvalidNotWrongNetwork() {
        // A version byte of no Dash network (Bitcoin P2PKH = 0).
        val btc = Base58.encodeChecked(0, hash20)
        assertEquals(DestinationParse.Invalid, detectSendDestination(btc, main))
    }

    // ── Platform / Shielded ───────────────────────────────────────────

    @Test
    fun platform_bothTypesBothNetworks_areValid() {
        assertTrue(platformMainP2pkh.startsWith("dash1k"))
        assertTrue(platformMainP2sh.startsWith("dash1s"))
        assertTrue(platformTestP2pkh.startsWith("tdash1k"))
        assertTrue(platformTestP2sh.startsWith("tdash1s"))
        assertEquals(SendDestination.Platform(platformMainP2pkh), valid(platformMainP2pkh, main))
        assertEquals(SendDestination.Platform(platformMainP2sh), valid(platformMainP2sh, main))
        assertEquals(SendDestination.Platform(platformTestP2pkh), valid(platformTestP2pkh, test))
        assertEquals(SendDestination.Platform(platformTestP2sh), valid(platformTestP2sh, test))
    }

    @Test
    fun shielded_bothNetworks_areValid() {
        assertTrue(shieldedMain.startsWith("dash1z"))
        assertTrue(shieldedTest.startsWith("tdash1z"))
        assertEquals(SendDestination.Shielded(shieldedMain), valid(shieldedMain, main))
        assertEquals(SendDestination.Shielded(shieldedTest), valid(shieldedTest, test))
    }

    @Test
    fun bech32m_otherNetwork_isWrongNetwork() {
        assertEquals(DestinationParse.WrongNetwork, detectSendDestination(platformTestP2pkh, main))
        assertEquals(DestinationParse.WrongNetwork, detectSendDestination(platformMainP2sh, test))
        assertEquals(DestinationParse.WrongNetwork, detectSendDestination(shieldedTest, main))
        assertEquals(DestinationParse.WrongNetwork, detectSendDestination(shieldedMain, test))
    }

    @Test
    fun bech32m_badChecksum_isInvalid() {
        for (address in listOf(platformMainP2pkh, shieldedMain)) {
            val last = address.last()
            val tampered = address.dropLast(1) + (if (last == 'q') 'p' else 'q')
            assertEquals(DestinationParse.Invalid, detectSendDestination(tampered, main))
        }
    }

    @Test
    fun bech32m_mixedCase_isInvalid_upperCase_isValidAndLowercased() {
        val mixed = platformMainP2pkh.substring(0, 6).uppercase() + platformMainP2pkh.substring(6)
        assertEquals(DestinationParse.Invalid, detectSendDestination(mixed, main))
        assertEquals(SendDestination.Platform(platformMainP2pkh), valid(platformMainP2pkh.uppercase(), main))
        assertEquals(SendDestination.Shielded(shieldedMain), valid(shieldedMain.uppercase(), main))
    }

    @Test
    fun sharedDash1Prefix_isToldApartByTypeByteAndLength() {
        // Platform type byte with an Orchard-length payload, and the Orchard byte with a
        // Platform-length payload, are neither.
        assertEquals(DestinationParse.Invalid, detectSendDestination(bech("dash", 0xb0, raw43), main))
        assertEquals(DestinationParse.Invalid, detectSendDestination(bech("dash", 0x10, hash20), main))
        // Unknown type byte.
        assertEquals(DestinationParse.Invalid, detectSendDestination(bech("dash", 0x00, hash20), main))
        // Another HRP.
        assertEquals(DestinationParse.Invalid, detectSendDestination(bech("bc", 0xb0, hash20), main))
    }

    @Test
    fun decodePlatformAddress_mapsTypeBytesToStorageTypes() {
        val p2pkh = requireNotNull(decodePlatformAddress(platformMainP2pkh, main))
        assertEquals(0, p2pkh.addressType)
        assertArrayEquals(hash20, p2pkh.hash)
        assertEquals(1, requireNotNull(decodePlatformAddress(platformTestP2sh, test)).addressType)
        assertNull(decodePlatformAddress(platformTestP2sh, main))
    }

    // ── Identity ──────────────────────────────────────────────────────

    @Test
    fun identity_32Bytes_isValidOnBothNetworks() {
        assertEquals(SendDestination.Identity(identityId), valid(identityId, main))
        assertEquals(SendDestination.Identity(identityId), valid(identityId, test))
    }

    @Test
    fun identity_wrongLength_isInvalid() {
        assertEquals(DestinationParse.Invalid, detectSendDestination(Base58.encode(ByteArray(31) { 9 }), main))
        assertEquals(DestinationParse.Invalid, detectSendDestination(Base58.encode(ByteArray(33) { 9 }), main))
        assertEquals(DestinationParse.Invalid, detectSendDestination("not-base58-0OIl", main))
    }

    // ── Whitespace / empty ────────────────────────────────────────────

    @Test
    fun whitespace_isTrimmed_blankIsEmpty() {
        assertEquals(DestinationParse.Empty, detectSendDestination("", main))
        assertEquals(DestinationParse.Empty, detectSendDestination("  \n\t", main))
        assertEquals(SendDestination.Core(coreMainP2pkh), valid("  $coreMainP2pkh\n", main))
        assertEquals(SendDestination.Platform(platformMainP2pkh), valid("\t$platformMainP2pkh ", main))
        assertEquals(SendDestination.Identity(identityId), valid(" $identityId ", main))
    }

    // ── dash: URI ─────────────────────────────────────────────────────

    @Test
    fun dashUri_withAmount_isCoreWithAmount() {
        val parsed = detectSendDestination("dash:$coreTestP2pkh?amount=0.25", test)
        assertEquals(DestinationParse.Valid(SendDestination.Core(coreTestP2pkh), Dash(25_000_000L)), parsed)
    }

    @Test
    fun dashUri_withoutAmount_isCore() {
        assertEquals(
            DestinationParse.Valid(SendDestination.Core(coreMainP2pkh), null),
            detectSendDestination("dash:$coreMainP2pkh", main)
        )
    }

    @Test
    fun dashUri_wrongNetwork_bip70_andGarbage() {
        assertEquals(DestinationParse.WrongNetwork, detectSendDestination("dash:$coreTestP2pkh", main))
        assertEquals(
            DestinationParse.Invalid,
            detectSendDestination("dash:$coreMainP2pkh?r=https%3A%2F%2Fexample.com%2Fi", main)
        )
        assertEquals(DestinationParse.Invalid, detectSendDestination("dash:notanaddress", main))
    }

    @Test
    fun destinations_toString_isRedacted() {
        for (d in listOf(
            SendDestination.Core(coreMainP2pkh),
            SendDestination.Platform(platformMainP2pkh),
            SendDestination.Shielded(shieldedMain),
            SendDestination.Identity(identityId)
        )) {
            assertFalse(d.toString().contains(d.display))
        }
    }

    // ── Support matrix ────────────────────────────────────────────────

    private val core = SendDestination.Core(coreMainP2pkh)
    private val shielded = SendDestination.Shielded(shieldedMain)
    private val platform = SendDestination.Platform(platformMainP2pkh)
    private val identity = SendDestination.Identity(identityId)

    private fun support(source: SendBalance, dest: SendDestination, own: Boolean? = false, hasIdentity: Boolean = true) =
        externalSendSupport(source, dest, hasIdentity, own)

    @Test
    fun matrix_core() {
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.CORE, core))
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.CORE, shielded))
        assertEquals(SendSupport.NOT_SUPPORTED_YET, support(SendBalance.CORE, platform))
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.CORE, identity, own = true))
        assertEquals(SendSupport.NEEDS_OWN_IDENTITY, support(SendBalance.CORE, identity, own = false))
        // Could not tell: fail closed.
        assertEquals(SendSupport.NEEDS_OWN_IDENTITY, support(SendBalance.CORE, identity, own = null))
        // The Core source does not need an identity.
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.CORE, core, hasIdentity = false))
    }

    @Test
    fun matrix_shielded() {
        for (dest in listOf(core, shielded, platform, identity)) {
            assertEquals(SendSupport.SUPPORTED, support(SendBalance.SHIELDED, dest))
            assertEquals(SendSupport.SUPPORTED, support(SendBalance.SHIELDED, dest, own = null, hasIdentity = false))
        }
    }

    @Test
    fun matrix_platform() {
        assertEquals(SendSupport.NOT_SUPPORTED_YET, support(SendBalance.PLATFORM, core))
        assertEquals(SendSupport.NOT_SUPPORTED_YET, support(SendBalance.PLATFORM, shielded))
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.PLATFORM, platform))
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.PLATFORM, identity))
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.PLATFORM, identity, own = true))
    }

    @Test
    fun matrix_identity() {
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.IDENTITY, core))
        assertEquals(SendSupport.NOT_SUPPORTED_YET, support(SendBalance.IDENTITY, shielded))
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.IDENTITY, platform))
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.IDENTITY, identity, own = false))
        // To itself: moves nothing.
        assertEquals(SendSupport.NOT_SUPPORTED_YET, support(SendBalance.IDENTITY, identity, own = true))
        for (dest in listOf(core, shielded, platform, identity)) {
            assertEquals(SendSupport.NO_IDENTITY, support(SendBalance.IDENTITY, dest, hasIdentity = false))
        }
    }

    // ── Review round 1 regressions ────────────────────────────────────

    @Test
    fun dashUri_ofAShieldedPlatformOrIdentityDestination_isThatDestination_withItsAmount() {
        val cases = listOf(
            shieldedTest to SendDestination.Shielded(shieldedTest),
            platformTestP2pkh to SendDestination.Platform(platformTestP2pkh),
            platformTestP2sh to SendDestination.Platform(platformTestP2sh),
            identityId to SendDestination.Identity(identityId)
        )
        for ((address, destination) in cases) {
            assertEquals(
                DestinationParse.Valid(destination, Dash(12_345_678L)),
                detectSendDestination("dash:$address?amount=0.12345678", test)
            )
            assertEquals(DestinationParse.Valid(destination, null), detectSendDestination("dash:$address", test))
            // Optional parameters are ignored; a zero amount is no amount.
            assertEquals(
                DestinationParse.Valid(destination, null),
                detectSendDestination("dash:$address?label=x&amount=0", test)
            )
        }
        assertEquals(
            DestinationParse.Valid(SendDestination.Shielded(shieldedTest), Dash(100_000_000L)),
            detectSendDestination("DASH:${shieldedTest.uppercase()}?amount=1", test)
        )
    }

    @Test
    fun dashUri_ofANonCoreDestination_isReadStrictly() {
        for (query in listOf(
            "amount=1&amount=2",
            "amount=-1",
            "amount=1.123456789",
            "amount=1e3",
            "amount=abc",
            "amount=",
            "amount=99999999",
            "r=https%3A%2F%2Fexample.com%2Fi",
            "req-foo=1"
        )) {
            assertEquals(query, DestinationParse.Invalid, detectSendDestination("dash:$shieldedTest?$query", test))
        }
        assertEquals(DestinationParse.WrongNetwork, detectSendDestination("dash:$shieldedMain?amount=1", test))
        assertEquals(DestinationParse.Invalid, detectSendDestination("dash:tdash1notanaddress?amount=1", test))
    }

    @Test
    fun dashUri_roundTripsAdvancedReceivesAmountRequests() {
        val amount = org.dash.wallet.common.money.Coin.valueOf(123_456_789L)
        for (address in listOf(shieldedTest, platformTestP2pkh, identityId, coreTestP2pkh)) {
            val payload = de.schildbach.wallet.ui.payments.ReceiveViewModel.amountRequestPayload(address, amount)
            val parsed = detectSendDestination(payload, test)
            assertTrue("$parsed", parsed is DestinationParse.Valid)
            parsed as DestinationParse.Valid
            assertEquals(address, parsed.destination.display)
            assertEquals(Dash(123_456_789L), parsed.amount)
        }
    }

    @Test
    fun anEmptyBase58CheckPayload_isInvalid_neverACrash() {
        // "3QJmnh" is the Base58Check encoding of zero bytes: a valid checksum, no version byte.
        for (input in listOf("3QJmnh", "dash:3QJmnh", "dash:3QJmnh?amount=1")) {
            assertEquals(input, DestinationParse.Invalid, detectSendDestination(input, test))
        }
    }

    @Test
    fun detectSendDestination_neverThrows() {
        val random = java.util.Random(7)
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz0OIl:?&=-.%+ tdash1z"
        repeat(5_000) {
            val input = buildString { repeat(random.nextInt(60)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            detectSendDestination(input, test)
            detectSendDestination("dash:$input", test)
        }
    }
}
