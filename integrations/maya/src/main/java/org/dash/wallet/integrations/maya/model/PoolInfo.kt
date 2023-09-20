package org.dash.wallet.integrations.maya.model

import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.util.toBigDecimal
import org.dash.wallet.common.util.toFiat
import org.dash.wallet.integrations.maya.utils.MayaConstants
import java.math.BigDecimal

data class PoolInfo(
    val annualPercentageRate: String,
    val asset: String,
    val assetDepth: String,
    val assetPrice: String,
    val assetPriceUSD: String,
    val liquidityUnits: String,
    val poolAPY: String,
    val runeDepth: String,
    val status: String,
    val synthSupply: String,
    val synthUnits: String,
    val units: String,
    val volume24h: String
) {
    var assetPriceFiat: Fiat = Fiat.valueOf(MayaConstants.DEFAULT_EXCHANGE_CURRENCY, 0)

    fun getAssetPriceUSD() : Fiat {
        return Fiat.parseFiatInexact("USD", assetPriceUSD)
    }

    fun setAssetPrice(fiatExchangeRate: Fiat) {
        assetPriceFiat = BigDecimal(assetPriceUSD)
            .multiply(fiatExchangeRate.toBigDecimal())
            .toFiat(fiatExchangeRate.currencyCode)
    }

    val currencyCode: String
        get() {
            val codeIndex = asset.indexOf('.')
            val smartContract = asset.indexOf('-')
            return asset.substring(codeIndex + 1, if (smartContract != -1) smartContract else asset.length)
        }
}
