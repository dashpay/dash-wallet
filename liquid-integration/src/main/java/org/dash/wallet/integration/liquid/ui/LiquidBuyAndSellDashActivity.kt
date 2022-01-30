package org.dash.wallet.integration.liquid.ui

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import dagger.hilt.android.AndroidEntryPoint
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
import org.dash.wallet.common.data.ExchangeRateData
import org.dash.wallet.common.data.Status
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.NetworkUnavailableFragment
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.liquid.R
import org.dash.wallet.integration.liquid.data.LiquidUnauthorizedException
import org.dash.wallet.integration.liquid.databinding.ActivityLiquidBuyAndSellDashBinding
import org.dash.wallet.integration.liquid.dialog.CountrySupportDialog
import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
@AndroidEntryPoint
class LiquidBuyAndSellDashActivity : InteractionAwareActivity(), FancyAlertDialog.FancyAlertButtonsClickListener {

    companion object {
        val log: Logger = LoggerFactory.getLogger(LiquidBuyAndSellDashActivity::class.java)

        fun createIntent(context: Context?): Intent {
            return if (LiquidClient.getInstance()!!.isAuthenticated) {
                Intent(context, LiquidBuyAndSellDashActivity::class.java)
            } else {
                Intent(context, LiquidSplashActivity::class.java)
            }
        }
    }

    private var returnHome: Boolean = false
    private var liquidClient: LiquidClient? = null
    private val viewModel by viewModels<LiquidViewModel>()
    private lateinit var viewBinding: ActivityLiquidBuyAndSellDashBinding
    private val analytics = FirebaseAnalyticsServiceImpl.getInstance()
    private var countrySupportDialog: CountrySupportDialog? = null
    private lateinit var context: Context
    private var loadingDialog: ProgressDialog? = null

    private val cryptoCurrencyArrayList = ArrayList<PayloadItem>()
    private val fiatCurrencyList = ArrayList<PayloadItem>()
    private var isSelectFiatCurrency = false
    private var isClickLogoutButton = false

    var currentExchangeRate: ExchangeRateData? = null

