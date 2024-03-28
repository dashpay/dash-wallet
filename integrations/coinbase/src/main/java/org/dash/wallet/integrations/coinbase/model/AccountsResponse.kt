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

package org.dash.wallet.integrations.coinbase.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.bitcoinj.core.Coin
import org.dash.wallet.common.util.toCoin
import java.lang.Exception
import java.lang.IllegalArgumentException
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.UUID

@Parcelize
data class AccountsResponse(
    val accounts: List<CoinbaseAccount>
): Parcelable

@Parcelize
data class CoinbaseAccount(
    val uuid: UUID,
    val name: String,
    val currency: String,
    @SerializedName("available_balance")
    val availableBalance: Balance,
    val default: Boolean,
    val active: Boolean,
    val type: String,
    val ready: Boolean
): Parcelable {
    companion object {
        val EMPTY = CoinbaseAccount(
            UUID.randomUUID(),
            "",
            "",
            Balance("", ""),
            default = false,
            active = false,
            type = "",
            ready = false
        )
    }

    fun coinBalance(): Coin = try {
        Coin.parseCoin(availableBalance.value)
    } catch (ex: IllegalArgumentException) {
        try {
            val rounded = BigDecimal(availableBalance.value).round(MathContext(8, RoundingMode.HALF_UP))
            rounded.toCoin()
        } catch (ex: Exception) {
            Coin.ZERO
        }
    }
}

@Parcelize
data class Balance(
    val value: String,
    val currency: String
): Parcelable
