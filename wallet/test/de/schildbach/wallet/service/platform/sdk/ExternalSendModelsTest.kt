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
        // fundFromAssetLockExternal: P2PKH only (either network's HRP; the network is checked by the parser).
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.CORE, platform))
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.CORE, SendDestination.Platform(platformTestP2pkh)))
        assertEquals(SendSupport.NOT_SUPPORTED_YET, support(SendBalance.CORE, SendDestination.Platform(platformMainP2sh)))
        assertEquals(SendSupport.NOT_SUPPORTED_YET, support(SendBalance.CORE, SendDestination.Platform(platformTestP2sh)))
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.CORE, platform, hasIdentity = false))
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
        // int37: the partial-amount withdrawal and the Type 15 shield to a recipient.
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.PLATFORM, core))
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.PLATFORM, shielded))
        // A P2SH Platform destination is a plain transfer output.
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.PLATFORM, SendDestination.Platform(platformMainP2sh)))
        // The Platform source needs no identity.
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.PLATFORM, core, hasIdentity = false))
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.PLATFORM, platform))
        // Any identity: topUpFromAddresses for a managed one, the external top-up
        // (platform#5860) for anyone else's; the send picks the call (and refuses when it
        // cannot tell), so the matrix does not depend on ownership.
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.PLATFORM, identity, own = true))
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.PLATFORM, identity, own = false))
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.PLATFORM, identity, own = null))
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.PLATFORM, identity, hasIdentity = false))
    }

    @Test
    fun matrix_identity() {
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.IDENTITY, core))
        // int37: the Type 21 shield to a recipient.
        assertEquals(SendSupport.SUPPORTED, support(SendBalance.IDENTITY, shielded))
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

    // ── Core → Platform record and arithmetic ─────────────────────────

    private val outPoint = "ab".repeat(32) + ":0"

    private fun record(outPointHex: String? = outPoint) = PendingPlatformFunding(
        id = "a7c1e3d2-0000-4000-8000-000000000001",
        walletIdHex = "cd".repeat(32),
        lockDuffs = 162_000L,
        destination = PlatformAddressBytes(0, hash20),
        explicitCredits = 100_000_000L,
        remainder = PlatformAddressBytes(0, ByteArray(20) { 9 }),
        outPointHex = outPointHex
    )

    @Test
    fun pendingPlatformFunding_roundTrips_withAndWithoutAnOutPoint() {
        for (original in listOf(record(), record(outPointHex = null))) {
            val decoded = PendingPlatformFunding.decode(original.encode())
            assertTrue(decoded.sameAs(original))
        }
        assertTrue(record(null).withOutPoint(outPoint).sameAs(record()))
        assertFalse(record(null).sameAs(record()))
    }

    @Test
    fun pendingPlatformFunding_completionUnconfirmed_roundTrips_andOldRecordsDecodeUnmarked() {
        // An unmarked record keeps the original ten-field form earlier builds wrote.
        val unmarked = record().encode()
        assertEquals(10, unmarked.split('|').size)
        assertFalse(PendingPlatformFunding.decode(unmarked).completionUnconfirmed)

        val marked = record().asCompletionUnconfirmed()
        val decoded = PendingPlatformFunding.decode(marked.encode())
        assertTrue(decoded.completionUnconfirmed)
        assertTrue(decoded.sameAs(marked))
        assertFalse(decoded.sameAs(record()))
        assertFalse(record().toString().contains("completionUnconfirmed=true"))

        // Only a built lock can be marked; any other eleventh field is malformed.
        try {
            record(null).asCompletionUnconfirmed()
            throw AssertionError("marked a record without a lock")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        for (text in listOf(unmarked + "|x", unmarked + "|", record(null).encode() + "|u")) {
            try {
                PendingPlatformFunding.decode(text)
                throw AssertionError("decoded a malformed record")
            } catch (e: IllegalStateException) {
                // expected
            }
        }
    }

    @Test
    fun pendingPlatformFunding_malformed_isAnError_neverAbsent() {
        val good = record().encode()
        val malformed = listOf(
            "",
            "2" + good.substring(1),
            good.replace("|162000|", "|-1|"),
            good.replace("|162000|", "|x|"),
            good + "|extra",
            good.replace(":0", ":"),
            good.substringBeforeLast('|') + "|" + "ab".repeat(31) + ":0",
            // A P2SH recipient can never be persisted.
            good.replaceFirst("|0|", "|1|")
        )
        for (text in malformed) {
            try {
                PendingPlatformFunding.decode(text)
                throw AssertionError("decoded a malformed record")
            } catch (e: IllegalStateException) {
                // expected
            }
        }
    }

    @Test
    fun pendingPlatformFunding_refusesARemainderThatIsThePayee_andRedactsItself() {
        try {
            PendingPlatformFunding("id", "cd".repeat(32), 1L, PlatformAddressBytes(0, hash20), 1L, PlatformAddressBytes(0, hash20), null)
            throw AssertionError("accepted the payee as the remainder")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        val text = record().toString()
        assertFalse(text.contains("162000"))
        assertFalse(text.contains("cdcd"))
        assertFalse(text.contains("abab"))
    }

    @Test
    fun coreToPlatform_arithmetic() {
        assertEquals(62_000L, PLATFORM_FUNDING_FEE_MARGIN_DUFFS)
        assertEquals(162_000L, platformFundingLockDuffs(100_000L))
        // The L1 reserve ((n * 148 + 300) * 2, at least 1000 duffs) plus the margin.
        assertEquals(1_000L + 62_000L, coreToPlatformFeeDuffs(1))
        assertEquals(1_192L + 62_000L, coreToPlatformFeeDuffs(2))
        assertEquals(1L, coreToPlatformMaxDuffs(63_193L, 2))
        assertNull(coreToPlatformMaxDuffs(63_192L, 2))
        assertNull(coreToPlatformMaxDuffs(0L, 2))
    }
}
