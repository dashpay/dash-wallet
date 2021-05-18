package org.dash.wallet.integration.liquid.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import org.dash.wallet.integration.liquid.currency.CurrencyResponse
import org.dash.wallet.integration.liquid.currency.PayloadItem
import org.dash.wallet.integration.liquid.data.LiquidClient
import org.dash.wallet.integration.liquid.data.LiquidConstants
import org.dash.wallet.integration.liquid.dialog.BuyDashCryptoCurrencyDialog
import org.dash.wallet.integration.liquid.dialog.SelectBuyDashDialog
import org.dash.wallet.integration.liquid.dialog.SelectSellDashDialog
import org.dash.wallet.integration.liquid.dialog.SellDashCryptoCurrencyDialog
import org.dash.wallet.integration.liquid.listener.ValueSelectListener
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Constants
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.customtabs.CustomTabActivityHelper
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.liquid.R
import org.json.JSONObject


class LiquidBuyAndSellDashActivity : InteractionAwareActivity() {

    companion object {
        fun createIntent(context: Context?): Intent {
            return if (LiquidClient.getInstance()!!.isAuthenticated) {
                Intent(context, LiquidBuyAndSellDashActivity::class.java)
            } else {
                Intent(context, LiquidSplashActivity::class.java)
            }
        }
    }

    private var liquidClient: LiquidClient? = null

    private lateinit var context: Context
    private var loadingDialog: ProgressDialog? = null

    private val cryptoCurrencyArrayList = ArrayList<PayloadItem>()
    private val fiatCurrencyList = ArrayList<PayloadItem>()
    private var isSelectFiatCurrency = false
    private var isClickLogoutButton = false

    var currentExchangeRate: ExchangeRate? = null

