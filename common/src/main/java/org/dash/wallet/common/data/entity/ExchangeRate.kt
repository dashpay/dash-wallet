/*
 * Copyright 2023 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.dash.wallet.common.data.entity

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.PrimaryKey
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.data.CurrencyInfo
import java.lang.IllegalArgumentException
import java.math.BigDecimal
import java.util.*

@Entity(tableName = "exchange_rates")
open class ExchangeRate : Parcelable {
    @PrimaryKey
    var currencyCode: String
    var rate: String? = null

    @Ignore
    private var currencyName: String? = null

    @Ignore
    private var currency: Currency? = null

    constructor(currencyCode: String, rate: String?) {
        this.currencyCode = currencyCode
        this.rate = rate
    }

    protected constructor(input: Parcel) {
        currencyCode = input.readString()!!
        rate = input.readString()
        currencyName = input.readString()
        currency = input.readSerializable() as Currency?
    }

    val fiat: Fiat
        get() {
            val value = BigDecimal(rate).movePointRight(Fiat.SMALLEST_UNIT_EXPONENT).toLong()
            return Fiat.valueOf(currencyCode, value)
        }

    override fun toString(): String {
        return "{$currencyCode:$rate}"
    }

    private fun getCurrency(): Currency {
        if (currency == null) {
            currency = Currency.getInstance(currencyCode.uppercase())
        }
        return currency!!
    }

    val currencySymbol: String
        get() = getCurrency().symbol

    fun getCurrencyName(context: Context): String {
        if (currencyName == null) {
            // currency codes must be 3 letters before calling getCurrency()
            // If the currency is not a valid ISO 4217 code, then set the
            // currency name to be equal to the currency code
            // exchanges often have "invalid" currency codes like USDT and CNH
            currencyName = if (currencyCode.length == 3) {
                try {
                    getCurrency().displayName
                } catch (x: IllegalArgumentException) {
                    currencyCode
                }
            } else currencyCode
            if (currencyCode.equals(currencyName!!, ignoreCase = true)) {
                currencyName = CurrencyInfo.getOtherCurrencyName(currencyCode, context)
            }
        }
        return currencyName!!
    }

    override fun describeContents(): Int {
        return 0
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(currencyCode)
        dest.writeString(rate)
        dest.writeString(currencyName)
        dest.writeSerializable(currency)
    }

    companion object CREATOR : Parcelable.Creator<ExchangeRate> {
        override fun createFromParcel(parcel: Parcel): ExchangeRate {
            return ExchangeRate(parcel)
        }

        override fun newArray(size: Int): Array<ExchangeRate?> {
            return arrayOfNulls(size)
        }
    }
}
