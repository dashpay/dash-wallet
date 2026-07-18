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

import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.DashAddressValidator
import org.dash.wallet.common.money.DashNetworks
import org.dash.wallet.common.money.MoneyFormat

object CrowdNodeConstants {
    private const val CROWDNODE_TESTNET_ADDRESS = "yMY5bqWcknGy5xYBHSsh2xvHZiJsRucjuy"
    private const val CROWDNODE_MAINNET_ADDRESS = "XjbaGWaGnvEtuQAUoBgDxJWe8ZNv45upG2"

    private const val MAINNET_BASE_URL = "https://app.crowdnode.io/"
    private const val TESTNET_BASE_URL = "https://test.crowdnode.io/"
    private const val MAINNET_LOGIN_URL = "https://login.crowdnode.io"
    private const val TESTNET_LOGIN_URL = "https://logintest.crowdnode.io"

    val MINIMUM_REQUIRED_DASH: Dash = Dash.valueOf(1000000)
    val REQUIRED_FOR_SIGNUP: Dash = MINIMUM_REQUIRED_DASH - Dash.valueOf(100000)
    val API_OFFSET: Dash = Dash.valueOf(20000)
    val MINIMUM_DASH_DEPOSIT: Dash = Dash.COIN.div(2)
    val DASH_FORMAT: MoneyFormat = MoneyFormat.BTC.minDecimals(1)
        .repeatOptionalDecimals(1, 3).postfixCode()
    val API_CONFIRMATION_DASH_AMOUNT: Dash = Dash.valueOf(54321)
    val MINIMUM_LEFTOVER_BALANCE: Dash = Dash.valueOf(30000)

    object WithdrawalLimits {
        // Current withdrawal limits can be found here:
        // https://knowledge.crowdnode.io/en/articles/6387601-api-withdrawal-limits
        // or with the API:
        // https://app.crowdnode.io/odata/apifundings/GetWithdrawalLimits(address='')
        val DEFAULT_LIMIT_PER_TX: Dash = Dash.COIN.multiply(15)
        val DEFAULT_LIMIT_PER_HOUR: Dash = Dash.COIN.multiply(30)
        val DEFAULT_LIMIT_PER_DAY: Dash = Dash.COIN.multiply(60)
    }

    /** The CrowdNode base58 address for the network identified by [networkId] (see [DashNetworks]). */
    fun getCrowdNodeAddress(networkId: String): String {
        return if (networkId == DashNetworks.MAINNET) {
            CROWDNODE_MAINNET_ADDRESS
        } else {
            CROWDNODE_TESTNET_ADDRESS
        }
    }

    /** [networkId] as in [DashNetworks]. */
    fun getCrowdNodeBaseUrl(networkId: String): String {
        return if (networkId == DashNetworks.MAINNET) {
            MAINNET_BASE_URL
        } else {
            TESTNET_BASE_URL
        }
    }

    fun getApiLinkUrl(address: String): String {
        return getBaseUrlForAddress(address) + "APILink/$address"
    }

    fun getProfileUrl(networkId: String): String {
        return getCrowdNodeBaseUrl(networkId) + "Profile"
    }

    fun getFundsOpenUrl(address: String): String {
        return getBaseUrlForAddress(address) + "FundsOpen/$address"
    }

    fun getLoginUrl(networkId: String): String {
        return if (networkId == DashNetworks.MAINNET) {
            MAINNET_LOGIN_URL
        } else {
            TESTNET_LOGIN_URL
        }
    }

    private fun getBaseUrlForAddress(address: String): String {
        // Same base-url-by-network resolution as the Address-typed original:
        // anything that isn't a mainnet address maps to the testnet url.
        return getCrowdNodeBaseUrl(DashAddressValidator.networkIdOrNull(address) ?: DashNetworks.TESTNET)
    }
}