    override
    fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_liquid_buy_and_sell_dash)
        this.context = this@LiquidBuyAndSellDashActivity
        liquidClient = LiquidClient.getInstance()

        
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        setTitle(getString(R.string.buy_sell_dash_))

        loadingDialog = ProgressDialog(this)
        loadingDialog!!.isIndeterminate = true
        loadingDialog!!.setCancelable(false)
        loadingDialog!!.setMessage(getString(R.string.loading))

        findViewById<LinearLayout>(R.id.llDisconnect).setOnClickListener { openLogOutUrl() }
        findViewById<LinearLayout>(R.id.buy_dash).setOnClickListener { buyDash() }

        findViewById<LinearLayout>(R.id.sell_dash).setOnClickListener {
            showSellDashDialog()
        }

        findViewById<LinearLayout>(R.id.llTransferToLiquid).setOnClickListener {

            // Commented code for future to use for this flow required this activity
            /* val intent = Intent()
             intent.setClassName(this, "de.schildbach.wallet.ui.LiquidDashToDashTransferActivity")
             intent.putExtra("extra_title", getString(R.string.liquid))
             intent.putExtra("extra_message", "Enter the amount to transfer")
             intent.putExtra("extra_max_amount", "17")
             startActivityForResult(intent, 101)*/
            getUserLiquidAccountAddress()
            /*val walletDataProvider = application as WalletDataProvider
            val address = Address.fromBase58(MainNetParams.get(), "yTgh4Z1RrMXbJrbkbS7Lgk8NEZERJigMsy")
            val amount = Coin.CENT
            walletDataProvider.startSendCoinsForResult(this, 1234, address, amount)*/
        }


        if (LiquidClient.getInstance()!!.isAuthenticated) {
            getUserLiquidAccountBalance()
        }


        val walletDataProvider = application as WalletDataProvider
        val defaultCurrency = walletDataProvider.defaultCurrencyCode()

        walletDataProvider.getExchangeRate(defaultCurrency).observe(this,
                { exchangeRate ->
                    if (exchangeRate != null) {
                        currentExchangeRate = exchangeRate
                    }
                })

    }

    /**
     * SHow dialog of sell dash option fiat or cryptocurrency
     */

    private fun showSellDashDialog() {

        SelectSellDashDialog(context, object : ValueSelectListener {
            override fun onItemSelected(value: Int) {
                if (value == 1) {
                    isSelectFiatCurrency = true
                    if (fiatCurrencyList.size > 0) {
                        showSellDashCurrencyDialog()
                    } else {
                        getAllCurrencyList(false)
                    }

                } else if (value == 2) {
                    isSelectFiatCurrency = false
                    if (cryptoCurrencyArrayList.size > 0) {
                        showSellDashCurrencyDialog()
                    } else {
                        getAllCurrencyList(false)
                    }
                }
            }
        })
    }

    /**
     * Show dialog fiat or cryptocurrency selected currency
     */
    private fun showSellDashCurrencyDialog() {
        if (isSelectFiatCurrency) {

            SellDashCryptoCurrencyDialog(context, "FiatCurrency", fiatCurrencyList, object : ValueSelectListener {
                override fun onItemSelected(value: Int) {
                    val intent = Intent(context, SellDashActivity::class.java)
                    intent.putExtra("CurrencySelected", fiatCurrencyList[value].ccyCode)
                    intent.putExtra("CurrencyType", "FIAT")
                    startActivity(intent)

                }
            })

        } else {

            SellDashCryptoCurrencyDialog(context, "CryptoCurrency", cryptoCurrencyArrayList, object : ValueSelectListener {
                override fun onItemSelected(value: Int) {
                    val intent = Intent(context, SellDashActivity::class.java)
                    intent.putExtra("CurrencySelected", cryptoCurrencyArrayList[value].ccyCode)
                    intent.putExtra("CurrencyType", "CRYPTO")
                    startActivity(intent)

                }
            })
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

    private fun openLogOutUrl() {
        //revoke access to the token
        revokeAccessToken()
    }

    /**
     * Show dialog for buy dash option to select credit card and cryptocurrency
     */

    private fun buyDash() {

        SelectBuyDashDialog(context, object : ValueSelectListener {
            override fun onItemSelected(value: Int) {
                if (value == 1) {

                    val intent = Intent(context, BuyDashWithCreditCardActivity::class.java)
                    intent.putExtra("Amount", "5")
                    startActivityForResult(intent, Constants.USER_BUY_SELL_DASH)

                } else if (value == 2) {
                    if (cryptoCurrencyArrayList.size > 0) {
                        showCurrencyDialog()
                    } else {
                        getAllCurrencyList(true)
                    }
                }

            }

        })
    }

    /**
     * call api to  get liquid wallet balance
     */
    private fun getUserLiquidAccountBalance() {
        if (GenericUtils.isInternetConnected(this)) {
            loadingDialog!!.show()
            liquidClient?.getUserAccountBalance(liquidClient?.storedSessionId!!, object : LiquidClient.Callback<String> {
                override fun onSuccess(data: String) {
                    if (isFinishing) {
                        return
                    }
                    loadingDialog!!.hide()
                    showDashLiquidBalance(data)
                }

                override fun onError(e: Exception?) {
                    if (isFinishing) {
                        return
                    }
                    loadingDialog!!.hide()
                }
            })
        } else {
            GenericUtils.showToast(this, getString(R.string.internet_connected))
        }
    }

    /**
     * call api to  get address
     */
    private fun getUserLiquidAccountAddress() {
        if (GenericUtils.isInternetConnected(this)) {
            loadingDialog!!.show()
            liquidClient?.getUserAccountAddress(liquidClient?.storedSessionId!!, true, object : LiquidClient.Callback<String> {
                override fun onSuccess(data: String) {
                    if (isFinishing) {
                        return
                    }
                    loadingDialog!!.hide()
//                    showDashLiquidBalance(data)
                    val jsonObject = JSONObject(data)
                    val payloadObject = jsonObject.getJSONObject("payload")
                    if (payloadObject.length() != 0) {
                        payloadObject.getString("send_to_btc_address")
                    }
                    /*val walletDataProvider = application as WalletDataProvider
                    val address = Address.fromBase58(MainNetParams.get(), payloadObject.getString("send_to_btc_address"))
                    val amount = Coin.CENT
                    walletDataProvider.startSendCoinsForResult(this@LiquidBuyAndSellDashActivity, 1234, address, amount)*/
                }

                override fun onError(e: Exception?) {
                    if (isFinishing) {
                        return
                    }
                    loadingDialog!!.hide()
                }
            })
        } else {
            GenericUtils.showToast(this, getString(R.string.internet_connected))
        }
    }

    /**
     * Show iiquid wallet balance
     */

    private fun showDashLiquidBalance(data: String) {

        try {

            var amount: String? = null

            val jsonObject = JSONObject(data)
            val cryptoArray = jsonObject.getJSONObject("payload").getJSONArray("crypto_accounts")

            for (i in 0 until cryptoArray.length()) {
                val currency = cryptoArray.getJSONObject(i).getString("currency")
                if (currency == "DASH") {
                    findViewById<LinearLayout>(R.id.llLiquidAmount).visibility = View.VISIBLE
                    findViewById<TextView>(R.id.txtLiquidAmount).setText(cryptoArray.getJSONObject(i).getString("balance"))
                    amount = cryptoArray.getJSONObject(i).getString("balance")
                }
            }


            if (currentExchangeRate != null) {
                //amount = "4.0"
                val walletDataProvider = application as WalletDataProvider
                val defaultCurrency = walletDataProvider.defaultCurrencyCode()

                val dashAmount = try {
                    Coin.parseCoin(amount)
                } catch (x: Exception) {
                    Coin.ZERO
                }

                val exchangeRate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, currentExchangeRate?.fiat)
                val localValue = exchangeRate.coinToFiat(dashAmount)
                val fiatFormat = MonetaryFormat().noCode().minDecimals(2).optionalDecimals()

                findViewById<TextView>(R.id.txtUSAmount).setText(defaultCurrency + " " + if (dashAmount.isZero) "0.00" else fiatFormat.format(localValue))

            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == Constants.USER_BUY_SELL_DASH && resultCode == Activity.RESULT_OK) {
            setResult(Activity.RESULT_OK)
            onBackPressed()
        } else if (requestCode == 1) {
            if (resultCode == Activity.RESULT_OK) {
                val txHash = data!!.getStringExtra("transaction_hash")
                println(txHash)
            }
        }
    }

    /**
     * Show dialog for logout from liquid
     */
    private fun revokeAccessToken() {

        val dialogBuilder = AlertDialog.Builder(this)
        dialogBuilder.setMessage(R.string.liquid_logout_title)
        dialogBuilder.setPositiveButton(android.R.string.ok) { dialog, button ->
            openLogoutUrl()
        }
        dialogBuilder.setNegativeButton(android.R.string.cancel) { dialog, which ->

        }
        dialogBuilder.show()
    }

    private fun appAvailable(packageName: String): Boolean {
        val pm: PackageManager = packageManager
        val installed: Boolean
        installed = try {
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        return installed
    }

    private fun openLogoutUrl() {


        if (appAvailable("com.quoine.liquid")) { // check liquid app installed or not
            callRevokeAccessTokenAPI()
        } else {
            isClickLogoutButton = true
            val url = LiquidConstants.LOGOUT_URL

            val builder = CustomTabsIntent.Builder()
            val toolbarColor = ContextCompat.getColor(this, R.color.colorPrimary)
            val colorSchemeParams = CustomTabColorSchemeParams.Builder()
                    .setToolbarColor(toolbarColor)
                    .build()
            val customTabsIntent = builder.setShowTitle(true)
                    .setDefaultColorSchemeParams(colorSchemeParams)
                    .build()

            val uri = Uri.parse(url)

            CustomTabActivityHelper.openCustomTab(this, customTabsIntent, uri
            ) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = uri
                startActivity(intent)
            }
        }

    }

    override fun onResume() {
        super.onResume()
        if (isClickLogoutButton) {
            isClickLogoutButton = false
            callRevokeAccessTokenAPI()
        }
    }

    /**
     * Call api of logut from liquid
     */
    private fun callRevokeAccessTokenAPI() {
        loadingDialog!!.show()
        LiquidClient.getInstance()?.revokeAccessToken(object : LiquidClient.Callback<String?> {
            override fun onSuccess(data: String?) {
                loadingDialog!!.hide()
                LiquidClient.getInstance()?.clearStoredSessionData()
                finish()
            }

            override fun onError(e: Exception?) {
                loadingDialog!!.hide()
            }
        })
    }


    private fun getAllCurrencyList(isShowBuyDashDialog: Boolean) {

        if (GenericUtils.isInternetConnected(this)) {

            loadingDialog!!.show()
            liquidClient?.getAllCurrencies(object : LiquidClient.Callback<CurrencyResponse> {

                override fun onSuccess(data: CurrencyResponse) {
                    if (isFinishing) {
                        return
                    }
                    loadingDialog!!.hide()
                    cryptoCurrencyArrayList.clear()
                    fiatCurrencyList.clear()


                    for (i in data.payload.indices) {
                        if (data.payload[i].type == "CRYPTO") {
                            if (data.payload[i].ccyCode != "DASH") {
                                cryptoCurrencyArrayList.add(data.payload[i])
                            }
                        } else if (data.payload[i].type == "FIAT") {
                            fiatCurrencyList.add(data.payload[i])
                        }
                    }


                    if (isShowBuyDashDialog) {
                        showCurrencyDialog()
                    } else {
                        showSellDashCurrencyDialog()
                    }
                }

                override fun onError(e: Exception?) {
                    if (isFinishing) {
                        return
                    }
                    loadingDialog!!.hide()
                }
            })
        } else {
            GenericUtils.showToast(this, getString(R.string.internet_connected))
        }
    }

    private fun showCurrencyDialog() {
        BuyDashCryptoCurrencyDialog(this, cryptoCurrencyArrayList, object : ValueSelectListener {
            override fun onItemSelected(value: Int) {
                val intent = Intent(context, BuyDashWithCryptoCurrencyActivity::class.java)
                intent.putExtra("CurrencySelected", cryptoCurrencyArrayList[value].ccyCode)
                startActivityForResult(intent, Constants.USER_BUY_SELL_DASH)
            }
        })
    }

    override fun onDestroy() {
        loadingDialog?.dismiss()
        super.onDestroy()
    }
}