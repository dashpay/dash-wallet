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

package org.dash.wallet.integrations.uphold.data

object UpholdConstants {
    lateinit var CLIENT_ID: String
    lateinit var CLIENT_SECRET: String
    lateinit var CLIENT_BASE_URL: String
    lateinit var INITIAL_URL: String
    lateinit var CARD_URL_BASE: String
    lateinit var TRANSACTION_URL: String
    lateinit var LOGOUT_URL: String
    lateinit var PROFILE_URL: String
    lateinit var UPHOLD_CURRENCY_LIST: String

    fun initialize(useSandbox: Boolean) {
        if (!useSandbox) {
            CLIENT_BASE_URL = "https://api.uphold.com/"
            INITIAL_URL = "https://uphold.com/authorize/" + CLIENT_ID +
                "?scope=accounts:read%%20cards:read%%20cards:write%%20transactions:deposit%%20transactions:read%%20" +
                "transactions:transfer:application%%20transactions:transfer:others%%20transactions:transfer:self%%20" +
                "transactions:withdraw%%20transactions:commit:otp%%20user:read&state=%s"
            CARD_URL_BASE = "https://uphold.com/dashboard/cards/%s/add"
            TRANSACTION_URL = "https://uphold.com/reserve/transactions/%s"
            LOGOUT_URL = "https://wallet.uphold.com/dashboard/more"
            PROFILE_URL = "https://wallet.uphold.com/dashboard/settings/profile"
            UPHOLD_CURRENCY_LIST = "https://api.uphold.com/v0/assets"
        } else {
            CLIENT_BASE_URL = "https://api-sandbox.uphold.com/"
            INITIAL_URL =
                "https://sandbox.uphold.com/authorize/" + CLIENT_ID +
                "?scope=accounts:read%%20cards:read%%20cards:write%%20transactions:deposit%%20transactions:read%%20" +
                "transactions:transfer:application%%20transactions:transfer:others%%20transactions:transfer:self%%20" +
                "transactions:withdraw%%20transactions:commit:otp%%20user:read&state=%s"
            CARD_URL_BASE = "https://sandbox.uphold.com/dashboard/cards/%s/add"
            TRANSACTION_URL = "https://sandbox.uphold.com/reserve/transactions/%s"
            LOGOUT_URL = "https://sandbox.uphold.com/dashboard/more"
            PROFILE_URL = "https://sandbox.uphold.com/dashboard/settings/profile"
            UPHOLD_CURRENCY_LIST = "https://api-sandbox.uphold.com/v0/assets"
        }
    }

    fun hasValidCredentials(): Boolean {
        return CLIENT_ID.isNotEmpty() && !CLIENT_ID.contains("CLIENT_ID")
    }
}
