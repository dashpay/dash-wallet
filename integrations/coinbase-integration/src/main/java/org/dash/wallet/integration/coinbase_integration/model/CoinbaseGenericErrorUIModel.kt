package org.dash.wallet.integration.coinbase_integration.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CoinbaseGenericErrorUIModel(
    var title: Int,
    var message: String? = null,
    var image: Int? = null,
    var positiveButtonText: Int? = null,
    var negativeButtonText: Int? = null
): Parcelable
