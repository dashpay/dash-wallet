package org.dash.wallet.integrations.maya.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

sealed class TransactionType: Parcelable {
    @Parcelize
    object BuyDash: TransactionType()
    @Parcelize
    object SellSwap: TransactionType()
    @Parcelize
    object BuySwap: TransactionType()
    @Parcelize
    object TransferDash: TransactionType()
}