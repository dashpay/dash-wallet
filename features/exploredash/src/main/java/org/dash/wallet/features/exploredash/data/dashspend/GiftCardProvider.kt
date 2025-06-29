package org.dash.wallet.features.exploredash.data.dashspend

import android.os.Parcelable
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import kotlinx.parcelize.Parcelize
import org.dash.wallet.features.exploredash.R

@Parcelize
sealed class GiftCardProvider(
    val name: String,
    @DrawableRes val logo: Int,
    @StringRes val disclaimer: Int,
    val termsAndConditions: String
): Parcelable {
    object CTX: GiftCardProvider(
        "CTX",
        R.drawable.ic_ctx_logo_blue,
        R.string.log_in_to_ctxspend_account_desc,
        "https://ctx.com/gift-card-agreement/"
    )
    object PiggyCards: GiftCardProvider(
        "PiggyCards",
        R.drawable.ic_piggycards_logo,
        R.string.log_in_to_piggycards_account_desc,
        "https://piggy.cards/index.php?route=information/information&information_id=5"
    )
}