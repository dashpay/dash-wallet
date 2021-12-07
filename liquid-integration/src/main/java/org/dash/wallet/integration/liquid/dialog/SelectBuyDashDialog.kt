package org.dash.wallet.integration.liquid.dialog

import android.content.Context
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import org.dash.wallet.integration.liquid.listener.ValueSelectListener
import org.dash.wallet.common.ui.BaseBottomSheetDialog
import org.dash.wallet.integration.liquid.R


class SelectBuyDashDialog(val contexts: Context, private val listener: ValueSelectListener) : BaseBottomSheetDialog(contexts) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bottomSheetView = layoutInflater.inflate(R.layout.dialog_buy_dash, null)
        setContentView(bottomSheetView)
        val llCreditCard = bottomSheetView.findViewById(R.id.ll_credit_card) as LinearLayout
        val llCryptocurrency = bottomSheetView.findViewById(R.id.ll_cryptocurrency) as LinearLayout
        val collapseButton = bottomSheetView.findViewById(R.id.collapse_button) as ImageView

        llCreditCard.setOnClickListener {
            dismiss()
            listener.onItemSelected(1)

            // Commented for future to use enter amount screen

            /*     val intent = Intent()
                 intent.setClassName(this, "de.schildbach.wallet.ui.LiquidTransferActivity")
                 intent.putExtra("extra_title", getString(R.string.buy_dash))
                 intent.putExtra("extra_message", "Enter the amount to transfer")
                 intent.putExtra("extra_max_amount", "17")
                 startActivityForResult(intent, 101)
     */

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