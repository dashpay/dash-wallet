package com.e.liquid_integration.ui

import android.Manifest
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.TextUtils
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.e.liquid_integration.R
import com.e.liquid_integration.data.LiquidClient
import com.e.liquid_integration.dialog.ConfirmCryptoCurrencyDialog
import com.e.liquid_integration.dialog.CountrySupportDialog
import org.dash.wallet.common.InteractionAwareActivity

class BuyDashCryptoActivity : InteractionAwareActivity() {

    private var liquidClient: LiquidClient? = null


    private lateinit var edUserAmount: EditText
    private lateinit var edDashAmount: EditText

    private var loadingDialog: ProgressDialog? = null


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_buy_dash_crypto)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        liquidClient = LiquidClient.getInstance()

        setTitle(getString(R.string.buy_dash))

        edUserAmount = findViewById(R.id.edUserAmount)
        edDashAmount = findViewById(R.id.edDashAmount)


        val btnBuy = findViewById<Button>(R.id.btnBuy)
        btnBuy.setOnClickListener {
            validateBuyAmount()
        }



        findViewById<View>(R.id.ivInfo).setOnClickListener {
            CountrySupportDialog(this).show()
        }
        initProgressDialog()


    }

    private fun initProgressDialog() {

        loadingDialog = ProgressDialog(this)
        loadingDialog!!.isIndeterminate = true
        loadingDialog!!.setCancelable(false)
        loadingDialog!!.setMessage(getString(R.string.loading))

    }

    private fun validateBuyAmount() {

        val userAmount = edUserAmount.text.toString().trim()

        if (TextUtils.isEmpty(userAmount)) {
            Toast.makeText(this, getString(R.string.please_enter_amount), Toast.LENGTH_LONG).show()
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                redirectToWidget()
            } else {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), BuyDashWithCreditCardActivity.REQUEST_CODE_CAMERA_PERMISSION)
            }
        }
    }

    private fun redirectToWidget() {

        if (intent.getStringExtra("From").equals("CreditCard")) {

            val intent = Intent(this, BuyDashWithCreditCardActivity::class.java)
            intent.putExtra("Amount", edUserAmount.text.toString().trim())
            startActivity(intent)
        } else {

            ConfirmCryptoCurrencyDialog(this).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == BuyDashWithCreditCardActivity.REQUEST_CODE_CAMERA_PERMISSION) {
            when {
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED -> redirectToWidget()
                else -> showDialog(BuyDashWithCreditCardActivity.REQUEST_CODE_CAMERA_PERMISSION)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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