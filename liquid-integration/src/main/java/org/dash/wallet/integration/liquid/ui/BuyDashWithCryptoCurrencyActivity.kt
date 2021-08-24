package org.dash.wallet.integration.liquid.ui

import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
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
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.integration.liquid.R
import org.slf4j.LoggerFactory


class BuyDashWithCryptoCurrencyActivity : InteractionAwareActivity() {

    companion object {
        private val log = LoggerFactory.getLogger(BuyDashWithCryptoCurrencyActivity::class.java)
    }

    private lateinit var webview: WebView
    private var walletAddress: String? = null
    private val mJsInterfaceName = "Android"
    private var isTransestionSuccessful = false
    private var error: String? = null
    private var mPermissionRequest: PermissionRequest? = null


    public override fun onCreate(savedInstanceState: Bundle?) {
        log.info("liquid: starting buy dash with crypto currency")
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun getAllData() {
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


        findViewById<View>(R.id.ivInfo).setOnClickListener {
            CountrySupportDialog(this, false).show()
        }
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
                        this@BuyDashWithCryptoCurrencyActivity,
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
    }



    @SuppressLint("NewApi")
    fun showPermissionDialog(request: PermissionRequest) {
        val builder = AlertDialog.Builder(this)
        //set title for alert dialog
        builder.setTitle("Allow Permission")
        //set message for alert dialog
        builder.setMessage("Allow Permission to camera")

        //performing positive action
        builder.setPositiveButton("Yes") { dialogInterface, which ->
            request.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE));
        }
        //performing negative action
        builder.setNegativeButton("No") { dialogInterface, which ->
            request.deny();
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(false)
        alertDialog.show()
    }

    val FILE_CHOOSER_RESULT_CODE = 1
    var uploadMessage: ValueCallback<Uri?>? = null
    var uploadMessageAboveL: ValueCallback<Array<Uri?>?>? = null

    private fun openImageChooserActivity() {
        log.info("liquid: open image chooser")
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
                    BuyDashWithCreditCardActivity.REQUEST_CODE_CAMERA_PERMISSION
            )
        }
    }

    private inner class MyBrowser : WebViewClient() {
        var lastUrl = ""
        override fun onPageFinished(webview: WebView, url: String) {
            if (lastUrl != url) {
                log.info("liquid: page finished: $url")
            }
            super.onPageFinished(webview, url)
            webview.visibility = View.VISIBLE
            bindListener()
            sendInitialization()
        }

    }

    private fun sendInitialization() {
        val initializationConfig = InitializationConfig(
                public_api_key = LiquidConstants.PUBLIC_API_KEY,

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

                funding_default = InitializationIdentityFunding(
                        currency = intent.getStringExtra("CurrencySelected"),
                        currency_scheme = "CRYPTO"

                ),

                identity = InitializationIdentity(
                        UserSession(
                                LiquidClient.getInstance()!!.storedSessionId,
                                LiquidClient.getInstance()!!.storedSessionSecret
                        )
                )
        )
        val initializationJson = Gson().toJson(initializationConfig)

        executeJavascriptInWebview(
                "window.initializeWidget(JSON.parse('$initializationJson'));"
        );
    }

    private inner class JavaScriptInterface {
        var lastEvent = ""
        @JavascriptInterface
        fun handleData(eventData: String) {
            runOnUiThread {
                try {
                    if (lastEvent != eventData) {
                        log.info("liquid: EventData::$eventData")
                        lastEvent = eventData;
                    }
                    val base = Gson().fromJson(eventData, WidgetEvent::class.java)
                    when (base?.event) {
                        "step_transition" -> {
                            val stepTransition =
                                    Gson().fromJson(eventData, WidgetResponse::class.java)
                            when (stepTransition.data?.newStep) {
                                "success" -> {
                                    setResult(Activity.RESULT_OK)
                                    isTransestionSuccessful = true
                                    log.info("liquid: buy dash with crypto transaction successful")
                                }
                            }
                        }
                        "ERROR" -> {
                            error = eventData
                            log.error("liquid: $error")
                        }
                    }
                } catch (e: Exception) {
                    log.error("liquid:  ${e.message}", e)
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
        log.info("liquid: execute script: $rawJavascript")
        runOnUiThread {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.KITKAT) {
                webview.evaluateJavascript(rawJavascript, null);
            } else {
                val javaScriptFunctionCall = "(function() { $rawJavascript })()";
                webview.loadUrl("javascript:$javaScriptFunctionCall;")
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

    @SuppressLint("NewApi")
    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        if (requestCode == BuyDashWithCreditCardActivity.REQUEST_CODE_CAMERA_PERMISSION) {
            when {
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED -> {
                    mPermissionRequest!!.grant(arrayOf(PermissionRequest.RESOURCE_VIDEO_CAPTURE))
                }
                else -> showDialog(BuyDashWithCreditCardActivity.REQUEST_CODE_CAMERA_PERMISSION)
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onDestroy() {
        log.info("liquid: closing buy dash with crypto currency")
        webview.removeJavascriptInterface(mJsInterfaceName)
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (isTransestionSuccessful) {
            val intent = Intent()
            intent.setClassName(this, "de.schildbach.wallet.ui.WalletActivity")
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            finish()
        } else {
            finish()
        }
    }
}