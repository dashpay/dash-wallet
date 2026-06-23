package org.dash.wallet.integrations.maya.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class InboundAddress(
    @SerializedName("address") val address: String = "",
    @SerializedName("chain") val chain: String = "",
    @SerializedName("chain_lp_actions_paused") val chainLpActionsPaused: Boolean = false,
    @SerializedName("chain_trading_paused") val chainTradingPaused: Boolean = false,
    @SerializedName("dust_threshold") val dustThreshold: String = "",
    @SerializedName("gas_rate") val gasRate: String = "",
    @SerializedName("gas_rate_units") val gasRateUnits: String = "",
    @SerializedName("global_trading_paused") val globalTradingPaused: Boolean = false,
    @SerializedName("halted") val halted: Boolean = false,
    @SerializedName("outbound_fee") val outboundFee: String = "",
    @SerializedName("outbound_tx_size") val outboundTxSize: String = "",
    @SerializedName("pub_key") val pubKey: String = ""
) : Parcelable
