package org.dash.wallet.features.exploredash.data.dashspend

import androidx.annotation.DrawableRes
import org.dash.wallet.features.exploredash.R

sealed class GiftCardService(
    @DrawableRes val logo: Int,
    val termsAndConditions: String
) {
    object CTX : GiftCardService(R.drawable.ic_ctx_logo_blue, "https://ctx.com/gift-card-agreement/")
    object PiggyCards : GiftCardService(
        R.drawable.ic_piggycards_logo,
        "https://piggy.cards/index.php?route=information/information&information_id=5"
    )
}
