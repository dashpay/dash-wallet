package org.dash.wallet.integration.liquid.dialog

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import org.dash.wallet.integration.liquid.R
import org.dash.wallet.integration.liquid.data.LiquidConstants
import org.dash.wallet.integration.liquid.ui.WebViewActivity

class CountrySupportDialog(val contexts: Context, val isCreditCard: Boolean) : Dialog(contexts, R.style.Theme_Dialog) {

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

        findViewById<TextView>(R.id.buying_not_supported).apply {
            text = if (isCreditCard) {
                resources.getString(R.string.buying_dash_not_supported_credit_cards)
            } else {
                resources.getString(R.string.buying_dash_not_supported_crypto)
            }
        }

        findViewById<TextView>(R.id.payment_support).apply {
            text = if (isCreditCard) {
                resources.getString(R.string.credit_card_support)
            } else {
                resources.getString(R.string.crypto_support)
            }
        }

        findViewById<ImageView>(R.id.icon).apply {
            setImageDrawable(AppCompatResources.getDrawable(context, if (isCreditCard) {
                R.drawable.ic_creditcard
            } else {
                R.drawable.ic_cryptocurrency
            }))
        }
    }

}