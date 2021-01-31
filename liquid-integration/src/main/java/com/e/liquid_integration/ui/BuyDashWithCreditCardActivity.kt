package com.e.liquid_integration.ui


import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.webkit.*
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
import com.google.gson.annotations.SerializedName
import org.dash.wallet.common.WalletDataProvider


data class SettlementParameter(
        var type: String,
        var value: String
)

data class SettlementParameters(
        var account_key: SettlementParameter? = null,
        var transaction_key: SettlementParameter? = null
)


data class InitializationSettlement(
        var currency_scheme: String? = null,
        var method: String? = null,
        var currency: String? = null,
        /*var quantity: String? = null,*/
        var input_parameters: SettlementParameters? = null
)

data class UserSession(
        var session_id: String? = null,
        var session_secret: String? = null
)

data class InitializationIdentity(
        var user_session: UserSession? = null
)

data class InitializationIdentityFunding(
        var currency: String? = null,
        var quantity: String? = null,
        var currency_scheme: String? = null
)

data class InitializationIdentityPayout(
        var currency: String? = null,
        var quantity: String? = null
)


data class InitializationConfig(
        var public_api_key: String,
        var config_version: String? = "1.1",
        var payout_default: InitializationIdentityPayout? = null,
        var funding_default: InitializationIdentityFunding? = null,
        var theme: String = "light",
        var user_locale: String? = null,
        var funding_settlement: InitializationSettlement? = null,
        var payout_settlement: InitializationSettlement? = null,
        var identity: InitializationIdentity? = null
)

class WidgetEvent(
        @SerializedName("event")
        var event: String?
)


class BuyDashWithCreditCardActivity : AppCompatActivity() {

    companion object {
        const val REQUEST_CODE_CAMERA_PERMISSION = 101


        fun newInstance(context: Context?): Intent {
            val intent = Intent(context, BuyDashWithCreditCardActivity::class.java)
            return intent
        }

    }

    private val mJsInterfaceName = "Android"
    private var error: String? = null
    private lateinit var webview: WebView
    private var walletAddress: String? = null
    private var userAmount: String? = null
    private var isTransestionSuccessful = false

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview_quick_exchange)
        webview = findViewById(R.id.webview)
        loadWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadWebView() {

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)


        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        setTitle(getString(R.string.buy_dash))


        userAmount = intent.getStringExtra("Amount")
        val walletDataProvider = application as WalletDataProvider
        val freshReceiveAddress = walletDataProvider.freshReceiveAddress()
        walletAddress = freshReceiveAddress.toBase58()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().removeAllCookies(null)
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush()
        };

        webview.clearCache(true);
        webview.clearFormData();
        webview.clearHistory();
        webview.clearSslPreferences();


        webview.webViewClient = MyBrowser()
        webview.settings.javaScriptEnabled = true
        webview.settings.domStorageEnabled = true
        webview.addJavascriptInterface(JavaScriptInterface(), mJsInterfaceName)
        webview.setBackgroundColor(0xFFFFFF)

        webview.loadUrl(LiquidConstants.BUY_WITH_CREDIT_CARD_URL)

        findViewById<View>(R.id.ivInfo).setOnClickListener {
            CountrySupportDialog(this).show()
        }

        askCameraPermission()
    }

    private fun askCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            //redirectToWidget()
            //Already Granted permission
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQUEST_CODE_CAMERA_PERMISSION)
        }

    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_CAMERA_PERMISSION) {
            when {
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED -> ""
                else -> showDialog(REQUEST_CODE_CAMERA_PERMISSION)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }


    private fun bindListener() {
        executeJavascriptInWebview(
                "window.onWidgetEvent((event) => window.$mJsInterfaceName.handleData(JSON.stringify(event)));"
        );
    }

    private fun sendInitialization() {
        val initializationConfig = InitializationConfig(
                public_api_key = LiquidConstants.PUBLIC_API_KEY,

                payout_default = InitializationIdentityPayout(
                        currency = "DASH",
                        quantity = userAmount
                ),
                payout_settlement = InitializationSettlement(
                        method = "BLOCKCHAIN_TRANSFER",
                        currency = "DASH",
                        // quantity = userAmount,//"1.0",
                        input_parameters = SettlementParameters(
                                account_key = SettlementParameter(
                                        type = "WALLET_ADDRESS",
                                        value = walletAddress.toString()// "XaxsLtAAh9LeyPdxTC5o2ZuwQaniELzYtQ"//walletAddress.toString()// "XaxsLtAAh9LeyPdxTC5o2ZuwQaniELzYtQ"
                                )
                        )
                ),

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

    private inner class MyBrowser : WebViewClient() {
        override fun onPageFinished(webview: WebView, url: String) {
            super.onPageFinished(webview, url)
            webview.visibility = View.VISIBLE
            bindListener();
            sendInitialization();
        }

        override fun onReceivedError(webview: WebView, request: WebResourceRequest, webResourceError: WebResourceError) {
            super.onReceivedError(webview, request, webResourceError)
            if (request.isForMainFrame) {
                error = webResourceError.description.toString()
                // reload_button.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroy() {
        webview.removeJavascriptInterface(mJsInterfaceName)
        super.onDestroy()
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (isTransestionSuccessful) {
            setResult(Activity.RESULT_OK)
            super.onBackPressed()
        } else {
            /* if (webview.canGoBack()) {
                 webview.goBack();
             } else {
                 finish()
             }*/
            finish()
        }
    }

}