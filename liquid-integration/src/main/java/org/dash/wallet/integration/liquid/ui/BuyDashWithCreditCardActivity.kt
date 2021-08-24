package org.dash.wallet.integration.liquid.ui


import android.Manifest
import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import androidx.core.view.isVisible
import org.dash.wallet.integration.liquid.data.LiquidClient
import org.dash.wallet.integration.liquid.data.LiquidConstants
import org.dash.wallet.integration.liquid.dialog.CountrySupportDialog
import org.dash.wallet.integration.liquid.model.WidgetResponse
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import org.dash.wallet.common.Constants
import org.dash.wallet.common.InteractionAwareActivity
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

class BuyDashWithCreditCardActivity : InteractionAwareActivity() {

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
    private var isTransactionSuccessful = false

    private var mPermissionRequest: PermissionRequest? = null
    val FILE_CHOOSER_RESULT_CODE = 1
    var uploadMessage: ValueCallback<Uri?>? = null
    var uploadMessageAboveL: ValueCallback<Array<Uri?>?>? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        log.info("liquid: starting buy dash with credit card")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_webview_quick_exchange)
        webview = findViewById(R.id.webview)
        loadWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadWebView() {

        log.info("liquid: loading webview")
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
                log.info("liquid: camera permission was denied")

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

        //don't show the (i) icon
        findViewById<View>(R.id.ivInfo).isVisible = false
        findViewById<View>(R.id.ivInfo).setOnClickListener {
            CountrySupportDialog(this, true).show()
        }
    }


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
        log.info("liquid: execute script: $rawJavascript")
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
        var finishedLoading = false
        override fun onPageFinished(webview: WebView, url: String) {
            if (webview.progress == 100 && !finishedLoading) {
                log.info("liquid: page finished(${webview.progress}%): $url")
                super.onPageFinished(webview, url)
                webview.visibility = View.VISIBLE
                bindListener()
                sendInitialization()
                finishedLoading = true
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            log.info("liquid: page started(${webview.progress}%): $url")
        }
    }


    /**
     * Handle widget transaction
     *
     * Flow - failed:
     *  ui_event (button_clicked) - clicked Buy With Visa
     *  transaction_created,
     *  step_transition(funding),
     *  step_transition(failure)  - clicked Pay $12.00 and credit card rejects
     *  step_transition(loading) - sometimes
     *  step_transition(outcome)
     *
     * Flow - success
     *  ui_event (button_clicked),  - clicked Buy With Visa
     *  transaction_created,
     *  step_transition(funding),
     *  step_transition(verifying),  - clicked Pay $12.00
     *  step_transition(success),
     */
    private inner class JavaScriptInterface {
        @JavascriptInterface
        fun handleData(eventData: String) {
            runOnUiThread {
                try {
                    log.info("liquid: EventData::$eventData")
                    val base = Gson().fromJson(eventData, WidgetEvent::class.java)
                    when (base?.event) {
                        "step_transition" -> {
                            val stepTransition = Gson().fromJson(eventData, WidgetResponse::class.java)
                            when (stepTransition.data?.newStep) {
                                // liquid: EventData::{"event":"step_transition","data":{"new_step":"success","old_step":"verifying","formPercent":100,"meta":{"transaction_id":"5b573089-1089-4b81-b626-41f2bae4fcbb","session_id":"3936771f-d3b9-4b0f-9334-1948282d362e","status":"PROCESSING","funding_settlement":{"settlement_instruction_id":"951359e1-f5f7-4ae4-be3e-52fc22a28344","transaction_id":"5b573089-1089-4b81-b626-41f2bae4fcbb","currency":"USD","direction":"FUNDING","method":"CARD_PAYMENT","quantity":"11.90","status":"READY","expires":{"unix_ms":1624047426828,"iso8601":"2021-06-18T20:17:06.828Z","ttl_ms":20000},"input_parameters":{"card_token":"tok_7rzjqrhaxzqexit3zqcqqiofuy","card_last4":"6887","card_network":"visa"},"result_parameters":{"card_receipt":"act_nhimctayonpkjo4vaiqx4q5hca"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/settlement/951359e1-f5f7-4ae4-be3e-52fc22a28344"},"capture_iframe":{"href":"https://partners.liquid.com/api/v1/method/card/951359e1-f5f7-4ae4-be3e-52fc22a28344"}}},"payout_settlement":{"settlement_instruction_id":"17954a9f-672d-4e7e-993d-499bdfd6d71c","transaction_id":"5b573089-1089-4b81-b626-41f2bae4fcbb","currency":"DASH","direction":"PAYOUT","method":"BLOCKCHAIN_TRANSFER","quantity":"0.0640","status":"READY","expires":{"unix_ms":1624047424897,"iso8601":"2021-06-18T20:17:04.897Z","ttl_ms":20000},"input_parameters":{"wallet_address":"XbVUUvtUUunEY971YoXUbrkejtkU7oXfHA"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/settlement/17954a9f-672d-4e7e-993d-499bdfd6d71c"}}},"quote":{"quote_id":"01F8GCETR40AQC3PQ0TNBFR0NE","status":"DEALABLE"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/transaction/5b573089-1089-4b81-b626-41f2bae4fcbb"}}}}}
                                "success" -> {
                                    log.info("liquid: buy dash transaction successful")
                                    onUserInteraction()
                                    if (!isTransactionSuccessful) {
                                        isTransactionSuccessful = true
                                        findViewById<View>(R.id.closePane).visibility = View.VISIBLE
                                        findViewById<View>(R.id.btnOkay).setOnClickListener {
                                            setResult(Constants.RESULT_CODE_GO_HOME)
                                            onBackPressed()
                                        }
                                    }
                                }
                                // liquid: EventData::{"event":"step_transition","data":{"new_step":"failure","old_step":"funding","formPercent":0,"meta":[{"severity":"FATAL","code":"3ds_failure","message":"Card 3DS failure.","context":"funding","trigger":"3ds-result"}]}}
                                "failure" -> {
                                    onUserInteraction()
                                }
                                // liquid: EventData::{"event":"step_transition","data":{"new_step":"loading","old_step":"failure","formPercent":0,"meta":{"transaction_id":"09b6e166-c994-4bfa-a838-a4332e8e906c","session_id":"e108dded-d956-4df1-9f52-0a2f9c15da1e","outcome_category":"EXTERNAL_REJECT","outcome_reason":"CARD_ISSUER_DECLINED_TRANSACTION","status":"VOID","funding_settlement":{"settlement_instruction_id":"339a667b-7ba6-4d18-b9aa-57f51eaba714","transaction_id":"09b6e166-c994-4bfa-a838-a4332e8e906c","currency":"USD","direction":"FUNDING","method":"CARD_PAYMENT","quantity":"12.00","status":"VOID","expires":{"unix_ms":1624108642767,"iso8601":"2021-06-19T13:17:22.767Z","ttl_ms":20000},"input_parameters":{"card_token":"tok_uzi7ofvao4sunl6dlwxgwotnty","card_last4":"3660","card_network":"visa"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/settlement/339a667b-7ba6-4d18-b9aa-57f51eaba714"},"capture_iframe":{"href":"https://partners.liquid.com/api/v1/method/card/339a667b-7ba6-4d18-b9aa-57f51eaba714"}}},"payout_settlement":{"settlement_instruction_id":"83276097-3ad7-4108-87ea-a155b6a4a7f2","transaction_id":"09b6e166-c994-4bfa-a838-a4332e8e906c","currency":"DASH","direction":"PAYOUT","method":"BLOCKCHAIN_TRANSFER","quantity":"0.063290","status":"READY","expires":{"unix_ms":1624108640242,"iso8601":"2021-06-19T13:17:20.242Z","ttl_ms":20000},"input_parameters":{"wallet_address":"XiHmLtFFZW5FgY5N4gmN52tcGnbDLormHf"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/settlement/83276097-3ad7-4108-87ea-a155b6a4a7f2"}}},"quote":{"quote_id":"01F8J6PV99CXAN5NMGNQH60STN","status":"DEALABLE"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/transaction/09b6e166-c994-4bfa-a838-a4332e8e906c"}}}}}
                                "loading" -> {
                                    onUserInteraction()
                                }
                                // liquid: EventData::{"event":"step_transition","data":{"new_step":"outcome","old_step":"loading","formPercent":0,"meta":{"transaction_id":"09b6e166-c994-4bfa-a838-a4332e8e906c","session_id":"e108dded-d956-4df1-9f52-0a2f9c15da1e","outcome_category":"EXTERNAL_REJECT","outcome_reason":"CARD_ISSUER_DECLINED_TRANSACTION","status":"VOID","funding_settlement":{"settlement_instruction_id":"339a667b-7ba6-4d18-b9aa-57f51eaba714","transaction_id":"09b6e166-c994-4bfa-a838-a4332e8e906c","currency":"USD","direction":"FUNDING","method":"CARD_PAYMENT","quantity":"12.00","status":"VOID","expires":{"unix_ms":1624108642767,"iso8601":"2021-06-19T13:17:22.767Z","ttl_ms":20000},"input_parameters":{"card_token":"tok_uzi7ofvao4sunl6dlwxgwotnty","card_last4":"3660","card_network":"visa"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/settlement/339a667b-7ba6-4d18-b9aa-57f51eaba714"},"capture_iframe":{"href":"https://partners.liquid.com/api/v1/method/card/339a667b-7ba6-4d18-b9aa-57f51eaba714"}}},"payout_settlement":{"settlement_instruction_id":"83276097-3ad7-4108-87ea-a155b6a4a7f2","transaction_id":"09b6e166-c994-4bfa-a838-a4332e8e906c","currency":"DASH","direction":"PAYOUT","method":"BLOCKCHAIN_TRANSFER","quantity":"0.063290","status":"READY","expires":{"unix_ms":1624108640242,"iso8601":"2021-06-19T13:17:20.242Z","ttl_ms":20000},"input_parameters":{"wallet_address":"XiHmLtFFZW5FgY5N4gmN52tcGnbDLormHf"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/settlement/83276097-3ad7-4108-87ea-a155b6a4a7f2"}}},"quote":{"quote_id":"01F8J6PV99CXAN5NMGNQH60STN","status":"DEALABLE"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/transaction/09b6e166-c994-4bfa-a838-a4332e8e906c"}}}}}
                                "outcome" -> {
                                    onUserInteraction()
                                }
                                // liquid: EventData::{"event":"step_transition","data":{"new_step":"funding","old_step":"quoting","formPercent":71,"meta":{"transaction_id":"09b6e166-c994-4bfa-a838-a4332e8e906c","session_id":"e108dded-d956-4df1-9f52-0a2f9c15da1e","status":"INPUT_REQUIRED","payout_settlement":{"settlement_instruction_id":"83276097-3ad7-4108-87ea-a155b6a4a7f2","transaction_id":"09b6e166-c994-4bfa-a838-a4332e8e906c","currency":"DASH","direction":"PAYOUT","method":"BLOCKCHAIN_TRANSFER","quantity":"0.063290","status":"READY","expires":{"unix_ms":1624108640242,"iso8601":"2021-06-19T13:17:20.242Z","ttl_ms":20000},"input_parameters":{"wallet_address":"XiHmLtFFZW5FgY5N4gmN52tcGnbDLormHf"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/settlement/83276097-3ad7-4108-87ea-a155b6a4a7f2"}}},"quote":{"quote_id":"01F8J6PV99CXAN5NMGNQH60STN","status":"DEALABLE"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/transaction/09b6e166-c994-4bfa-a838-a4332e8e906c"}}}}}
                                "funding" -> {
                                    onUserInteraction()
                                }
                                // liquid: EventData::{"event":"step_transition","data":{"new_step":"verifying","old_step":"funding","formPercent":85,"meta":{"transaction_id":"5b573089-1089-4b81-b626-41f2bae4fcbb","session_id":"3936771f-d3b9-4b0f-9334-1948282d362e","status":"INPUT_REQUIRED","funding_settlement":{"settlement_instruction_id":"951359e1-f5f7-4ae4-be3e-52fc22a28344","transaction_id":"5b573089-1089-4b81-b626-41f2bae4fcbb","currency":"USD","direction":"FUNDING","method":"CARD_PAYMENT","quantity":"11.90","status":"READY","expires":{"unix_ms":1624047426828,"iso8601":"2021-06-18T20:17:06.828Z","ttl_ms":20000},"input_parameters":{"card_token":"tok_7rzjqrhaxzqexit3zqcqqiofuy","card_network":"visa","card_last4":"6887"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/settlement/951359e1-f5f7-4ae4-be3e-52fc22a28344"},"capture_iframe":{"href":"https://partners.liquid.com/api/v1/method/card/951359e1-f5f7-4ae4-be3e-52fc22a28344"}}},"payout_settlement":{"settlement_instruction_id":"17954a9f-672d-4e7e-993d-499bdfd6d71c","transaction_id":"5b573089-1089-4b81-b626-41f2bae4fcbb","currency":"DASH","direction":"PAYOUT","method":"BLOCKCHAIN_TRANSFER","quantity":"0.0640","status":"READY","expires":{"unix_ms":1624047424897,"iso8601":"2021-06-18T20:17:04.897Z","ttl_ms":20000},"input_parameters":{"wallet_address":"XbVUUvtUUunEY971YoXUbrkejtkU7oXfHA"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/settlement/17954a9f-672d-4e7e-993d-499bdfd6d71c"}}},"quote":{"quote_id":"01F8GCAP78H5KNJTGV9V3C32B7","status":"DEALABLE"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/transaction/5b573089-1089-4b81-b626-41f2bae4fcbb"}}}}}
                                "verifying" -> {
                                    onUserInteraction()
                                }
                            }
                        }
                        // liquid: EventData::{"event":"ui_event","data":{"ui_event":"button_clicked","value":"next","target":"quote_view_next"}}
                        "ui-event" -> {
                            onUserInteraction()
                        }
                        // liquid: EventData::{"event":"transaction_created","data":{"transaction_id":"09b6e166-c994-4bfa-a838-a4332e8e906c","session_id":"e108dded-d956-4df1-9f52-0a2f9c15da1e","status":"INPUT_REQUIRED","payout_settlement":{"settlement_instruction_id":"83276097-3ad7-4108-87ea-a155b6a4a7f2","transaction_id":"09b6e166-c994-4bfa-a838-a4332e8e906c","currency":"DASH","direction":"PAYOUT","method":"BLOCKCHAIN_TRANSFER","quantity":"0.063290","status":"READY","expires":{"unix_ms":1624108640242,"iso8601":"2021-06-19T13:17:20.242Z","ttl_ms":20000},"input_parameters":{"wallet_address":"XiHmLtFFZW5FgY5N4gmN52tcGnbDLormHf"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/settlement/83276097-3ad7-4108-87ea-a155b6a4a7f2"}}},"quote":{"quote_id":"01F8J6PV99CXAN5NMGNQH60STN","status":"DEALABLE"},"_links":{"status":{"method":"get","href":"https://partners.liquid.com/api/v1/transaction/09b6e166-c994-4bfa-a838-a4332e8e906c"}}}}
                        "transaction-created" -> {
                            onUserInteraction()
                        }
                        // liquid: EventData::{"event":"suggested_size","data":{"backgroundHeight":416,"overflowHeight":551}}
                        "suggested_size" -> {

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
        log.info("liquid: closing buy dash with credit card")
        webview.removeJavascriptInterface(mJsInterfaceName)
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (isTransactionSuccessful) {
            log.info("liquid: onBackPressed: successful transaction was made")
            setResult(Constants.RESULT_CODE_GO_HOME)
            super.onBackPressed()
        } else {
            log.info("liquid: onBackPressed: successful transaction was not made")
            finish()
        }
    }
}