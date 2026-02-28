package org.dash.wallet.features.exploredash.data.dashspend

import android.os.Parcelable
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import kotlinx.parcelize.Parcelize
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.features.exploredash.R

//
@Parcelize
sealed class GiftCardProviderType(
    val name: String,
    @DrawableRes val logo: Int,
    @StringRes val disclaimer: Int,
    val termsAndConditions: String,
    val serviceName: String
) : Parcelable {
    data object CTX : GiftCardProviderType(
        "CTX",
        R.drawable.ic_ctx_logo_blue,
        R.string.log_in_to_ctxspend_account_desc,
        "https://ctx.com/gift-card-agreement/",
        ServiceName.CTXSpend
    )
    data object PiggyCards : GiftCardProviderType(
        "PiggyCards",
        R.drawable.ic_piggycards_logo,
        R.string.log_in_to_piggycards_account_desc,
        "https://piggy.cards/index.php?route=information/information&information_id=5",
        ServiceName.PiggyCards
    )

    companion object {
        fun fromProviderName(name: String): GiftCardProviderType {
            return when (name) {
                CTX.name -> CTX
                PiggyCards.name -> PiggyCards
                else -> CTX
            }
        }
    }
}
