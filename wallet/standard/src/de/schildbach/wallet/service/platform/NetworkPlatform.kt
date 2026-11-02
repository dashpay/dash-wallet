package de.schildbach.wallet.service.platform

import org.bitcoinj.core.NetworkParameters
import org.dashj.platform.sdk.platform.Platform

internal fun createNetworkPlatform(params: NetworkParameters): Platform = Platform(params)
