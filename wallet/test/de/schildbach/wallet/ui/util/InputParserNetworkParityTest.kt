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

package de.schildbach.wallet.ui.util

import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.TestNet3Params
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The BIP70 network check in [InputParser.parsePaymentRequest] compares the neutral
 * [AddressNetwork] of the parsed PaymentSession against the wallet's dashj
 * `NetworkParameters`. A direct `equals` between the two types is ALWAYS false (and would
 * reject every BIP70 payment request); [InputParser.isSameNetwork] must compare by network id.
 */
class InputParserNetworkParityTest {

    @Test
    fun sessionOnWalletOwnNetwork_isAccepted() {
        assertTrue(InputParser.isSameNetwork(AddressNetwork.DASH_MAINNET, MainNetParams.get()))
        assertTrue(InputParser.isSameNetwork(AddressNetwork.DASH_TESTNET, TestNet3Params.get()))
    }

    @Test
    fun sessionOnOtherNetwork_isRejected() {
        assertFalse(InputParser.isSameNetwork(AddressNetwork.DASH_TESTNET, MainNetParams.get()))
        assertFalse(InputParser.isSameNetwork(AddressNetwork.DASH_MAINNET, TestNet3Params.get()))
    }

    @Test
    fun neutralAddressNetworkNeverEqualsDashjParameters_directComparisonWouldFailOpen() {
        // documents the bug the id-based comparison fixes
        assertFalse(AddressNetwork.DASH_MAINNET.equals(MainNetParams.get()))
        assertFalse(AddressNetwork.DASH_TESTNET.equals(TestNet3Params.get()))
    }

    @Test
    fun devnetIds_resolveToDevnetDescriptor() {
        // AddressNetwork.fromId maps concrete devnet ids ("org.dash.dev.<name>") to the
        // shared devnet descriptor, so a devnet wallet accepts devnet BIP70 sessions.
        assertEquals(AddressNetwork.DASH_DEVNET, AddressNetwork.fromId("org.dash.dev.ouzo"))
        assertEquals(AddressNetwork.DASH_DEVNET, AddressNetwork.fromId(AddressNetwork.ID_DEVNET))
    }
}
