package com.e.liquid_integration.dialog

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.e.liquid_integration.R
import com.e.liquid_integration.data.LiquidConstants
import com.e.liquid_integration.ui.WebViewActivity

class CreditCardTransactionSuccessSupportDialog(val contexts: Context,private val mClickListener: OnClickListenerInterface) : Dialog(contexts, R.style.Theme_Dialog) {

    interface OnClickListenerInterface {
        fun onClick()
    }

    init {
        setCanceledOnTouchOutside(false)
        setCancelable(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_credit_card_transaction_sucess)

        findViewById<Button>(R.id.btnOkay).setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                mClickListener.onClick()
                dismiss()
            }
        })
    }

}