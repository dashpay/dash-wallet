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

package org.dash.wallet.integrations.maya.utils

import org.bitcoinj.core.NetworkParameters

object MayaConstants {
    const val DEFAULT_EXCHANGE_CURRENCY = "USD"

    private const val MAINNET_LEGACY_BASE_URL = "https://midgard.mayachain.info/v2/"
    private const val MAINNET_BASE_URL = "https://mayanode.mayachain.info/mayachain/"

    /**
     * https://exchangerate.host/#/docs
     */
    const val EXCHANGERATE_BASE_URL = "https://api.exchangerate.host/"

    fun getBaseUrl(params: NetworkParameters): String {
        return MAINNET_BASE_URL
    }
    fun getLegacyBaseUrl(params: NetworkParameters): String {
        return MAINNET_LEGACY_BASE_URL
    }
    const val VALUE_ZERO = "0"
    const val MIN_USD_AMOUNT = "2"

    const val ERROR_ID_INVALID_REQUEST = "invalid_request"
    const val ERROR_ID_2FA_REQUIRED = "two_factor_required"
    const val ERROR_MSG_INVALID_REQUEST = "That code was invalid"
    const val TRANSACTION_TYPE_SEND = "send"
}
