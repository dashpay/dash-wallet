package org.dash.wallet.common.util

import org.dash.wallet.common.payments.parsers.AddressNetwork

/** True when this network id (`NetworkParameters.getId()`) is the Dash mainnet id. */
fun String.isMainNetId(): Boolean = this == AddressNetwork.ID_MAINNET
