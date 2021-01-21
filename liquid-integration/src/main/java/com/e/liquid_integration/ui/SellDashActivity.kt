package com.e.liquid_integration.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.widget.Toolbar
import com.e.liquid_integration.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.dash.wallet.common.InteractionAwareActivity

class SellDashActivity : InteractionAwareActivity() {
    private var bottomSheetDialog: BottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sell_dash)
        initUI()
    }

    private fun initUI() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        setTitle(getString(R.string.sell_dash))

        findViewById<View>(R.id.ivInfo).setOnClickListener { buyDash() }

    }

    private fun buyDash() {

        val dialogView = layoutInflater.inflate(R.layout.dialog_buy_dash, null)
        bottomSheetDialog = BottomSheetDialog(this)
        bottomSheetDialog!!.setContentView(dialogView!!)
        bottomSheetDialog!!.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheetDialog!!.show()

        val ll_credit_card = dialogView.findViewById(R.id.ll_credit_card) as LinearLayout
        val ll_cryptocurrency = dialogView.findViewById(R.id.ll_cryptocurrency) as LinearLayout
        val collapse_button = dialogView.findViewById(R.id.collapse_button) as ImageView

        ll_credit_card.setOnClickListener {
            bottomSheetDialog!!.dismiss()
            startActivity(Intent(this, BuyDashCryptoActivity::class.java))
        }

        ll_cryptocurrency.setOnClickListener {
            bottomSheetDialog!!.dismiss()
        }

        collapse_button.setOnClickListener {
            bottomSheetDialog!!.dismiss()
        }

    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }
}