package org.dash.wallet.common.util

import org.bitcoinj.core.NetworkParameters

fun NetworkParameters.isMainNet(): Boolean = id == NetworkParameters.ID_MAINNET