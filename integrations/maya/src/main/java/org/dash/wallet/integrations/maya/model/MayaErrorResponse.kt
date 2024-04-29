/*
 * Copyright 2024 Dash Core Group.
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

package org.dash.wallet.integrations.maya.model

import android.os.Parcelable
import com.google.gson.Gson
import kotlinx.parcelize.Parcelize
import org.bitcoinj.core.Transaction
import org.dash.wallet.integrations.maya.R

enum class MayaErrorType {
    NONE,
    BUY_FAILED,
    USER_ACCOUNT_ERROR,
    INSUFFICIENT_BALANCE,
    NO_BANK_ACCOUNT,
    NO_EXCHANGE_RATE,
    DEPOSIT_FAILED,
    INVALID_DESTINATION_ADDRESS,
    TRADING_IS_HALTED,
    QUOTE_ERROR,
    UNKNOWN_ERROR
}

class MayaException(val errorType: MayaErrorType, message: String?) : Exception(message)
class IncorrectSwapOutputCount(val tx: Transaction):
    Exception("Maya transaction has ${tx.outputs.size} outputs.  Only 3 are allowed")

fun getMayaErrorType(error: String): MayaErrorType {
    val endOfErrorType = error.indexOf(':')
    val errorType = if (endOfErrorType != -1) {
        error.substring(0, endOfErrorType)
    } else {
        error
    }
    return when (errorType) {
        "bad destination address" -> MayaErrorType.INVALID_DESTINATION_ADDRESS
        "failed to simulate swap" -> {
            when {
                error.contains("trading is halted") -> MayaErrorType.TRADING_IS_HALTED
                else -> MayaErrorType.QUOTE_ERROR
            }
        }
        else -> MayaErrorType.UNKNOWN_ERROR
    }
}

private val errorMap = mapOf(
    MayaErrorType.INVALID_DESTINATION_ADDRESS to R.string.maya_error_invalid_destination_address,
    MayaErrorType.TRADING_IS_HALTED to R.string.maya_error_trading_halted
)
fun getMayaErrorString(error: String): Int? {
    return errorMap[getMayaErrorType(error)]
}

@Parcelize
data class MayaErrorResponse(
    val errors: List<Error>? = null
) : Parcelable {
    companion object {
        fun getErrorMessage(json: String): Error? {
            return try {
                val gson = Gson()
                val errorResponse: MayaErrorResponse = gson.fromJson(
                    json,
                    MayaErrorResponse::class.java
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
) : Parcelable