    override
    fun onCreate(savedInstanceState: Bundle?) {
        log.info("liquid: starting buy/sell dash activity")
        super.onCreate(savedInstanceState)
        viewBinding = ActivityLiquidBuyAndSellDashBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)
        countrySupportDialog = CountrySupportDialog(this, true)
        this.context = this@LiquidBuyAndSellDashActivity
        liquidClient = LiquidClient.getInstance()

        
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }
        title = getString(R.string.buy_sell_dash_)

        loadingDialog = ProgressDialog(this)
        loadingDialog!!.isIndeterminate = true
        loadingDialog!!.setCancelable(false)
        loadingDialog!!.setMessage(getString(R.string.loading))

        viewBinding.apply {
            disconnectButton.setOnClickListener { openLogOutUrl() }
            buyDash.setOnClickListener { buyDash() }

            sellDash.setOnClickListener {
                showSellDashDialog()
            }

            //don't show the (i) icon
            ivInfo.apply {
                isVisible = false
                setOnClickListener {
                    countrySupportDialog?.show()
                }
            }

            llTransferToLiquid.setOnClickListener {

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

        }

        initViewModel()

        supportFragmentManager.beginTransaction().replace(
            R.id.network_status_container,
            NetworkUnavailableFragment.newInstance()
        ).commitNow()
        viewBinding.networkStatusContainer.isVisible = false

        if (LiquidClient.getInstance()!!.isAuthenticated) {
            viewModel.updateLiquidBalance()
        }
    }

    fun initViewModel() {
        viewModel.connectivityLiveData.observe(this) { isConnected ->
            if (isConnected != null) {
                setConnectivityState(isConnected)
            }
        }
        viewModel.liquidBalanceLiveData.observe(this) {
            if (it != null) {
                when (it.status) {
                    Status.LOADING -> {
                        loadingDialog!!.show()
                        viewModel.lastLiquidBalance?.let { it1 -> showLiquidBalance(it1) }
                    }
                    Status.SUCCESS -> {
                        log.info("liquid: get user balance successful")
                        if (!isFinishing) {
                            loadingDialog!!.hide()
                            showDashLiquidBalance(it.data!!)
                        }
                    }
                    Status.ERROR -> {
                        log.error("liquid: cannot obtain user balance: ${it.exception?.message}")
                        if (!isFinishing) {
                            loadingDialog!!.hide()
                            viewModel.lastLiquidBalance?.let { it1 -> showLiquidBalance(it1) }
                        }
                        if (it.exception is LiquidUnauthorizedException) {
                            FancyAlertDialog.newInstance(
                                R.string.liquid_logout_title, R.string.liquid_forced_logout,
                                R.drawable.ic_liquid_icon, android.R.string.ok, 0
                            ).show(supportFragmentManager, "auto-logout-dialog")
                        }
                    }
                    Status.CANCELED -> {
                        if (!isFinishing) {
                            loadingDialog!!.hide()
                            viewModel.lastLiquidBalance?.let { it1 -> showLiquidBalance(it1) }
                        }
                    }
                }
            }
        }


        // Exchange Rates
        val walletDataProvider = application as WalletDataProvider
        val defaultCurrency = walletDataProvider.defaultCurrencyCode()
        walletDataProvider.getExchangeRate(defaultCurrency).observe(this,
            { exchangeRate ->
                if (exchangeRate != null) {
                    currentExchangeRate = exchangeRate
                }
            })
    }

    fun setConnectivityState(isConnected: Boolean) {
        viewBinding.apply {
            liquidContainer.isEnabled = false // disable ripple
            networkStatusContainer.isVisible = !isConnected
            liquidButtonsRow1.isVisible = isConnected
            liquidButtonsRow2.isVisible = isConnected
            disconnectButton.isVisible = isConnected
            liquidBalanceInaccurate.root.isVisible = !isConnected
        }
        setLoginStatus(isConnected)
    }

    private fun setLoginStatus(isConnected: Boolean) {

        val connectedStringId = if (isConnected) R.string.connected else R.string.disconnected
        val connectedDrawable = resources.getDrawable(if (isConnected) R.drawable.drawable_green_round else R.drawable.drawable_orange_round)

        viewBinding.apply {
            liquidConnected.text = getString(connectedStringId)
            liquidConnected.setCompoundDrawablesWithIntrinsicBounds(
                connectedDrawable,
                null,
                null,
                null
            )
        }
    }

    /**
     * SHow dialog of sell dash option fiat or cryptocurrency
     */

    private fun showSellDashDialog() {
        log.info("liquid: show buy dash dialog")
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
        }).show()
    }

    /**
     * Show dialog fiat or cryptocurrency selected currency
     */
    private fun showSellDashCurrencyDialog() {
        log.info("liquid: starting sell dash currency dialog")
        if (isSelectFiatCurrency) {
            SellDashCryptoCurrencyDialog(this, "FiatCurrency", fiatCurrencyList, object : ValueSelectListener {
                override fun onItemSelected(value: Int) {
                    super@LiquidBuyAndSellDashActivity.turnOffAutoLogout()
                    val intent = Intent(this@LiquidBuyAndSellDashActivity, SellDashActivity::class.java)
                    intent.putExtra("CurrencySelected", fiatCurrencyList[value].ccyCode)
                    intent.putExtra("CurrencyType", "FIAT")
                    startActivity(intent)
                }
            }).show()
        } else {
            SellDashCryptoCurrencyDialog(this,"CryptoCurrency", cryptoCurrencyArrayList, object : ValueSelectListener {
                override fun onItemSelected(value: Int) {
                    super@LiquidBuyAndSellDashActivity.turnOffAutoLogout()
                    val intent = Intent(this@LiquidBuyAndSellDashActivity, SellDashActivity::class.java)
                    intent.putExtra("CurrencySelected", cryptoCurrencyArrayList[value].ccyCode)
                    intent.putExtra("CurrencyType", "CRYPTO")
                    startActivity(intent)
                }
            }).show()
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
        analytics.logEvent(AnalyticsConstants.Liquid.DISCONNECT, bundleOf())
        //revoke access to the token
        revokeAccessToken()
    }

    /**
     * Show dialog for buy dash option to select credit card and cryptocurrency
     */
    private fun buyDash() {
        log.info("liquid: buy dash")
        analytics.logEvent(AnalyticsConstants.Liquid.BUY_DASH, bundleOf())

        SelectBuyDashDialog(context, object : ValueSelectListener {
            override fun onItemSelected(value: Int) {
                if (value == 1) {
                    super@LiquidBuyAndSellDashActivity.turnOffAutoLogout()
                    analytics.logEvent(AnalyticsConstants.Liquid.BUY_CREDIT_CARD, bundleOf())

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
        }).show()
    }

    /**
     * call api to  get address
     */
    private fun getUserLiquidAccountAddress() {
        log.info("liquid: attempting to get liquid account address")
        if (GenericUtils.isInternetConnected(this)) {
            loadingDialog!!.show()
            liquidClient?.getUserAccountAddress(liquidClient?.storedSessionId!!, true, object : LiquidClient.Callback<String> {
                override fun onSuccess(data: String) {
                    log.info("liquid: get user address successful")
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
                    log.error("liquid: cannot obtain user address: ${e?.message}")
                    if (isFinishing) {
                        return
                    }
                    loadingDialog!!.hide()
                }
            })
        } else {
            GenericUtils.showToast(this, getString(R.string.internet_connected))
            log.error("liquid: cannot connect to internet")
        }
    }

    /**
     * Show iiquid wallet balance
     */
    private fun showDashLiquidBalance(data: String) {

        try {
            val jsonObject = JSONObject(data)
            val cryptoArray = jsonObject.getJSONObject("payload").getJSONArray("crypto_accounts")
            var amount = "0.00"

            for (i in 0 until cryptoArray.length()) {
                val currency = cryptoArray.getJSONObject(i).getString("currency")
                if (currency == "DASH") {
                    amount = cryptoArray.getJSONObject(i).getString("balance")
                    viewModel.lastLiquidBalance = amount
                }
            }

            showLiquidBalance(amount)
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    private fun showLiquidBalance(amount: String) {
        viewBinding.liquidBalance.text = amount
        val dashAmount = try {
            Coin.parseCoin(amount)
        } catch (x: Exception) {
            Coin.ZERO
        }

        if (currentExchangeRate != null) {
            val exchangeRate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, currentExchangeRate?.fiat)
            val localValue = exchangeRate.coinToFiat(dashAmount)
            val fiatFormat = MonetaryFormat().noCode().minDecimals(2).optionalDecimals()

            viewBinding.liquidFiatAmount.text = getString(
                R.string.fiat_balance_with_currency, viewModel.defaultCurrency,
                fiatFormat.format(localValue)
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        log.info("liquid: onActivityResult($requestCode, $resultCode)")
        if (requestCode == Constants.USER_BUY_SELL_DASH && resultCode == Constants.RESULT_CODE_GO_HOME) {
            log.info("liquid: activity result for user buy sell dash was RESULT_CODE_GO_HOME")
            returnHome = true
            onBackPressed()
        } else if (requestCode == 1) {
            if (resultCode == Activity.RESULT_OK) {
                val txHash = data!!.getStringExtra("transaction_hash")
                log.info("liquid: received tx hash: $txHash")
            }
        }
    }

    override fun onBackPressed() {
        if (!returnHome) {
            setResult(Activity.RESULT_OK)
        } else {
            setResult(Constants.RESULT_CODE_GO_HOME)
        }
        super.onBackPressed()
    }

    /**
     * Show dialog for logout from liquid
     */
    private fun revokeAccessToken() {
        log.info("liquid: revoking access token")
        alertDialogBuilder.apply {
            message = getString(R.string.liquid_logout_title)
            positiveText = getString(android.R.string.ok)
            positiveAction = {
                openLogoutUrl()
            }
            negativeText = getString(android.R.string.cancel)
        }.buildAlertDialog().show()
    }

    private fun appAvailable(packageName: String): Boolean {
        val pm: PackageManager = packageManager
        return try {
            pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun openLogoutUrl() {
        log.info("liquid: open logout url")
        if (appAvailable("com.quoine.liquid")) { // check liquid app installed or not
            log.info("liquid: call logout using liquid app")
            callRevokeAccessTokenAPI()
        } else {
            isClickLogoutButton = true
            val url = LiquidConstants.LOGOUT_URL
            log.error("liquid: call logout through the url: $url")

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
                log.info("liquid: logout failure because custom tabs is not available")
                log.info("liquid: using the web browser instead for $uri")
                val intent = Intent(Intent.ACTION_VIEW)
                intent.data = uri
                startActivity(intent)
            }
        }
        super.turnOffAutoLogout()
    }

    override fun onResume() {
        super.onResume()
        super.turnOnAutoLogout()
        viewModel.updateLiquidBalance()
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
                log.info("liquid: revoke access token successful")
                loadingDialog!!.hide()
                LiquidClient.getInstance()?.clearStoredSessionData()
                setResult(Activity.RESULT_OK)
                finish()
            }

            override fun onError(e: Exception?) {
                log.error("liquid: cannot revoke access token: ${e?.message}")
                loadingDialog!!.hide()
                if (e is LiquidUnauthorizedException) {
                    // do nothing for now
                }
            }
        })
    }


    private fun getAllCurrencyList(isShowBuyDashDialog: Boolean) {
        log.error("liquid: attempting to get currency list")

        if (GenericUtils.isInternetConnected(this)) {

            loadingDialog!!.show()
            liquidClient?.getAllCurrencies(object : LiquidClient.Callback<CurrencyResponse> {

                override fun onSuccess(data: CurrencyResponse) {
                    log.info("liquid: get all currency list successful")
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
                    log.error("liquid: cannot obtain currency list: ${e?.message}")
                    if (isFinishing) {
                        return
                    }
                    loadingDialog!!.hide()
                }
            })
        } else {
            GenericUtils.showToast(this, getString(R.string.internet_connected))
            log.error("liquid: cannot connect to internet")
        }
    }

    private fun showCurrencyDialog() {
        BuyDashCryptoCurrencyDialog(this, cryptoCurrencyArrayList, object : ValueSelectListener {
            override fun onItemSelected(value: Int) {
                super@LiquidBuyAndSellDashActivity.turnOffAutoLogout()
                val intent = Intent(context, BuyDashWithCryptoCurrencyActivity::class.java)
                intent.putExtra("CurrencySelected", cryptoCurrencyArrayList[value].ccyCode)
                startActivityForResult(intent, Constants.USER_BUY_SELL_DASH)
            }
        })
    }

    override fun onDestroy() {
        log.info("liquid: closing buy/sell dash activity")
        loadingDialog?.dismiss()
        countrySupportDialog?.dismiss()
        super.onDestroy()
    }

    override fun onPositiveButtonClick() {
        startActivity(LiquidSplashActivity.createIntent(this@LiquidBuyAndSellDashActivity))
        finish()
    }

    override fun onNegativeButtonClick() {}
}