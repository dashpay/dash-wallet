package org.dash.wallet.integrations.maya.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.util.toFormattedString
import java.math.BigDecimal
import java.math.RoundingMode


@Parcelize
data class AccountDataUIModel(
    override val coinbaseAccount: Account,
    val currencyToCryptoCurrencyExchangeRate: BigDecimal,
    override val currencyToDashExchangeRate: BigDecimal,
    override val currencyToUSDExchangeRate: BigDecimal
) : ToDashExchangeRateUIModel(
    coinbaseAccount,
    currencyToDashExchangeRate,
    currencyToUSDExchangeRate
) {
    fun getCryptoToDashExchangeRate(): BigDecimal {
        return currencyToDashExchangeRate / currencyToCryptoCurrencyExchangeRate
    }
}

fun AccountDataUIModel.getCoinBaseExchangeRateConversion(
    currentExchangeRate: ExchangeRate
): Pair<String, Coin> {
    val cleanedValue =
        this.coinbaseAccount.availableBalance.value.toBigDecimal() /
                this.currencyToCryptoCurrencyExchangeRate
    val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

    val currencyRate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, currentExchangeRate.fiat)
    val fiatAmount = Fiat.parseFiat(currencyRate.fiat.currencyCode, bd.toString())
    val dashAmount = currencyRate.fiatToCoin(fiatAmount)

    return Pair(fiatAmount.toFormattedString(), dashAmount)
}

@Parcelize
open class ToDashExchangeRateUIModel(
    open val coinbaseAccount: Account,
    open val currencyToDashExchangeRate: BigDecimal,
    open val currencyToUSDExchangeRate: BigDecimal
): Parcelable {
    companion object {
        val EMPTY = ToDashExchangeRateUIModel(
            Account.EMPTY,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        )
    }
}