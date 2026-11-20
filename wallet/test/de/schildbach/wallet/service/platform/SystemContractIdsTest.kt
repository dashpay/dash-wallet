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

import de.schildbach.wallet.network.SakuraDevNetParams
import org.bitcoinj.core.Address
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.BinTangDevNetParams
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.OuzoDevNetParams
import org.bitcoinj.params.RegTestParams
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.params.UnitTestParams
import org.dashj.platform.dapiclient.SystemIds
import org.dashj.platform.dpp.identifier.Identifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity of the wallet-owned [SystemContractIds] registry with the legacy `Platform` constructor
 * of the pinned dash-sdk-kotlin 5.0.1, and of [resolveHasApp] with legacy `Platform.hasApp`.
 * Nothing here constructs `Platform` or loads a native library.
 */
class SystemContractIdsTest {

    private val sakura: NetworkParameters by lazy {
        SakuraDevNetParams.create(Address.fromPubKeyHash(TestNet3Params.get(), ByteArray(20)).toBase58(), 20001)
    }

    private val allNetworks: List<NetworkParameters>
        get() = listOf(
            MainNetParams.get(),
            TestNet3Params.get(),
            BinTangDevNetParams.get(),
            OuzoDevNetParams.get(),
            sakura,
            RegTestParams.get(),
            UnitTestParams.get()
        )

    // The identity-verify IDs are only reachable through the legacy Platform constructor (which
    // loads libsdklib), so they are pinned here as literals copied from its 5.0.1 bytecode.
    private val identityVerifyTestnet = Identifier.from("Bhptm3yBDhLkRNt7ofjpwaBHhMUKjDrQoPufKzQaxmpK")
    private val identityVerifyBintang = Identifier.from("Fds5DDfXoLwpUZ71AAVYZP1uod8S7Ze2bR28JExBvZKR")
    private val identityVerifyMainnet = Identifier.from("EVKMFboB3QBUa9Jo7PP5bsLyohzUz8zvw5c2gJs1SfcX")

    @Test
    fun systemContractsAreTheCanonicalIdsOnEveryNetwork() {
        for (params in allNetworks) {
            val registry = SystemContractIds(params)
            assertEquals(params.id, Identifier.from("GWRSAVFMjXx8HpQFaNJMqBV7MBgMK4br5UESsB4S31Ec"), registry.contractId("dpns"))
            assertEquals(params.id, Identifier.from("Bwr4WHCPz5rFVAD87RqTs3izo4zpzwsEdKPWUT1NS1C7"), registry.contractId("dashpay"))
            assertEquals(params.id, Identifier.from("7CSFGeF4WNzgDmx94zwvHkYaG3Dx4XEe5LFsFgJswLbm"), registry.contractId("wallet-utils"))
        }
    }

    @Test
    fun systemContractsMatchThePinnedLegacySystemIds() {
        // Drift guard: SystemIds is the object the legacy Platform constructor registers from.
        for (params in allNetworks) {
            val registry = SystemContractIds(params)
            assertEquals(SystemIds.dpnsDataContractId, registry.contractId(SystemContractIds.DPNS))
            assertEquals(SystemIds.dashpayDataContractId, registry.contractId(SystemContractIds.DASHPAY))
            assertEquals(SystemIds.walletUtilsDataContractId, registry.contractId(SystemContractIds.WALLET_UTILS))
        }
    }

    @Test
    fun identityVerifyIsPerNetwork() {
        assertEquals(identityVerifyTestnet, SystemContractIds(TestNet3Params.get()).contractId("identity-verify"))
        assertEquals(identityVerifyBintang, SystemContractIds(BinTangDevNetParams.get()).contractId("identity-verify"))
        assertEquals(identityVerifyMainnet, SystemContractIds(MainNetParams.get()).contractId("identity-verify"))
    }

    @Test
    fun identityVerifyIsAbsentOnSakuraAndOtherDevnets() {
        assertNull(SystemContractIds(sakura).contractId("identity-verify"))
        assertNull(SystemContractIds(OuzoDevNetParams.get()).contractId("identity-verify"))
    }

    @Test
    fun identityVerifyFollowsTheLegacySubstringMatch() {
        // Legacy matches params.id.contains("test") first, so the regtest and unit-test networks
        // ("org.bitcoin.regtest", "com.google.darkcoin.unittest") also get the testnet contract.
        assertEquals(identityVerifyTestnet, SystemContractIds(RegTestParams.get()).contractId("identity-verify"))
        assertEquals(identityVerifyTestnet, SystemContractIds(UnitTestParams.get()).contractId("identity-verify"))
    }

    @Test
    fun unknownNamesHaveNoIdAndAreNotOwnedByTheRegistry() {
        val registry = SystemContractIds(TestNet3Params.get())
        assertNull(registry.contractId("app-connect-system"))
        assertNull(registry.isRegistered("app-connect-system"))
        assertNull(registry.isRegistered("DPNS")) // legacy keys are case-sensitive
    }

    @Test
    fun isRegisteredMirrorsLegacyRegistration() {
        val testnet = SystemContractIds(TestNet3Params.get())
        listOf("dpns", "dashpay", "wallet-utils", "identity-verify").forEach {
            assertEquals(it, true, testnet.isRegistered(it))
        }
        val sakuraRegistry = SystemContractIds(sakura)
        assertEquals(true, sakuraRegistry.isRegistered("dashpay"))
        // Legacy Platform registers no identity-verify app on Sakura, so its hasApp is false.
        assertEquals(false, sakuraRegistry.isRegistered("identity-verify"))
    }

    @Test
    fun hasAppAnswersKnownNamesWithoutTheLegacyClient() {
        val legacyCalls = mutableListOf<String>()
        val legacy: (String) -> Boolean = { legacyCalls.add(it); error("legacy client must not be constructed") }

        assertTrue(resolveHasApp("dashpay", true, SystemContractIds(TestNet3Params.get()), legacy))
        assertTrue(resolveHasApp("identity-verify", true, SystemContractIds(MainNetParams.get()), legacy))
        assertFalse(resolveHasApp("identity-verify", true, SystemContractIds(sakura), legacy))
        assertTrue(legacyCalls.isEmpty())
    }

    @Test
    fun hasAppAsksTheLegacyClientForUnknownNames() {
        val legacyCalls = mutableListOf<String>()
        val result = resolveHasApp("app-connect-system", true, SystemContractIds(TestNet3Params.get())) {
            legacyCalls.add(it)
            true
        }
        assertTrue(result)
        assertEquals(listOf("app-connect-system"), legacyCalls)
    }

    @Test
    fun hasAppKeepsTheLegacyPathWhenPlatformIsUnsupported() {
        // Legacy getter throws when Platform is unsupported; that failure is preserved.
        val thrown = runCatching {
            resolveHasApp("dashpay", false, SystemContractIds(TestNet3Params.get())) {
                throw NullPointerException("platform unsupported")
            }
        }.exceptionOrNull()
        assertTrue(thrown is NullPointerException)
    }
}
