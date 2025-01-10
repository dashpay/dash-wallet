/*
 * Copyright 2021 Dash Core Group.
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
import com.google.gson.Gson
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import org.dash.wallet.integrations.coinbase.CoinbaseConstants

enum class CoinbaseErrorType {
    NONE,
    BUY_FAILED,
    USER_ACCOUNT_ERROR,
    INSUFFICIENT_BALANCE,
    NO_BANK_ACCOUNT,
    NO_EXCHANGE_RATE,
    DEPOSIT_FAILED
}

class CoinbaseException(val errorType: CoinbaseErrorType, message: String?) : Exception(message)

@Parcelize
data class CoinbaseErrorResponse(
    val errors: List<Error>? = null
) : Parcelable {
    companion object {
        fun getErrorMessage(json: String): Error? {
            return try {
                val gson = Gson()
                val errorResponse: CoinbaseErrorResponse = gson.fromJson(
                    json,
                    CoinbaseErrorResponse::class.java
                )
                errorResponse.errors?.firstOrNull()
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
}

@Parcelize
data class Error(
    val id: String? = null,
    val message: String? = null
) : Parcelable {
    @IgnoredOnParcel
    val isInvalidRequest = id == CoinbaseConstants.ERROR_ID_INVALID_REQUEST
}
