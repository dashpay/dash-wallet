package org.dash.wallet.integrations.maya.model

import com.google.gson.annotations.SerializedName

data class InboundAddress(
    @SerializedName("address") val address: String,
    @SerializedName("chain") val chain: String,
    @SerializedName("chain_lp_actions_paused") val chainLpActionsPaused: Boolean,
    @SerializedName("chain_trading_paused") val chainTradingPaused: Boolean,
    @SerializedName("dust_threshold") val dustThreshold: String,
    @SerializedName("gas_rate") val gasRate: String,
    @SerializedName("gas_rate_units") val gasRateUnits: String,
    @SerializedName("global_trading_paused") val globalTradingPaused: Boolean,
    @SerializedName("halted") val halted: Boolean,
    @SerializedName("outbound_fee") val outboundFee: String,
    @SerializedName("outbound_tx_size") val outboundTxSize: String,
    @SerializedName("pub_key") val pubKey: String
)
