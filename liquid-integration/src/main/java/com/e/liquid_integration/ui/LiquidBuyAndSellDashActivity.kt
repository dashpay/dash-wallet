package com.e.liquid_integration.ui

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
import androidx.lifecycle.Observer
import com.e.liquid_integration.R
import com.e.liquid_integration.`interface`.ValueSelectListner
import com.e.liquid_integration.currency.CurrencyResponse
import com.e.liquid_integration.currency.PayloadItem
import com.e.liquid_integration.data.LiquidClient
import com.e.liquid_integration.data.LiquidConstants
import com.e.liquid_integration.dialog.BuyDashCryptoCurrencyDialog
import com.e.liquid_integration.dialog.SelectBuyDashDialog
import com.e.liquid_integration.dialog.SelectSellDashDialog
import com.e.liquid_integration.dialog.SellDashCryptoCurrencyDialog
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Constants
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.customtabs.CustomTabActivityHelper
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.util.GenericUtils
import org.json.JSONObject


class LiquidBuyAndSellDashActivity : InteractionAwareActivity() {

    companion object {
        fun createIntent(context: Context?): Intent? {
            return if (LiquidClient.getInstance()!!.isAuthenticated) {
                Intent(context, LiquidBuyAndSellDashActivity::class.java)
            } else {
                Intent(context, LiquidSplashActivity::class.java)
            }
        }
    }

    private var liquidClient: LiquidClient? = null

    private lateinit var _context: Context
    private var loadingDialog: ProgressDialog? = null

    private val cryptoCurrencyArrayList = ArrayList<PayloadItem>()
    private val fiatCurrencyList = ArrayList<PayloadItem>()
    private var isSelectFiatCurrency = false
    private var isClickLogoutButton = false

    // var currentExchangeRate: LiveData<ExchangeRate>? = null
    var currentExchangeRate: ExchangeRate? = null

    override
    fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_liquid_buy_and_sell_dash)
        this._context = this@LiquidBuyAndSellDashActivity
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

            /* val intent = Intent()
             intent.setClassName(this, "de.schildbach.wallet.ui.LiquidDashToDashTransferActivity")
             intent.putExtra("extra_title", getString(R.string.liquid))
             intent.putExtra("extra_message", "Enter the amount to transfer")
             intent.putExtra("extra_max_amount", "17")
             startActivityForResult(intent, 101)*/
            val walletDataProvider = application as WalletDataProvider
            val address = Address.fromBase58(TestNet3Params.get(), "yTgh4Z1RrMXbJrbkbS7Lgk8NEZERJigMsy")
            val amount = Coin.CENT
            walletDataProvider.startSendCoinsForResult(this, 1234, address, amount)
        }


        if (LiquidClient.getInstance()!!.isAuthenticated) {
            getUserLiquidAccountBalance()
        }


        val walletDataProvider = application as WalletDataProvider
        val defaultCurrency = walletDataProvider.defaultCurrencyCode()

        walletDataProvider.getExchangeRate(defaultCurrency).observe(this,
                Observer { exchangeRate ->
                    if (exchangeRate != null) {
                        currentExchangeRate = exchangeRate
                    }
                })
    }

    private fun showSellDashDialog() {

        SelectSellDashDialog(_context, object : ValueSelectListner {
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
        })//.show()
    }

    private fun showSellDashCurrencyDialog() {
        if (isSelectFiatCurrency) {

            SellDashCryptoCurrencyDialog(_context, "FiatCurrency", fiatCurrencyList, object : ValueSelectListner {
                override fun onItemSelected(value: Int) {

                }

            })//.show()

        } else {

            SellDashCryptoCurrencyDialog(_context, "CryptoCurrency", cryptoCurrencyArrayList, object : ValueSelectListner {
                override fun onItemSelected(value: Int) {

                }
            })//.show()
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

    private fun buyDash() {

        SelectBuyDashDialog(_context, object : ValueSelectListner {
            override fun onItemSelected(value: Int) {
                if (value == 1) {

                    val intent = Intent(_context, BuyDashWithCreditCardActivity::class.java)
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
            GenericUtils.showToast(this, getString(com.e.liquid_integration.R.string.internet_connected))
        }
    }

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


            /*val base = Gson().fromJson(data, DashBalanceResponse::class.java)

            if (base.success!!) {

                for (i in base.payload?.cryptoAccounts!!.indices) {

                    if (base.payload.cryptoAccounts[i]!!.currency == "DASH") {
                        llLiquidAmount.visibility = View.VISIBLE
                        txtUSAmount.text = base.payload.cryptoAccounts[i]!!.balance
                        amount = base.payload.cryptoAccounts[i]!!.balance
                        break
                    }
                }
            }*/
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

    private fun revokeAccessToken() {

        val dialogBuilder = AlertDialog.Builder(this)
        dialogBuilder.setMessage(R.string.liquid_logout_title)
        dialogBuilder.setPositiveButton(android.R.string.ok) { dialog, button ->
            openLogoutUrl()
            /*loadingDialog!!.show()
            LiquidClient.getInstance()?.revokeAccessToken(object : LiquidClient.Callback<String?> {
                override fun onSuccess(data: String?) {
                    loadingDialog!!.hide()
                    LiquidClient.getInstance()?.clearStoredSessionData()
                    finish()
                }

                override fun onError(e: Exception?) {
                    loadingDialog!!.hide()
                }
            })*/
        }
        dialogBuilder.setNegativeButton(android.R.string.cancel) { dialog, which ->

        }
        dialogBuilder.show()
    }

    private fun appAvailable(package_name: String): Boolean {
        val pm: PackageManager = packageManager
        val installed: Boolean
        installed = try {
            pm.getPackageInfo(package_name, PackageManager.GET_ACTIVITIES)
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
            liquidClient?.getAllCurrencies(object : LiquidClient.CallbackCurrency<String> {

                override fun onSuccess(data: CurrencyResponse) {
                    if (isFinishing) {
                        return
                    }
                    loadingDialog!!.hide()
                    cryptoCurrencyArrayList.clear()
                    fiatCurrencyList.clear()


                    for (i in data.payload.indices) {
                        if (data.payload[i].type == "CRYPTO") {
                            cryptoCurrencyArrayList.add(data.payload[i])
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
        BuyDashCryptoCurrencyDialog(this, cryptoCurrencyArrayList, object : ValueSelectListner {
            override fun onItemSelected(value: Int) {
                val intent = Intent(_context, BuyDashWithCryptoCurrencyActivity::class.java)
                intent.putExtra("CurrencySelected", cryptoCurrencyArrayList[value].symbol)
                startActivityForResult(intent, Constants.USER_BUY_SELL_DASH)

            }
        })//.show()
    }

    override fun onDestroy() {
        loadingDialog?.dismiss()
        super.onDestroy()
    }
}