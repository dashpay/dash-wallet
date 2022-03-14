package org.dash.wallet.integration.coinbase_integration

import org.dash.wallet.integration.coinbase_integration.model.*

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
