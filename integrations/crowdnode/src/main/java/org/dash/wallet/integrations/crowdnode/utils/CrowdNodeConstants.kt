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

package org.dash.wallet.integrations.crowdnode.utils

import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.utils.MonetaryFormat

object CrowdNodeConstants {
    private const val CROWDNODE_TESTNET_ADDRESS = "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy"
    private const val CROWDNODE_MAINNET_ADDRESS = "XjbaGWaGnvEtuQAUoBgDxJWe8ZNv45upG2"

    private const val MAINNET_BASE_URL = "https://app.crowdnode.io/"
    private const val TESTNET_BASE_URL = "https://test.crowdnode.io/"
    private const val MAINNET_LOGIN_URL = "https://login.crowdnode.io"
    private const val TESTNET_LOGIN_URL = "https://logintest.crowdnode.io"

    val MINIMUM_REQUIRED_DASH: Coin = Coin.valueOf(1000000)
    val REQUIRED_FOR_SIGNUP: Coin = MINIMUM_REQUIRED_DASH - Coin.valueOf(100000)
    val API_OFFSET: Coin = Coin.valueOf(20000)
    val MINIMUM_DASH_DEPOSIT: Coin = Coin.COIN.div(2)
    val DASH_FORMAT: MonetaryFormat = MonetaryFormat.BTC.minDecimals(1)
        .repeatOptionalDecimals(1, 3).postfixCode()
    val API_CONFIRMATION_DASH_AMOUNT: Coin = Coin.valueOf(54321)
    val MINIMUM_LEFTOVER_BALANCE: Coin = Coin.valueOf(30000)

    object WithdrawalLimits {
        // Current withdrawal limits can be found here:
        // https://knowledge.crowdnode.io/en/articles/6387601-api-withdrawal-limits
        // or with the API:
        // https://app.crowdnode.io/odata/apifundings/GetWithdrawalLimits(address='')
        val DEFAULT_LIMIT_PER_TX: Coin = Coin.COIN.multiply(15)
        val DEFAULT_LIMIT_PER_HOUR: Coin = Coin.COIN.multiply(30)
        val DEFAULT_LIMIT_PER_DAY: Coin = Coin.COIN.multiply(60)
    }

    fun getCrowdNodeAddress(params: NetworkParameters): Address {
        return Address.fromBase58(
            params,
            if (params == MainNetParams.get()) {
                CROWDNODE_MAINNET_ADDRESS
            } else {
                CROWDNODE_TESTNET_ADDRESS
            }
        )
    }

    fun getCrowdNodeBaseUrl(params: NetworkParameters): String {
        return if (params == MainNetParams.get()) {
            MAINNET_BASE_URL
        } else {
            TESTNET_BASE_URL
        }
    }

    fun getApiLinkUrl(address: Address): String {
        return getCrowdNodeBaseUrl(address.parameters) + "APILink/${address.toBase58()}"
    }

    fun getProfileUrl(params: NetworkParameters): String {
        return getCrowdNodeBaseUrl(params) + "Profile"
    }

    fun getFundsOpenUrl(address: Address): String {
        return getCrowdNodeBaseUrl(address.parameters) + "FundsOpen/${address.toBase58()}"
    }

    fun getLoginUrl(params: NetworkParameters): String {
        return if (params == MainNetParams.get()) {
            MAINNET_LOGIN_URL
        } else {
            TESTNET_LOGIN_URL
        }
    }
}
