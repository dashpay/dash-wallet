package org.dash.wallet.features.exploredash.ui.dialogs

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import org.dash.wallet.features.exploredash.R

class BuyGiftCardDescriptionDialog : OffsetDialogFragment<ConstraintLayout>() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.buy_gift_card_description, container, false)
    }
}