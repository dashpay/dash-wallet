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

package org.dash.wallet.integrations.maya.payments

import com.google.common.io.BaseEncoding
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.payments.parsers.AddressUtils
import org.dash.wallet.common.payments.parsers.Base58
import org.dash.wallet.common.payments.parsers.Bech32
import org.dash.wallet.common.payments.parsers.SegwitAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-chain tests for every [MayaCryptoCurrency.getNewExampleAddress] override. Each generated
 * address must:
 *  - be an exact match for the currency's own [MayaCryptoCurrency.addressParser],
 *  - differ from the hardcoded static [MayaCryptoCurrency.exampleAddress],
 *  - be stable within a session (all addresses derive from the one [MayaCryptoCurrency.sessionExampleKey]),
 *  - carry a valid checksum wherever the format defines one (verified by decoding when a
 *    decoder is available on the test classpath).
 */
class MayaCryptoCurrencyNewExampleAddressTest {

    /** Common assertions for every chain; returns the generated address for format checks. */
    private fun generateAndCheck(currency: MayaCryptoCurrency): String {
        val address = currency.getNewExampleAddress()
        assertTrue(
            "${currency.asset}: generated address '$address' does not match its own addressParser",
            currency.addressParser.exactMatch(address)
        )
        assertNotEquals(
            "${currency.asset}: generated address should differ from the static exampleAddress",
            currency.exampleAddress,
            address
        )
        assertEquals(
            "${currency.asset}: addresses derive from the session key, so repeated calls must be stable",
            address,
            currency.getNewExampleAddress()
        )
        return address
    }

    @Test
    fun bitcoin_generatesSegwitAddress() {
        val address = generateAndCheck(MayaBitcoinCryptoCurrency())
        assertTrue("expected bc1q P2WPKH, got $address", address.startsWith("bc1q"))
        // Round-trip through the real decoder: verifies the bech32 checksum and program length.
        val decoded = SegwitAddress.fromBech32(AddressNetwork.BITCOIN_MAINNET, address)
        assertEquals(0, decoded.witnessVersion)
        assertEquals(20, decoded.witnessProgram.size)
    }

    @Test
    fun dash_generatesMainnetP2pkh() {
        val address = generateAndCheck(MayaDashCryptoCurrency())
        assertTrue("expected X-prefixed Dash address, got $address", address.startsWith("X"))
        // Verifies the Base58 checksum + Dash mainnet version byte 76.
        AddressUtils.verify(AddressNetwork.DASH_MAINNET, address)
    }

    @Test
    fun ethereum_generatesLowercaseHexAddress() {
        val address = generateAndCheck(MayaEthereumCryptoCurrency())
        assertTrue("expected 0x + 40 hex chars, got $address", Regex("0x[0-9a-f]{40}").matches(address))
    }

    @Test
    fun evmSubclassesAndTokens_inheritTheEthereumAddress() {
        // Every EVM L2 subclass and ERC-20 wrapper shares MayaEthereumCryptoCurrency's format —
        // and, deriving from the same session key, the exact same address.
        val ethAddress = MayaEthereumCryptoCurrency().getNewExampleAddress()
        listOf(
            MayaArbitrumCryptoCurrency(),
            MayaBaseCryptoCurrency(),
            MayaOptimismCryptoCurrency(),
            MayaAvalancheCryptoCurrency(),
            MayaBnbSmartChainCryptoCurrency(),
            MayaBeraCryptoCurrency(),
            MayaMonadCryptoCurrency(),
            MayaPolygonCryptoCurrency(),
            MayaXLayerCryptoCurrency(),
            MayaGnosisXdaiCryptoCurrency()
        ).forEach { evm ->
            assertEquals("${evm.asset} should reuse the EVM address", ethAddress, generateAndCheck(evm))
        }
    }

    @Test
    fun kujira_generatesBech32Account() {
        val address = generateAndCheck(MayaKujiraCryptoCurrency())
        val decoded = Bech32.decode(address) // verifies checksum
        assertEquals("kujira", decoded.hrp)
        assertEquals(Bech32.Encoding.BECH32, decoded.encoding)
    }

    @Test
    fun rune_generatesBech32Account() {
        val address = generateAndCheck(MayaRuneCryptoCurrency())
        val decoded = Bech32.decode(address)
        assertEquals("thor", decoded.hrp)
        assertEquals(Bech32.Encoding.BECH32, decoded.encoding)
    }

    @Test
    fun maya_generatesBech32Account() {
        val address = generateAndCheck(MayaMayaTokenCryptoCurrency())
        val decoded = Bech32.decode(address)
        assertEquals("maya", decoded.hrp)
        assertEquals(Bech32.Encoding.BECH32, decoded.encoding)
    }

    @Test
    fun cacao_generatesBech32AccountOnMayaChain() {
        val address = generateAndCheck(MayaCacaoCryptoCurrency())
        assertEquals("maya", Bech32.decode(address).hrp)
        // CACAO and MAYA live on the same chain, so the session address is the same account.
        assertEquals(MayaMayaTokenCryptoCurrency().getNewExampleAddress(), address)
    }

