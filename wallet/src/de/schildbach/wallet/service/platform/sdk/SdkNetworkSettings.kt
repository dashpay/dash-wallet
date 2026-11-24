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

import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.DevNetParams
import org.dashfoundation.dashsdk.Sdk
import org.dashfoundation.dashsdk.config.SdkConfig
import java.net.URI

/** A missing or unusable devnet endpoint leaves Platform's answer unknown. */
internal class SdkDevnetConfigurationException(message: String) : IllegalStateException(message)

internal data class SdkDevnetSettings(
    val name: String,
    val quorumUrl: String,
    val llmqSize: Int,
    val llmqThreshold: Int
) {
    /** The same active-node feed that the SDK uses for devnet DAPI discovery. */
    fun activeSpvPeers(
        discover: (String, Int) -> List<Sdk.ActiveMasternode>? = { url, port ->
            Sdk.discoverActiveMasternodes(url, port)
        }
    ): List<String> = discover(quorumUrl, 1443)
        ?.map { it.spvPeer }
        ?.takeIf { it.isNotEmpty() }
        ?: throw SdkDevnetConfigurationException(
            "Devnet $name quorum endpoint did not return active masternodes"
        )
}

internal data class SdkNetworkSettings(val config: SdkConfig, val devnet: SdkDevnetSettings?)

/** Resolve every devnet value from the wallet's selected parameters and build configuration. */
internal fun sdkNetworkSettings(
    parameters: NetworkParameters,
    quorumUrl: String?,
    llmqSizeOverride: Int = 0,
    llmqThresholdOverride: Int = 0
): SdkNetworkSettings {
    val network = toSdkNetwork(parameters)
    if (parameters !is DevNetParams) return SdkNetworkSettings(SdkConfig(network = network), null)

    val name = parameters.devNetName?.removePrefix("devnet-")
        ?.takeIf { it.isNotBlank() && !it.contains('/') && it.none(Char::isWhitespace) }
        ?: throw SdkDevnetConfigurationException("Selected devnet has no valid name")
    val endpoint = quorumUrl?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw SdkDevnetConfigurationException("Set devnetQuorumUrl for devnet $name")
    val uri = try { URI(endpoint) } catch (_: Exception) { null }
    if (uri?.scheme !in listOf("http", "https") || uri?.host.isNullOrBlank() ||
        uri?.userInfo != null || uri?.query != null || uri?.fragment != null
    ) {
        throw SdkDevnetConfigurationException("Invalid devnetQuorumUrl for devnet $name")
    }
    if ((llmqSizeOverride == 0) != (llmqThresholdOverride == 0) ||
        llmqSizeOverride < 0 || llmqThresholdOverride < 0 ||
        llmqThresholdOverride > llmqSizeOverride && llmqSizeOverride != 0
    ) {
        throw SdkDevnetConfigurationException("Invalid devnet LLMQ size and threshold for devnet $name")
    }
    return SdkNetworkSettings(
        SdkConfig(network = network, quorumUrl = endpoint),
        SdkDevnetSettings(name, endpoint, llmqSizeOverride, llmqThresholdOverride)
    )
}
