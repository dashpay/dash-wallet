package org.dash.wallet.integration.liquid.ui


import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.dash.wallet.integration.liquid.data.LiquidClient
import org.dash.wallet.integration.liquid.data.LiquidConstants
import org.dash.wallet.integration.liquid.dialog.CountrySupportDialog
import org.dash.wallet.integration.liquid.model.WidgetResponse
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.integration.liquid.R
import org.slf4j.LoggerFactory


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
        var quantity: String? = null,
        var currency_scheme: String? = null
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

        private val log = LoggerFactory.getLogger(BuyDashWithCreditCardActivity::class.java)

        const val REQUEST_CODE_CAMERA_PERMISSION = 101
        fun newInstance(context: Context?): Intent {
            return Intent(context, BuyDashWithCreditCardActivity::class.java)
        }
    }

    private val mJsInterfaceName = "Android"
    private var error: String? = null
    private lateinit var webview: WebView
    private var walletAddress: String? = null
    private var userAmount: String? = null
    private var isTransestionSuccessful = false

    private var mPermissionRequest: PermissionRequest? = null
    val FILE_CHOOSER_RESULT_CODE = 1
    var uploadMessage: ValueCallback<Uri?>? = null
    var uploadMessageAboveL: ValueCallback<Array<Uri?>?>? = null

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
            setHomeAsUpIndicator(R.drawable.ic_close)
        }

        setTitle(getString(R.string.buy_dash))


        userAmount = intent.getStringExtra("Amount")
        val walletDataProvider = application as WalletDataProvider
        val freshReceiveAddress = walletDataProvider.freshReceiveAddress()
        walletAddress = freshReceiveAddress.toBase58()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().removeAllCookies(null)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().flush()
        }

        webview.clearCache(true)
        webview.clearFormData()
        webview.clearHistory()
        webview.clearSslPreferences()


        webview.webViewClient = MyBrowser()
        webview.settings.javaScriptEnabled = true
        webview.settings.domStorageEnabled = true
        webview.addJavascriptInterface(JavaScriptInterface(), mJsInterfaceName)
        webview.setBackgroundColor(0xFFFFFF)
        webview.settings.javaScriptEnabled = true
        webview.settings.domStorageEnabled = true
        webview.settings.javaScriptCanOpenWindowsAutomatically = true
        webview.settings.allowFileAccess = true
        webview.settings.allowUniversalAccessFromFileURLs = true
        webview.settings.allowFileAccessFromFileURLs = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            webview.settings.mediaPlaybackRequiresUserGesture = false
        }

        /**
         * This is for checking camera permission for selfie and upload document
         */
        webview.setWebChromeClient(object : WebChromeClient() {

            override fun onPermissionRequestCanceled(request: PermissionRequest?) {
                super.onPermissionRequestCanceled(request)
                Toast.makeText(
                        this@BuyDashWithCreditCardActivity,
                        "Permission Denied. Please enable from setting.",
                        Toast.LENGTH_SHORT
                ).show()
            }

            @SuppressLint("NewApi")
            override fun onPermissionRequest(request: PermissionRequest) {
                mPermissionRequest = request
                for (str in request.resources) {
                    if (str.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
                        runOnUiThread({
                            askCameraPermission()
                        })
                        break
                    }
                }

            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
            }

            //For Android API < 11 (3.0 OS)
            fun openFileChooser(valueCallback: ValueCallback<Uri?>) {
                uploadMessage = valueCallback
                openImageChooserActivity()
            }

            //For Android API >= 11 (3.0 OS)
            fun openFileChooser(
                    valueCallback: ValueCallback<Uri?>,
                    acceptType: String?,
                    capture: String?
            ) {
                uploadMessage = valueCallback
                openImageChooserActivity()
            }

            //For Android API >= 21 (5.0 OS)
            override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri?>?>,
                    fileChooserParams: FileChooserParams?
            ): Boolean {
                uploadMessageAboveL = filePathCallback
                openImageChooserActivity()
                return true
            }
        })


        webview.loadUrl(LiquidConstants.BUY_WITH_CREDIT_CARD_URL)

        findViewById<View>(R.id.ivInfo).setOnClickListener {
            CountrySupportDialog(this).show()
        }

    }


    private fun openImageChooserActivity() {
        val i = Intent(Intent.ACTION_GET_CONTENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.setType("image/*")
        startActivityForResult(Intent.createChooser(i, "Image Chooser"), FILE_CHOOSER_RESULT_CODE)
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (null == uploadMessage && null == uploadMessageAboveL) return
            val result = if (data == null || resultCode != RESULT_OK) null else data.data
            if (uploadMessageAboveL != null) {
                onActivityResultAboveL(requestCode, resultCode, data)
            } else if (uploadMessage != null) {
                uploadMessage!!.onReceiveValue(result)
                uploadMessage = null
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun onActivityResultAboveL(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode != FILE_CHOOSER_RESULT_CODE || uploadMessageAboveL == null) return
        var results: Array<Uri?>? = null
        if (resultCode == RESULT_OK) {
            if (intent != null) {
                val dataString = intent.dataString
                val clipData: ClipData? = intent.clipData
                if (clipData != null) {
                    results = arrayOfNulls(clipData.getItemCount())
                    for (i in 0 until clipData.getItemCount()) {
                        val item: ClipData.Item = clipData.getItemAt(i)
                        results[i] = item.getUri()
                    }
                }
                if (dataString != null) results = arrayOf(Uri.parse(dataString))
            }
        }
        uploadMessageAboveL!!.onReceiveValue(results)
        uploadMessageAboveL = null
    }


    @SuppressLint("NewApi")
    private fun askCameraPermission() {
        if (ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
        ) {
            mPermissionRequest!!.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))

        } else {
            ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.CAMERA),
                    REQUEST_CODE_CAMERA_PERMISSION
            )
        }
    }

    @SuppressLint("NewApi")
    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_CAMERA_PERMISSION) {
            when {
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED -> {
                    mPermissionRequest!!.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                }
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


    /**
     * Passing paylod into widget
     *
     */
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
                        input_parameters = SettlementParameters(
                                account_key = SettlementParameter(
                                        type = "WALLET_ADDRESS",
                                        value = walletAddress.toString()
                                )
                        )
                ),

                identity = InitializationIdentity(
                        UserSession(LiquidClient.getInstance()!!.storedSessionId, LiquidClient.getInstance()!!.storedSessionSecret)
                )
        )
        val initializationJson = Gson().toJson(initializationConfig)

        log.debug("PARAMS:::$initializationJson")
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
            bindListener()
            sendInitialization()
        }
    }


    /**
     * Handle widget transaction
     */
    private inner class JavaScriptInterface {
        @JavascriptInterface
        fun handleData(eventData: String) {
            runOnUiThread {
                try {
                    log.debug("EventData::$eventData")
                    val base = Gson().fromJson(eventData, WidgetEvent::class.java)
                    when (base?.event) {
                        "step_transition" -> {
                            val stepTransition = Gson().fromJson(eventData, WidgetResponse::class.java)
                            when (stepTransition.data?.newStep) {
                                "success" -> {
                                    setResult(Activity.RESULT_OK)
                                    if (!isTransestionSuccessful) {
                                        findViewById<View>(R.id.closePane).visibility = View.VISIBLE
                                        findViewById<View>(R.id.btnOkay).setOnClickListener {
                                            onBackPressed()
                                        }
                                    }
                                    isTransestionSuccessful = true
                                }
                            }
                        }
                        "ERROR" -> {
                            error = eventData
                        }
                    }
                } catch (e: Exception) {
                    log.warn(e.message, e)
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

    override fun onDestroy() {
        webview.removeJavascriptInterface(mJsInterfaceName)
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (isTransestionSuccessful) {
            setResult(Activity.RESULT_OK)
            super.onBackPressed()
        } else {
            finish()
        }
    }
}