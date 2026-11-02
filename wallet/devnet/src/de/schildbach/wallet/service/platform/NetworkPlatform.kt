package de.schildbach.wallet.service.platform

import de.schildbach.wallet.network.SakuraDevNetParams
import org.bitcoinj.core.NetworkParameters
import org.dashj.platform.sdk.client.NetworkConfiguration
import org.dashj.platform.sdk.platform.Platform

internal fun createNetworkPlatform(params: NetworkParameters): Platform {
    require(params is SakuraDevNetParams) { "The devnet flavor requires Sakura parameters" }
    return Platform(params, NetworkConfiguration.sakura())
}
