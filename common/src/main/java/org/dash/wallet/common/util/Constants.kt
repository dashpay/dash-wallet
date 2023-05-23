/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.common.util

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.bitcoinj.core.Coin
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.utils.MonetaryFormat
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

object Constants {
    private val log: Logger = LoggerFactory.getLogger(Constants::class.java)

    const val CHAR_HAIR_SPACE = '\u200a'
    const val CHAR_THIN_SPACE = '\u2009'
    const val CHAR_ALMOST_EQUAL_TO = '\u2248'
    const val CURRENCY_PLUS_SIGN = '\uff0b'
    const val CURRENCY_MINUS_SIGN = '\uff0d'
    const val PREFIX_ALMOST_EQUAL_TO = CHAR_ALMOST_EQUAL_TO.toString() + CHAR_THIN_SPACE

    const val USER_BUY_SELL_DASH = 101
    const val RESULT_CODE_GO_HOME = 100

    var MAX_MONEY: Coin = MainNetParams.get().maxMoney
    val ECONOMIC_FEE: Coin = Coin.valueOf(1000)
    val SEND_PAYMENT_LOCAL_FORMAT: MonetaryFormat =
        MonetaryFormat().withLocale(GenericUtils.getDeviceLocale()).minDecimals(2)
            .optionalDecimals()

    const val ANDROID_KEY_STORE = "AndroidKeyStore"

    lateinit var EXPLORE_GC_FILE_PATH: String
    lateinit var FAUCET_URL: String
    lateinit var BUILD_FLAVOR: String
    var DEEP_LINK_PREFIX = "android-app://hashengineering.darkcoin.wallet"

    const val DASH_CURRENCY = "DASH"
    const val USD_CURRENCY = "USD"

    /** Shared HTTP client, can reuse connections  */
    val HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(
            HttpLoggingInterceptor { log.debug(it) }.setLevel(HttpLoggingInterceptor.Level.BASIC)
        )
        .build()
}
