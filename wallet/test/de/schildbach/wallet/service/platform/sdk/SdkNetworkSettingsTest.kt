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

import org.bitcoinj.params.BinTangDevNetParams
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.params.OuzoDevNetParams
import org.dashfoundation.dashsdk.Network
import org.dashfoundation.dashsdk.Sdk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SdkNetworkSettingsTest {
    @Test
    fun selectedDevnetSuppliesSdkEndpointAndSpvSettings() {
        val cases = listOf(
            OuzoDevNetParams.get() to "https://quorums.ouzo.example",
            BinTangDevNetParams.get() to "https://quorums.bintang.example"
        )
        for ((parameters, endpoint) in cases) {
            val settings = sdkNetworkSettings(parameters, endpoint)
            val devnet = settings.devnet!!
            assertEquals(Network.DEVNET, settings.config.network)
            assertEquals(endpoint, settings.config.quorumUrl)
            assertEquals(parameters.devNetName.removePrefix("devnet-"), devnet.name)
            assertEquals(0, devnet.llmqSize)
            assertEquals(0, devnet.llmqThreshold)
            assertEquals(
                listOf("${devnet.name}.example:20001"),
                devnet.activeSpvPeers { url, port ->
                    assertEquals(endpoint, url)
                    assertEquals(1443, port)
                    listOf(Sdk.ActiveMasternode("${devnet.name}.example:20001", "https://${devnet.name}.example:1443"))
                }
            )
        }
    }

    @Test
    fun explicitLlMqParametersFollowTheSelectedDevnet() {
        val devnet = sdkNetworkSettings(OuzoDevNetParams.get(), "http://localhost:8080", 12, 8).devnet!!
        assertEquals(12, devnet.llmqSize)
        assertEquals(8, devnet.llmqThreshold)
    }

    @Test
    fun missingInvalidOrUnreachableEndpointFailsClosed() {
        val parameters = OuzoDevNetParams.get()
        assertThrows(SdkDevnetConfigurationException::class.java) {
            sdkNetworkSettings(parameters, "")
        }
        assertThrows(SdkDevnetConfigurationException::class.java) {
            sdkNetworkSettings(parameters, "quorums.ouzo.example")
        }
        val devnet = sdkNetworkSettings(parameters, "https://quorums.ouzo.example").devnet!!
        assertThrows(SdkDevnetConfigurationException::class.java) {
            devnet.activeSpvPeers { _, _ -> null }
        }
        assertThrows(SdkDevnetConfigurationException::class.java) {
            devnet.activeSpvPeers { _, _ -> emptyList() }
        }
        assertThrows(SdkDevnetConfigurationException::class.java) {
            sdkNetworkSettings(parameters, devnet.quorumUrl, 12, 0)
        }
    }

    @Test
    fun mainnetIgnoresDevnetOnlyInputs() {
        val settings = sdkNetworkSettings(MainNetParams.get(), null)
        assertEquals(Network.MAINNET, settings.config.network)
        assertNull(settings.config.quorumUrl)
        assertNull(settings.devnet)
    }
}
