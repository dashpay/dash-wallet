package org.dash.wallet.integration.liquid.dialog

import android.content.Context
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import org.dash.wallet.integration.liquid.listener.ValueSelectListener
import org.dash.wallet.common.ui.BaseBottomSheetDialog
import org.dash.wallet.integration.liquid.R


class SelectSellDashDialog(val contexts: Context, val listener: ValueSelectListener) : BaseBottomSheetDialog(contexts) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bottomSheetView = layoutInflater.inflate(R.layout.dialog_sell_dash, null)
        setContentView(bottomSheetView)
        val llCreditCard = bottomSheetView.findViewById(R.id.ll_credit_card) as LinearLayout
        val llCryptocurrency = bottomSheetView.findViewById(R.id.ll_cryptocurrency) as LinearLayout
        val collapseButton = bottomSheetView.findViewById(R.id.collapse_button) as ImageView

        llCreditCard.setOnClickListener {
            dismiss()
            listener.onItemSelected(1)
        }

        llCryptocurrency.setOnClickListener {
            dismiss()
            listener.onItemSelected(2)
        }

        collapseButton.setOnClickListener {
            dismiss()
        }
    }
}