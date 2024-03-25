package org.dash.wallet.integrations.maya.model

data class InboundAddress(
    val address: String,
    val chain: String,
    val chain_lp_actions_paused: Boolean,
    val chain_trading_paused: Boolean,
    val dust_threshold: String,
    val gas_rate: String,
    val gas_rate_units: String,
    val global_trading_paused: Boolean,
    val halted: Boolean,
    val outbound_fee: String,
    val outbound_tx_size: String,
    val pub_key: String
)
