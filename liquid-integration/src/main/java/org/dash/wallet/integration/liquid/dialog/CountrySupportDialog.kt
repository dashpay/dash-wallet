package org.dash.wallet.integration.liquid.dialog

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import org.dash.wallet.integration.liquid.R
import org.dash.wallet.integration.liquid.data.LiquidConstants
import org.dash.wallet.integration.liquid.ui.WebViewActivity

class CountrySupportDialog(val contexts: Context) : Dialog(contexts, R.style.Theme_Dialog) {

    init {
        setCanceledOnTouchOutside(false)
        setCancelable(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_crypto_support)

        findViewById<Button>(R.id.btnOkay).setOnClickListener(object : View.OnClickListener {
            override fun onClick(v: View) {
                dismiss()
            }
        })

        findViewById<TextView>(R.id.txtCountrySupported).setOnClickListener {
            dismiss()
            val intent = Intent(contexts, WebViewActivity::class.java)
            intent.putExtra("url", LiquidConstants.COUNTRY_NOT_SUPPORTED)
            intent.putExtra("title", "Liquid")
            contexts.startActivity(intent)

        }
    }

}