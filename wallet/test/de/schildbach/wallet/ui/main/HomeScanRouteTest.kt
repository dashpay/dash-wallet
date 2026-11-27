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

package de.schildbach.wallet.ui.main

import de.schildbach.wallet.service.platform.sdk.Bech32m
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.payments.parsers.Base58
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScanRouteTest {
    private val network = AddressNetwork.DASH_TESTNET
    private val hash20 = ByteArray(20) { (it + 1).toByte() }
    private val coreAddress = Base58.encodeChecked(network.addressHeader, hash20)
    private val mainnetCoreAddress = Base58.encodeChecked(AddressNetwork.DASH_MAINNET.addressHeader, hash20)
    private val platformAddress = requireNotNull(Bech32m.encode("tdash", byteArrayOf(0xb0.toByte()) + hash20))
    private val shieldedAddress =
        requireNotNull(Bech32m.encode("tdash", byteArrayOf(0x10) + ByteArray(43) { (it * 3 + 7).toByte() }))
    private val mainnetShieldedAddress =
        requireNotNull(Bech32m.encode("dash", byteArrayOf(0x10) + ByteArray(43) { (it * 3 + 7).toByte() }))
    private val connect = { input: String -> input.startsWith("dash-key:") }

    private fun route(input: String, advanced: Boolean) =
        homeScanRoute(input, advanced, network, supportsConnect = true, isDashConnect = connect)

    @Test
    fun advancedModeOff_everyScanTakesTheLegacyPath() {
        for (
            input in listOf(
                coreAddress, platformAddress, shieldedAddress, "dash:$shieldedAddress?amount=1",
                mainnetShieldedAddress, mainnetCoreAddress, "dash-key:abc", "garbage"
            )
        ) {
            assertEquals(input, HomeScanRoute.Legacy, route(input, advanced = false))
        }
    }

    @Test
    fun advancedModeOn_destinationsOpenExternalSend() {
        for (
            input in listOf(
                coreAddress, "dash:$coreAddress?amount=1", platformAddress, "dash:$platformAddress?amount=1",
                shieldedAddress, "dash:$shieldedAddress?amount=1"
            )
        ) {
            assertEquals(input, HomeScanRoute.ExternalSend(input), route(input, advanced = true))
        }
    }

    @Test
    fun advancedModeOn_wrongNetworkShowsThatError() {
        for (input in listOf(mainnetShieldedAddress, mainnetCoreAddress, "dash:$mainnetShieldedAddress?amount=1")) {
            assertEquals(input, HomeScanRoute.WrongNetwork, route(input, advanced = true))
        }
    }

    @Test
    fun advancedModeOn_everythingElseTakesTheLegacyPath() {
        for (
            input in listOf(
                "dash-key:abc", // DashConnect
                "dash:$coreAddress?r=https://example.com/invoice", // BIP70
                "pay:?r=https://example.com/invoice", // AnyPay
                "garbage" // private key / raw tx / unrecognised: StringInputParser decides
            )
        ) {
            assertEquals(input, HomeScanRoute.Legacy, route(input, advanced = true))
        }
    }
}
