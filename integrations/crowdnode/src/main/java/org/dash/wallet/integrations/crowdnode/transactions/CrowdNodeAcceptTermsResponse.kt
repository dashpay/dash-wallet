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

import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.dash.wallet.common.transactions.filters.CoinsFromAddressTxFilter
import org.dash.wallet.integrations.crowdnode.model.ApiCode
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants

// TODO: consider making sure that `toAddress` matches our account address
class CrowdNodeAcceptTermsResponse(networkParams: NetworkParameters) : CoinsFromAddressTxFilter(
    CrowdNodeConstants.getCrowdNodeAddress(networkParams),
    ACCEPT_TERMS_RESPONSE_CODE
) {
    companion object {
        val ACCEPT_TERMS_RESPONSE_CODE: Coin =
            CrowdNodeConstants.API_OFFSET + Coin.valueOf(ApiCode.PleaseAcceptTerms.code)
    }
}
