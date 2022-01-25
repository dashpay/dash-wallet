/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.dialog.CurrencyDialog
import de.schildbach.wallet.rates.ExchangeRatesViewModel
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_buy_and_sell_liquid_uphold.*
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.Constants.RESULT_CODE_GO_HOME
import org.dash.wallet.common.Constants.USER_BUY_SELL_DASH
import org.dash.wallet.common.data.Status
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.FancyAlertDialogViewModel
import org.dash.wallet.common.ui.NetworkUnavailableFragment
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.integration.liquid.currency.CurrencyResponse
import org.dash.wallet.integration.liquid.currency.PayloadItem
import org.dash.wallet.integration.liquid.data.LiquidClient
import org.dash.wallet.integration.liquid.data.LiquidConstants
import org.dash.wallet.integration.liquid.data.LiquidUnauthorizedException
import org.dash.wallet.integration.liquid.listener.CurrencySelectListener
import org.dash.wallet.integration.liquid.ui.LiquidBuyAndSellDashActivity
import org.dash.wallet.integration.liquid.ui.LiquidSplashActivity
import org.dash.wallet.integration.liquid.ui.LiquidViewModel
import org.dash.wallet.integration.uphold.currencyModel.UpholdCurrencyResponse
import org.dash.wallet.integration.uphold.data.UpholdClient
import org.dash.wallet.integration.uphold.data.UpholdConstants
import org.dash.wallet.integration.uphold.ui.UpholdAccountActivity
import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class BuyAndSellLiquidUpholdActivity : LockScreenActivity() {

    private var liquidClient: LiquidClient? = null
    private var loadingDialog: ProgressDialog? = null
    private var bottomSheetDialog: BottomSheetDialog? = null
    private lateinit var application: WalletApplication
    private lateinit var config: Configuration
    private var upholdCurrencyArrayList = ArrayList<UpholdCurrencyResponse>()
    private val liquidCurrencyArrayList = ArrayList<PayloadItem>()
    private var currentExchangeRate: org.dash.wallet.common.data.ExchangeRate? = null

    private var selectedFilterCurrencyItems: PayloadItem? = null

    private var rangeString = "items=0-50"
    private var totalUpholdRange = ""
    private lateinit var viewModel: BuyAndSellViewModel
    private lateinit var liquidViewModel: LiquidViewModel
    private var isNetworkOnline: Boolean = true
    private val analytics = FirebaseAnalyticsServiceImpl.getInstance()

    companion object {
        val log: Logger = LoggerFactory.getLogger(BuyAndSellLiquidUpholdActivity::class.java)
        fun createIntent(context: Context?): Intent {
            return Intent(context, BuyAndSellLiquidUpholdActivity::class.java)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log.info("starting Buy and Sell Dash activity")
        setContentView(R.layout.activity_buy_and_sell_liquid_uphold)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)


        application = WalletApplication.getInstance()
        config = application.configuration

        liquidClient = LiquidClient.getInstance()

        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        setTitle(R.string.menu_buy_and_sell_title)

        loadingDialog = ProgressDialog(this)
        loadingDialog!!.isIndeterminate = true
        loadingDialog!!.setCancelable(false)
        loadingDialog!!.setMessage(getString(org.dash.wallet.integration.liquid.R.string.loading))

        //do we need this here?
        setLoginStatus(isNetworkOnline)

        initViewModel()
        updateBalances()

        liquid_container.setOnClickListener {
            analytics.logEvent(if (LiquidClient.getInstance()?.isAuthenticated == true) {
                AnalyticsConstants.Liquid.ENTER_CONNECTED
            } else {
                AnalyticsConstants.Liquid.ENTER_DISCONNECTED
            }, bundleOf())

            startActivityForResult(
                LiquidBuyAndSellDashActivity.createIntent(this),
                USER_BUY_SELL_DASH
            )
        }

        uphold_container.setOnClickListener {
            analytics.logEvent(if (UpholdClient.getInstance().isAuthenticated) {
                AnalyticsConstants.Uphold.ENTER_CONNECTED
            } else {
                AnalyticsConstants.Uphold.ENTER_DISCONNECTED
            }, bundleOf())

            startActivity(UpholdAccountActivity.createIntent(this))
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.network_status_container, NetworkUnavailableFragment.newInstance())
            .commitNow()
        network_status_container.isVisible = false

        // check for missing keys from service.properties
        if (!LiquidConstants.hasValidCredentials() || !UpholdConstants.hasValidCredentials()) {
            keys_missing_error.isVisible = true
            liquid_container.isEnabled = false
            uphold_container.isEnabled = false
        }
    }

    fun initViewModel() {
        viewModel = ViewModelProvider(this)[BuyAndSellViewModel::class.java]
        liquidViewModel = ViewModelProvider(this)[LiquidViewModel::class.java]

        liquidViewModel.connectivityLiveData.observe(this) { isConnected ->
            if (isConnected != null) {
                setNetworkState(isConnected)
            }
        }

        viewModel.upholdBalanceLiveData.observe(this) {
            if (it != null) {
                when (it.status) {
                    Status.LOADING -> {
                        //TODO: start progress bar
                    }
                    Status.SUCCESS -> {
                        if (!isFinishing) {
                            //TODO: hide progress bar
                            val balance = it.data.toString()
                            config.lastUpholdBalance = balance
                            showUpholdBalance(balance)
                        }
                    }
                    Status.ERROR -> {
                        if (!isFinishing) {
                            //TODO: error progress bar

                            //TODO: if the exception is UnknownHostException and isNetworkOnline is true
                            // then there is a problem contacting the server and we don't have
                            // error handling for it
                            showUpholdBalance(config.lastUpholdBalance)
                        }
                    }
                    Status.CANCELED -> {
                        //TODO: stop progress bar
                        showUpholdBalance(config.lastUpholdBalance)
                    }
                }
            }
        }

        liquidViewModel.liquidBalanceLiveData.observe(this) {
            if (it != null) {
                when (it.status) {
                    Status.LOADING -> {
                        //TODO: start progress bar
                    }
                    Status.SUCCESS -> {
                        if (!isFinishing) {
                            //TODO: finish progress bar
                            showDashLiquidBalance(it.data!!)
                        }
                    }
                    Status.ERROR -> {
                        if (!isFinishing) {
                            //TODO: error progress bar
                            if (it.exception is LiquidUnauthorizedException) {
                                // do we need this
                                setLoginStatus(isNetworkOnline)
                                val viewModel =
                                    ViewModelProvider(this@BuyAndSellLiquidUpholdActivity)[FancyAlertDialogViewModel::class.java]
                                viewModel.onPositiveButtonClick.observe(
                                    this@BuyAndSellLiquidUpholdActivity,
                                    Observer {
                                        startActivity(LiquidSplashActivity.createIntent(this@BuyAndSellLiquidUpholdActivity))
                                    })
                                FancyAlertDialog.newInstance(
                                    org.dash.wallet.integration.liquid.R.string.liquid_logout_title,
                                    org.dash.wallet.integration.liquid.R.string.liquid_forced_logout,
                                    org.dash.wallet.integration.liquid.R.drawable.ic_liquid_icon,
                                    android.R.string.ok,
                                    0
                                ).show(supportFragmentManager, "auto-logout-dialog")
                            }
                            //TODO: if the exception is UnknownHostException and isNetworkOnline is true
                            // then there is a problem contacting the server and we don't have
                            // error handling for it
                            showLiquidBalance(liquidViewModel.lastLiquidBalance)
                        }
                    }
                    Status.CANCELED -> {
                        //TODO: stop progress bar
                        showLiquidBalance(liquidViewModel.lastLiquidBalance)
                    }
                }
            }
        }

        // for getting currency exchange rates
        val exchangeRatesViewModel = ViewModelProvider(this)[ExchangeRatesViewModel::class.java]
        exchangeRatesViewModel.getRate(config.exchangeCurrencyCode).observe(this,
            { exchangeRate ->
                if (exchangeRate != null) {
                    currentExchangeRate = exchangeRate
                    showLiquidBalance(liquidViewModel.lastLiquidBalance)
                    showUpholdBalance(config.lastUpholdBalance)
                }
            })
    }

    fun setNetworkState(online: Boolean) {
        network_status_container.isVisible = !online
        liquid_container.isEnabled = online && LiquidConstants.hasValidCredentials()
        uphold_container.isEnabled = online && UpholdConstants.hasValidCredentials()
        setLoginStatus(online)
        if (!isNetworkOnline && online) {
            updateBalances()
        }
        isNetworkOnline = online
    }

    private fun updateBalances() {
        if (LiquidClient.getInstance()!!.isAuthenticated) {
            liquidViewModel.updateLiquidBalance()
        }
        if (UpholdClient.getInstance().isAuthenticated) {
            viewModel.updateUpholdBalance()
        }
    }

    override fun onResume() {
        super.onResume()
        setLoginStatus(isNetworkOnline)
        updateBalances()
    }

    private fun setLoginStatus(online: Boolean) {

        val connectedStringId = if (online) R.string.connected else R.string.disconnected
        val connectedDrawable =
            resources.getDrawable(if (online) R.drawable.drawable_green_round else R.drawable.drawable_orange_round)
        if (LiquidClient.getInstance()!!.isAuthenticated) {
            liquid_connect.visibility = View.GONE
            liquid_connected.visibility = View.VISIBLE
            liquid_connected.text = getString(connectedStringId)
            liquid_connected.setCompoundDrawablesWithIntrinsicBounds(
                connectedDrawable,
                null,
                null,
                null
            )
            liquid_balance_container.visibility = View.VISIBLE
            liquid_balance_inaccurate.isVisible = !online
        } else {
            liquid_connect.visibility = View.VISIBLE//if (online) View.VISIBLE else View.GONE
            liquid_connected.visibility = View.GONE
            liquid_balance_container.visibility = View.GONE
            liquid_balance_inaccurate.isVisible = false
        }

        if (UpholdClient.getInstance().isAuthenticated) {
            uphold_connect.visibility = View.GONE
            uphold_connected.visibility = View.VISIBLE
            uphold_connected.text = getString(connectedStringId)
            uphold_connected.setCompoundDrawablesWithIntrinsicBounds(
                connectedDrawable,
                null,
                null,
                null
            )
            uphold_balance_container.visibility = View.VISIBLE
            uphold_balance_inaccurate.isVisible = !online
        } else {
            uphold_connect.visibility = View.VISIBLE
            uphold_connected.visibility = View.GONE
            uphold_balance_container.visibility = View.GONE
            uphold_balance_inaccurate.isVisible = false
        }

    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.wallet_buy_and_sell, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
            R.id.buy_and_sell_dash_filter -> {
                rangeString = "items=0-50"
                getLiquidCurrencyList()
            }
        }
        return super.onOptionsItemSelected(item)
    }

    //TODO: Can these next two functions be refactored into the liquid module?
    private fun showDashLiquidBalance(data: String) {

        try {
            val jsonObject = JSONObject(data)
            val cryptoArray = jsonObject.getJSONObject("payload").getJSONArray("crypto_accounts")
            var amount = "0.00"

            for (i in 0 until cryptoArray.length()) {
                val currency = cryptoArray.getJSONObject(i).getString("currency")
                if (currency == "DASH") {
                    liquid_balance_container.visibility = View.VISIBLE
                    amount = cryptoArray.getJSONObject(i).getString("balance")
                    liquidViewModel.lastLiquidBalance = amount
                }
            }

            showLiquidBalance(amount)
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    private fun showLiquidBalance(amount: String) {
        liquid_balance.text = amount
        val dashAmount = try {
            Coin.parseCoin(amount)
        } catch (x: Exception) {
            Coin.ZERO
        }

        if (currentExchangeRate != null) {
            val exchangeRate = ExchangeRate(Coin.COIN, currentExchangeRate?.fiat)
            val localValue = exchangeRate.coinToFiat(dashAmount)
            val fiatFormat = Constants.LOCAL_FORMAT

            liquid_fiat_amount.text = getString(
                R.string.fiat_balance_with_currency, config.exchangeCurrencyCode,
                if (dashAmount.isZero) "0.00" else fiatFormat.format(localValue)
            )
        }
    }

    // TODO: can this be refactored into the uphold module>?
    private fun showUpholdBalance(balance: String) {
        val monetaryFormat = MonetaryFormat().noCode().minDecimals(8)
        uphold_balance.setFormat(monetaryFormat)
        uphold_balance.setApplyMarkup(false)
        val amount = Coin.parseCoin(balance)
        uphold_balance.setAmount(amount)

        if (currentExchangeRate != null) {
            val exchangeRate = ExchangeRate(Coin.COIN, currentExchangeRate?.fiat)
            val localValue = exchangeRate.coinToFiat(amount)
            val fiatFormat = Constants.LOCAL_FORMAT

            uphold_fiat_amount.text = getString(
                R.string.fiat_balance_with_currency, config.exchangeCurrencyCode,
                if (amount.isZero) "0.00" else fiatFormat.format(localValue)
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == USER_BUY_SELL_DASH && resultCode == RESULT_CODE_GO_HOME) {
            log.info("liquid: activity result for user buy sell dash was RESULT_CODE_GO_HOME")
            if (LiquidClient.getInstance()!!.isAuthenticated) {
                liquidViewModel.updateLiquidBalance()
            }
            setResult(RESULT_CODE_GO_HOME)
            finish()
        }
    }

    /**
     * For getting liquid supported currency list
     */
    private fun getLiquidCurrencyList() {

        if (GenericUtils.isInternetConnected(this)) {

            loadingDialog!!.show()

            liquidClient?.getAllCurrencies(object : LiquidClient.Callback<CurrencyResponse> {
                override fun onSuccess(data: CurrencyResponse) {
                    if (isFinishing) {
                        return
                    }
                    liquidCurrencyArrayList.clear()
                    liquidCurrencyArrayList.addAll(data.payload)
                    checkAndCallApiOfUpload()
                }

                override fun onError(e: Exception?) {
                    if (isFinishing) {
                        return
                    }
                    checkAndCallApiOfUpload()
                }

            })

        } else {
            log.error("liquid: There is no internet connection")
        }
    }

    /**
     * For getting uphold supported currency list
     */
    private fun getUpholdCurrencyList() {


        if (GenericUtils.isInternetConnected(this)) {
            loadingDialog!!.show()
            UpholdClient.getInstance()
                .getUpholdCurrency(rangeString, object : UpholdClient.CallbackFilter<String> {
                    override fun onSuccess(data: String?, range: String) {


                        if (rangeString == "items=0-50") {
                            upholdCurrencyArrayList.clear()
                            totalUpholdRange = range
                            rangeString = ""
                        }

                        val turnsType = object : TypeToken<List<UpholdCurrencyResponse>>() {}.type
                        val turns = Gson().fromJson<List<UpholdCurrencyResponse>>(data, turnsType)
                        upholdCurrencyArrayList.addAll(turns)
                        loadingDialog!!.hide()
                        checkAndCallApiOfUpload()
                        //showCurrenciesDialog()
                    }

                    override fun onError(e: java.lang.Exception?, otpRequired: Boolean) {
                        loadingDialog!!.hide()
                        showCurrenciesDialog()
                    }
                }
                )
        } else {
            log.error("liquid: There is no internet connection")
        }
    }

    /**
     * Calling uphold pagination in all data once
     */
    private fun checkAndCallApiOfUpload() {
        if (rangeString == "items=0-50") {
            getUpholdCurrencyList()
        } else {
            if (upholdCurrencyArrayList.size % 50 == 0) {
                if (totalUpholdRange.isNotEmpty() and (totalUpholdRange.contains("/"))) {
                    val array = totalUpholdRange.split("/")
                    if (array.size == 2) {
                        val totalRange = array[1]
                        val size = upholdCurrencyArrayList.size + 1
                        rangeString = "items=${size.toString()}-$totalRange"
                        getUpholdCurrencyList()
                    }
                } else {
                    getUpholdCurrencyList()
                }
            } else {
                showCurrenciesDialog()
            }
        }
    }


    /**
     * Show dialog of currency list
     */

    private fun showCurrenciesDialog() {
        bottomSheetDialog = CurrencyDialog(
            this,
            liquidCurrencyArrayList,
            upholdCurrencyArrayList,
            selectedFilterCurrencyItems,
            object : CurrencySelectListener {
                override fun onCurrencySelected(
                    isLiquidSelcted: Boolean,
                    isUpholdSelected: Boolean,
                    selectedFilterCurrencyItem: PayloadItem?
                ) {
                    liquid_container.visibility = if (isLiquidSelcted) View.VISIBLE else View.GONE
                    uphold_container.visibility = if (isUpholdSelected) View.VISIBLE else View.GONE
                    selectedFilterCurrencyItems = selectedFilterCurrencyItem
                    setSelectedCurrency()
                }
            })
        bottomSheetDialog?.show()
    }

    override fun onLockScreenActivated() {
        super.onLockScreenActivated()
        // TODO: replace with BottomSheetDialogFragment to dismiss automatically
        bottomSheetDialog?.dismiss()
    }

    /**
     * Show selected currency
     */
    private fun setSelectedCurrency() {

        if (selectedFilterCurrencyItems != null) {
            llFilterSelected.visibility = View.VISIBLE
            txtSelectedFilterCurrency.text =
                selectedFilterCurrencyItems?.label + " (" + selectedFilterCurrencyItems?.symbol + ")"
        } else {
            llFilterSelected.visibility = View.GONE
        }

        imgRemoveCurrency.setOnClickListener {
            selectedFilterCurrencyItems = null
            llFilterSelected.visibility = View.GONE
            liquid_container.visibility = View.VISIBLE
            uphold_container.visibility = View.VISIBLE
        }
    }
}
