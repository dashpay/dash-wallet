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

import org.dash.wallet.integrations.coinbase.model.*

interface Mapper<in Input, out Output> {
    fun map(input: Input): Output
}

class PlaceBuyOrderMapper : Mapper<BuyOrderData?, PlaceBuyOrderUIModel> {
    override fun map(input: BuyOrderData?): PlaceBuyOrderUIModel {
        return if (input == null)
            BuyOrderResponse.EMPTY_PLACE_BUY
        else {
            PlaceBuyOrderUIModel(
                input.id ?: "",
                input.paymentMethod?.id ?: "",
                input.subtotal?.amount ?: "",
                input.subtotal?.currency ?: "",
                input.fee?.amount ?: "",
                input.fee?.currency ?: "",
                input.total?.amount ?: "",
                input.total?.currency ?: "",
                input.amount?.amount ?: ""
            )
        }
    }
}

class SwapTradeMapper : Mapper<SwapTradeResponseData?, SwapTradeUIModel> {
    override fun map(input: SwapTradeResponseData?): SwapTradeUIModel {
        return if (input == null)
            SwapTradeResponse.EMPTY_SWAP_TRADE
        else {
            SwapTradeUIModel(
                input.id ?: "",
                input.input_amount?.amount ?: "",
                input.input_amount?.currency ?: "",
                input.output_amount?.amount ?: "",
                input.output_amount?.currency ?: "",
                input.display_input_amount?.amount ?: "",
                input.display_input_amount?.currency ?: "",
                input.fee?.amount ?: "",
                input.fee?.currency ?: ""
            )
        }
    }
}

class CommitBuyOrderMapper : Mapper<BuyOrderData?, CommitBuyOrderUIModel> {
    override fun map(input: BuyOrderData?): CommitBuyOrderUIModel {
        return if (input == null)
            BuyOrderResponse.EMPTY_COMMIT_BUY
        else {
            CommitBuyOrderUIModel(dashAmount = input.amount?.amount ?: "")
        }
    }
}

class CoinbaseAddressMapper : Mapper<CoinBaseAccountAddressResponse?, String> {
    override fun map(input: CoinBaseAccountAddressResponse?): String {
        return if (input == null)
            ""
        else {
            input.data?.mapNotNull { it?.address }
                ?.firstOrNull { it.isNotEmpty() } ?: ""
        }
    }
}
