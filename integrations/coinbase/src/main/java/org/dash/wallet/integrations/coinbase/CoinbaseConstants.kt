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
package org.dash.wallet.integrations.coinbase

import android.content.Context
import org.dash.wallet.integrations.coinbase.service.CoinBaseClientConstants
import java.io.File

object CoinbaseConstants {
    const val BASE_URL = "https://api.coinbase.com/"
    const val CB_VERSION_KEY = "CB-VERSION"
    const val CB_2FA_TOKEN_KEY = "CB-2FA-TOKEN"
    const val CB_VERSION_VALUE = "2021-09-07"
    const val TRANSACTION_TYPE_SEND = "send"
    const val TRANSACTION_TYPE_BUY = "BUY"
    const val DASH_USD_PAIR = "DASH-USD"
    const val FIAT_ACCOUNT_TYPE = "ACCOUNT_TYPE_FIAT"
    const val ERROR_ID_INVALID_REQUEST = "invalid_request"
    const val ERROR_ID_2FA_REQUIRED = "two_factor_required"
    const val ERROR_MSG_INVALID_REQUEST = "That code was invalid"
    const val VALUE_ZERO = "0"
    const val DEFAULT_CURRENCY_USD = "USD"
    const val MIN_USD_COINBASE_AMOUNT = "2"
    const val BASE_IDS_REQUEST_URL = "v2/assets/prices?filter=holdable&resolution=latest"
    const val BUY_FEE = 0.006
    const val REDIRECT_URL = "dashwallet://brokers/coinbase/connect"
    const val AUTH_RESULT_ACTION = "Coinbase.AUTH_RESULT"
    val LINK_URL = "https://www.coinbase.com/oauth/authorize?client_id=${CoinBaseClientConstants.CLIENT_ID}" +
            "&redirect_uri=${REDIRECT_URL}&response_type" +
            "=code&scope=wallet:accounts:read,wallet:user:read,wallet:payment-methods:read," +
            "wallet:buys:read,wallet:buys:create,wallet:transactions:transfer," +
            "wallet:sells:create,wallet:sells:read,wallet:deposits:create," +
            "wallet:transactions:request,wallet:transactions:read,wallet:trades:create," +
            "wallet:supported-assets:read,wallet:transactions:send," +
            "wallet:addresses:read,wallet:addresses:create" +
            "&meta[send_limit_amount]=10" +
            "&meta[send_limit_currency]=USD" +
            "&meta[send_limit_period]=month" +
            "&account=all"

    fun getCacheDir(context: Context): File {
        return File(context.cacheDir, "coinbase")
    }
}
