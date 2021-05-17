package org.dash.wallet.integration.liquid.dialog

import android.content.Context
import android.widget.ImageView
import android.widget.LinearLayout
import org.dash.wallet.integration.liquid.listener.ValueSelectListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.dash.wallet.integration.liquid.R


class SelectBuyDashDialog(val contexts: Context, private val listener: ValueSelectListener) : BottomSheetDialog(contexts) {

    init {
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        create()
    }

    override fun create() {


        val bottomSheetView = layoutInflater.inflate(R.layout.dialog_buy_dash, null)
        val dialog = BottomSheetDialog(contexts, R.style.BottomSheetDialog) // Style here
        dialog.setContentView(bottomSheetView);
        dialog.show()


        val llCreditCard = bottomSheetView.findViewById(R.id.ll_credit_card) as LinearLayout
        val llCryptocurrency = bottomSheetView.findViewById(R.id.ll_cryptocurrency) as LinearLayout
        val collapseButton = bottomSheetView.findViewById(R.id.collapse_button) as ImageView

        llCreditCard.setOnClickListener {
            dialog.dismiss()

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
            dialog.dismiss()
            listener.onItemSelected(2)
        }

        collapseButton.setOnClickListener {
            dialog.dismiss()
        }
    }
}