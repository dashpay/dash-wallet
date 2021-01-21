package com.e.liquid_integration.dialog

import android.content.Context
import android.widget.ImageView
import android.widget.LinearLayout
import com.e.liquid_integration.R
import com.e.liquid_integration.`interface`.ValueSelectListner
import com.google.android.material.bottomsheet.BottomSheetDialog


class SelectBuyDashDialog(val _context: Context, private val listner: ValueSelectListner) : BottomSheetDialog(_context) {

    init {
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        create()
    }

    override fun create() {


        val bottomSheetView = layoutInflater.inflate(R.layout.dialog_buy_dash, null)
        val dialog = BottomSheetDialog(_context, R.style.BottomSheetDialog) // Style here
        dialog.setContentView(bottomSheetView);
        dialog.show()


        val llCreditCard = bottomSheetView.findViewById(R.id.ll_credit_card) as LinearLayout
        val llCryptocurrency = bottomSheetView.findViewById(R.id.ll_cryptocurrency) as LinearLayout
        val collapseButton = bottomSheetView.findViewById(R.id.collapse_button) as ImageView

        llCreditCard.setOnClickListener {
            dialog.dismiss()

            listner.onItemSelected(1)

            // Commented for future to use enter amount screen

            /*     val intent = Intent()
                 intent.setClassName(this, "de.schildbach.wallet.ui.LiquidTransferActivity")
                 intent.putExtra("extra_title", getString(R.string.buy_dash))
                 intent.putExtra("extra_message", "Enter the amount to transfer")
                 intent.putExtra("extra_max_amount", "17")
                 startActivityForResult(intent, 101)
     */

            /* val intent = Intent(this, BuyDashCryptoActivity::class.java)
             intent.putExtra("From", "CreditCard")
             startActivity(intent)*/

        }

        llCryptocurrency.setOnClickListener {
            dialog.dismiss()
            listner.onItemSelected(2)
            /* val intent = Intent(_context, BuyDashCryptoActivity::class.java)
             intent.putExtra("From", "BuyCryptocurrency")
             _context.startActivity(intent)*/

        }

        collapseButton.setOnClickListener {
            dialog.dismiss()
        }
    }
}