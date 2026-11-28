package org.dash.wallet.integrations.maya.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.FiatValue
import org.dash.wallet.common.money.fiatToDash
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
): Pair<String, Dash> {
    val cleanedValue =
        this.coinbaseAccount.availableBalance.value.toBigDecimal() /
            this.currencyToCryptoCurrencyExchangeRate
    val bd = cleanedValue.setScale(8, RoundingMode.HALF_UP)

    val fiatAmount = FiatValue.parseFiat(currentExchangeRate.currencyCode, bd.toString())
    val dashAmount = currentExchangeRate.fiatToDash(fiatAmount)

    return Pair(fiatAmount.toFormattedString(), dashAmount)
}

@Parcelize
open class ToDashExchangeRateUIModel(
    open val coinbaseAccount: Account,
    open val currencyToDashExchangeRate: BigDecimal,
    open val currencyToUSDExchangeRate: BigDecimal
) : Parcelable {
    companion object {
        val EMPTY = ToDashExchangeRateUIModel(
            Account.EMPTY,
            BigDecimal.ZERO,
            BigDecimal.ZERO
        )
    }
}
