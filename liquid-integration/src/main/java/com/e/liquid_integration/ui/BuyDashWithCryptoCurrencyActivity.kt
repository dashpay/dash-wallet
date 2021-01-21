package com.e.liquid_integration.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.e.liquid_integration.R
import com.e.liquid_integration.data.LiquidClient
import com.e.liquid_integration.data.LiquidConstants
import com.e.liquid_integration.dialog.CountrySupportDialog
import com.e.liquid_integration.model.WidgetResponse
import com.google.gson.Gson
import org.dash.wallet.common.WalletDataProvider

class BuyDashWithCryptoCurrencyActivity : AppCompatActivity() {

    private lateinit var webview: WebView
    private var walletAddress: String? = null
    private val mJsInterfaceName = "Android"
    private var isTransestionSuccessful = false
    private var error: String? = null


    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview_quick_exchange)
        webview = findViewById(R.id.webview)
        initToolBar()
    }

    private fun initToolBar() {

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        setTitle(getString(R.string.buy_dash))
        getAllData()
    }

    private fun getAllData() {
        val walletDataProvider = application as WalletDataProvider
        val freshReceiveAddress = walletDataProvider.freshReceiveAddress()
        walletAddress = freshReceiveAddress.toBase58()

        webview.clearCache(true)

        findViewById<View>(R.id.ivInfo).setOnClickListener {
            CountrySupportDialog(this).show()
        }
        webview.webViewClient = MyBrowser()
        webview.settings.javaScriptEnabled = true
        webview.settings.domStorageEnabled = true
        webview.addJavascriptInterface(JavaScriptInterface(), mJsInterfaceName)
        webview.setBackgroundColor(0xFFFFFF)

        webview.loadUrl(LiquidConstants.BUY_WITH_CREDIT_CARD_URL)
        askCameraPermission()
    }


    private fun askCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            //redirectToWidget()
            //Already Granted permission
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), BuyDashWithCreditCardActivity.REQUEST_CODE_CAMERA_PERMISSION)
        }

    }

    private inner class MyBrowser : WebViewClient() {
        override fun onPageFinished(webview: WebView, url: String) {
            super.onPageFinished(webview, url)
            webview.visibility = View.VISIBLE
            bindListener();
            sendInitialization();
        }

    }

    private fun sendInitialization() {
        val initializationConfig = InitializationConfig(
                public_api_key = LiquidConstants.PUBLIC_API_KEY,

                /* payout_default = InitializationIdentityPayout(
                         currency = "DASH",
                         quantity = "5"
                 ),*/
                payout_settlement = InitializationSettlement(
                        method = "BLOCKCHAIN_TRANSFER",
                        currency = "DASH",
                        //   quantity = "10",//"1.0",
                        input_parameters = SettlementParameters(
                                account_key = SettlementParameter(
                                        type = "WALLET_ADDRESS",
                                        value = "XaxsLtAAh9LeyPdxTC5o2ZuwQaniELzYtQ"//walletAddress.toString()// "XaxsLtAAh9LeyPdxTC5o2ZuwQaniELzYtQ"
                                )
                        )
                ),

                funding_default = InitializationIdentityFunding(
                        currency = "BTC",
                        quantity = "5",
                        currency_scheme = "CRYPTO"

                ),

                /*funding_settlement = InitializationSettlement(
                        currency_scheme = "CRYPTO",
                      *//*  method = "BLOCKCHAIN_TRANSFER",
                        //currency = "BCH",
                        //   quantity = "10",//"1.0",
                        input_parameters = SettlementParameters(
                                account_key = SettlementParameter(
                                        type = "WALLET_ADDRESS",
                                        value = "XaxsLtAAh9LeyPdxTC5o2ZuwQaniELzYtQ"//walletAddress.toString()// "XaxsLtAAh9LeyPdxTC5o2ZuwQaniELzYtQ"
                                )
                        )*//*
                ),*/
                identity = InitializationIdentity(
                        UserSession(LiquidClient.getInstance()!!.storedSessionId, LiquidClient.getInstance()!!.storedSessionSecret)
                )
        )
        val initializationJson = Gson().toJson(initializationConfig)

        println("PARAMS:::" + initializationJson)
        executeJavascriptInWebview(
                "window.initializeWidget(JSON.parse('$initializationJson'));"
        );
    }

    private inner class JavaScriptInterface {
        @JavascriptInterface
        fun handleData(eventData: String) {
            runOnUiThread {
                try {
                    println("EventData::$eventData")
                    val base = Gson().fromJson(eventData, WidgetEvent::class.java)
                    when (base?.event) {
                        "step_transition" -> {
                            val stepTransition = Gson().fromJson(eventData, WidgetResponse::class.java)
                            when (stepTransition.data?.newStep) {
                                "success" -> {
                                    //finish()
                                    setResult(Activity.RESULT_OK)
                                    isTransestionSuccessful = true
                                }
                            }
                        }
                        "ERROR" -> {
                            error = eventData
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // FirebaseCrashlytics.getInstance().recordException(RuntimeException(eventData))
                }
            }
        }
    }


    private fun bindListener() {
        executeJavascriptInWebview(
                "window.onWidgetEvent((event) => window.$mJsInterfaceName.handleData(JSON.stringify(event)));"
        );
    }

    fun executeJavascriptInWebview(rawJavascript: String) {
        runOnUiThread {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                webview.evaluateJavascript(rawJavascript, null);
            } else {
                val javaScriptFunctionCall = "(function() { $rawJavascript })()";
                webview.loadUrl("javascript:$javaScriptFunctionCall;");
            }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == BuyDashWithCreditCardActivity.REQUEST_CODE_CAMERA_PERMISSION) {
            when {
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED -> ""
                else -> showDialog(BuyDashWithCreditCardActivity.REQUEST_CODE_CAMERA_PERMISSION)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onDestroy() {
        webview.removeJavascriptInterface(mJsInterfaceName)
        super.onDestroy()
    }


}