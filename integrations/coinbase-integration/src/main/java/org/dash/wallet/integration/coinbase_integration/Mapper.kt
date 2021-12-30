package org.dash.wallet.integration.coinbase_integration

import org.dash.wallet.integration.coinbase_integration.model.*

interface Mapper<in Input, out Output> {
    fun map(input: Input): Output
}

class PlaceBuyOrderMapper: Mapper<BuyOrderData?, PlaceBuyOrderUIModel> {
    override fun map(input: BuyOrderData?): PlaceBuyOrderUIModel {
        return if (input == null)
            BuyOrderResponse.EMPTY_PLACE_BUY
        else {
            PlaceBuyOrderUIModel(input.id ?:"",
                input.paymentMethod?.id?:"",
                input.subtotal?.amount?: "",
                input.subtotal?.currency?: "",
                input.fee?.amount?: "",
                input.fee?.currency?: "",
                 input.total?.amount?: "",
                input.total?.currency?: "",
                input.amount?.amount?: "")
        }
    }
}


class CommitBuyOrderMapper: Mapper<BuyOrderData?, CommitBuyOrderUIModel> {
    override fun map(input: BuyOrderData?): CommitBuyOrderUIModel {
        return if (input == null)
            BuyOrderResponse.EMPTY_COMMIT_BUY
        else {
            CommitBuyOrderUIModel(dashAmount = input.amount?.amount ?: "")
        }
    }
}

class SendFundsToWalletMapper: Mapper<SendTransactionToWalletData?, SendTransactionToWalletUIModel> {
    override fun map(input: SendTransactionToWalletData?): SendTransactionToWalletUIModel {
        return if (input == null) {
            SendTransactionToWalletResponse.EMPTY
        }
        else {
            SendTransactionToWalletUIModel(input.status)
        }
    }
}