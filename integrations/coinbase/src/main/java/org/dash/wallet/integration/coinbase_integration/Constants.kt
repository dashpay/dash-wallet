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
package org.dash.wallet.integration.coinbase_integration

import android.content.Context
import java.io.File

object CoinbaseConstants {
    const val BASE_URL = "https://api.coinbase.com/"
    const val CB_VERSION_KEY = "CB-VERSION"
    const val CB_2FA_TOKEN_KEY = "CB-2FA-TOKEN"
    const val CB_VERSION_VALUE = "2021-09-07"
    const val TRANSACTION_TYPE_SEND = "send"
    const val TRANSACTION_STATUS_PENDING = "pending"
    const val TRANSACTION_STATUS_COMPLETED = "completed"
    const val TRANSACTION_STATUS_FAILED = "failed"
    const val TRANSACTION_STATUS_EXPIRED = "expired"
    const val TRANSACTION_STATUS_CANCELED = "canceled"
    const val TRANSACTION_STATUS_WAITING_FOR_SIGNATURE = "waiting_for_signature"
    const val TRANSACTION_STATUS_WAITING_FOR_CLEARING = "waiting_for_clearing"
    const val ERROR_ID_INVALID_REQUEST = "invalid_request"
    const val ERROR_ID_2FA_REQUIRED = "two_factor_required"
    const val ERROR_MSG_INVALID_REQUEST = "That code was invalid"
    const val VALUE_ZERO = "0"
    const val DEFAULT_CURRENCY_USD = "USD"
    const val MIN_USD_COINBASE_AMOUNT = "2"
    const val BASE_IDS_REQUEST_URL = "v2/assets/prices?filter=holdable&resolution=latest"

    fun getCacheDir(context: Context): File {
        return File(context.cacheDir, "coinbase")
    }
}