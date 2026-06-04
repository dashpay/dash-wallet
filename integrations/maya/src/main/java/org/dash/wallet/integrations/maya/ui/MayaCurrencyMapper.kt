package org.dash.wallet.integrations.maya.ui

import android.content.Context
import org.dash.wallet.integrations.maya.R
import java.util.Currency

class MayaCurrencyMapper(private val context: Context) {

    fun getCurrencyName(currencyCode: String): String {
        val displayName = try {
            val currency = Currency.getInstance(currencyCode)
            currency.displayName
        } catch (e: Exception) {
            currencyCode
        }
        return if (displayName == currencyCode) {
            when (currencyCode) {
                "DASH" -> context.getString(R.string.dash)
                "BTC" -> context.getString(R.string.cryptocurrency_bitcoin_network)
                else -> displayName
            }
        } else {
            displayName
        }
    }

    fun getCurrencyNetwork(asset: String): String {
        val list = asset.split('.')
        val networkName = when (val networkCode = list[1]) {
            "DASH" -> context.getString(R.string.dash)
            "BTC" -> context.getString(R.string.cryptocurrency_bitcoin_network)
            "ETH" -> context.getString(R.string.cryptocurrency_ethereum_network)
            else -> networkCode
        }
        return context.getString(R.string.cryptocurrency_any_network, networkName)
    }
}
