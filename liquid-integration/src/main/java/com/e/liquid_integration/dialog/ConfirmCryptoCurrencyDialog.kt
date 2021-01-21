package com.e.liquid_integration.dialog

import android.content.Context
import android.content.Intent
import android.widget.Button
import com.e.liquid_integration.R
import com.e.liquid_integration.ui.LiquidLoginActivity
import com.google.android.material.bottomsheet.BottomSheetDialog


class ConfirmCryptoCurrencyDialog(val _context: Context) : BottomSheetDialog(_context) {

    init {
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        create()
    }

    override fun create() {

        val bottomSheetView = layoutInflater.inflate(R.layout.dialog_confirm_cryptocurrency, null)
        setContentView(bottomSheetView)

        val confirmPayment = bottomSheetView.findViewById<Button>(R.id.confirm_payment)
        confirmPayment.setOnClickListener {
            dismiss()
            val intent = Intent(_context, LiquidLoginActivity::class.java)
            intent.putExtra("url", "https://demo.partners.liquid.com/")
            intent.putExtra("title", "Buy Cryptocurrency")
            _context.startActivity(intent)
        }

    }
}