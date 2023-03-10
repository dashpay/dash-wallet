package org.dash.wallet.features.exploredash.data.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.Ignore
import kotlinx.parcelize.Parcelize
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.util.Constants

@Parcelize
@Entity(tableName = "gift_cards")
data class GiftCard(
    var merchantId: String,
    var merchantName: String,
    var transactionId: Sha256Hash,
    var number: String? = null,
    var pin: String? = null,
    var price: Fiat = Fiat.valueOf(Constants.USD_CURRENCY, 0),
    var merchantIconId: Sha256Hash? = null,
    var currentBalanceUrl: String? = null,
    @Ignore var merchantLogo: String? = null,
    @Ignore var barcodeImg: String? = null
) : Parcelable