    @Test
    fun zcash_generatesTransparentAddress() {
        val address = generateAndCheck(MayaZcashCryptoCurrency())
        assertTrue("expected t1 transparent address, got $address", address.startsWith("t1"))
        // decodeChecked verifies the Base58 checksum; then check the two-byte 0x1CB8 prefix.
        val payload = Base58.decodeChecked(address)
        assertEquals(0x1C.toByte(), payload[0])
        assertEquals(0xB8.toByte(), payload[1])
    }

    @Test
    fun radix_generatesBech32mAccount() {
        val address = generateAndCheck(MayaRadixCryptoCurrency())
        val decoded = Bech32.decode(address)
        assertEquals("account_rdx", decoded.hrp)
        assertEquals(Bech32.Encoding.BECH32M, decoded.encoding)
    }

    @Test
    fun bitcoinCash_generatesLegacyP2pkh() {
        val address = generateAndCheck(MayaBitcoinCashCryptoCurrency())
        assertTrue("expected legacy '1…' address, got $address", address.startsWith("1"))
        assertEquals(0x00.toByte(), Base58.decodeChecked(address)[0])
    }

    @Test
    fun litecoin_generatesSegwitAddress() {
        val address = generateAndCheck(MayaLitecoinCryptoCurrency())
        assertTrue("expected ltc1q P2WPKH, got $address", address.startsWith("ltc1q"))
        val decoded = Bech32.decode(address)
        assertEquals("ltc", decoded.hrp)
        assertEquals(Bech32.Encoding.BECH32, decoded.encoding)
    }

    @Test
    fun dogecoin_generatesP2pkh() {
        val address = generateAndCheck(MayaDogecoinCryptoCurrency())
        assertTrue("expected D-prefixed address, got $address", address.startsWith("D"))
        assertEquals(30.toByte(), Base58.decodeChecked(address)[0])
    }

    @Test
    fun cardano_generatesShelleyBaseAddress() {
        val address = generateAndCheck(MayaCardanoCryptoCurrency())
        // Header 0x01 (base address, mainnet) always encodes to "addr1q…"; a full base address is
        // 103 chars. (Bech32.decode caps input at 90 chars so the checksum can't be re-verified
        // here, but it is produced by the same encoder the shorter-address tests round-trip.)
        assertTrue("expected addr1q… base address, got $address", address.startsWith("addr1q"))
        assertEquals(103, address.length)
    }

    @Test
    fun solana_generatesBase58Key() {
        val address = generateAndCheck(MayaSolanaCryptoCurrency())
        assertEquals("expected 32 payload bytes", 32, Base58.decode(address).size)
    }

    @Test
    fun near_generatesImplicitAccount() {
        val address = generateAndCheck(MayaNearCryptoCurrency())
        assertTrue("expected 64 lowercase hex chars, got $address", Regex("[0-9a-f]{64}").matches(address))
    }

    @Test
    fun tron_generatesBase58CheckAddress() {
        val address = generateAndCheck(MayaTronCryptoCurrency())
        assertTrue("expected T-prefixed address, got $address", address.startsWith("T"))
        assertEquals(34, address.length)
        assertEquals(0x41.toByte(), Base58.decodeChecked(address)[0])
    }

    @Test
    fun xrp_generatesClassicAddress() {
        val address = generateAndCheck(MayaXrpCryptoCurrency())
        assertTrue("expected r-prefixed classic address, got $address", address.startsWith("r"))
        assertTrue("unexpected length ${address.length}", address.length in 25..35)
    }

    @Test
    fun ton_generatesBounceableFriendlyAddress() {
        val address = generateAndCheck(MayaTonCryptoCurrency())
        assertTrue("expected EQ… bounceable address, got $address", address.startsWith("EQ"))
        assertEquals(48, address.length)
        val bytes = BaseEncoding.base64Url().decode(address)
        assertEquals(36, bytes.size)
        assertEquals(0x11.toByte(), bytes[0]) // bounceable tag
        assertEquals(0x00.toByte(), bytes[1]) // basechain workchain
    }

    @Test
    fun sui_generates32ByteHexAddress() {
        val address = generateAndCheck(MayaSuiCryptoCurrency())
        assertTrue("expected 0x + 64 hex chars, got $address", Regex("0x[0-9a-f]{64}").matches(address))
    }

    @Test
    fun starknet_generatesFeltBelowFieldPrime() {
        val address = generateAndCheck(MayaStarknetCryptoCurrency())
        assertTrue("expected 0x + 64 hex chars, got $address", Regex("0x[0-9a-f]{64}").matches(address))
        // Top 5 bits cleared keeps the felt below Starknet's ~2^251 field prime.
        assertTrue("felt too large: $address", address[2] == '0' && address[3] in "01234567")
    }

    @Test
    fun everyRegisteredCurrency_generatesAParserValidAddress() {
        // Sweep the full list, token wrappers included: catches any future chain class that
        // forgets to override getNewExampleAddress() and inherits the wrong chain's format.
        val failures = MayaCurrencyList.all
            .filterNot { it.addressParser.exactMatch(it.getNewExampleAddress()) }
            .map { "${it.asset} (code=${it.code}): '${it.getNewExampleAddress()}'" }

        assertTrue(
            "These currencies generate an address their own addressParser rejects:\n" +
                failures.joinToString("\n"),
            failures.isEmpty()
        )
    }
}
