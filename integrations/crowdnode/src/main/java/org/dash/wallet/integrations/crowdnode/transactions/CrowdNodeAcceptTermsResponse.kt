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

package org.dash.wallet.integrations.crowdnode.transactions

import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.transactions.filters.CoinsFromAddressTxFilter
import org.dash.wallet.integrations.crowdnode.model.ApiCode
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants

// TODO: consider making sure that `toAddress` matches our account address
class CrowdNodeAcceptTermsResponse(networkId: String) : CoinsFromAddressTxFilter(
    CrowdNodeConstants.getCrowdNodeAddress(networkId),
    ACCEPT_TERMS_RESPONSE_CODE
) {
    companion object {
        val ACCEPT_TERMS_RESPONSE_CODE: Dash =
            CrowdNodeConstants.API_OFFSET + Dash.valueOf(ApiCode.PleaseAcceptTerms.code)
    }
}
