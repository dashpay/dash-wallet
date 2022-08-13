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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.adapter.BuyAndSellDashServicesAdapter
import de.schildbach.wallet.data.BuyAndSellDashServicesModel
import de.schildbach.wallet.ui.coinbase.CoinBaseWebClientActivity
import de.schildbach.wallet.ui.coinbase.CoinbaseActivity
import de.schildbach.wallet.ui.rates.ExchangeRatesViewModel
import de.schildbach.wallet_test.R
import org.dash.wallet.integration.coinbase_integration.R as R_coinbase
import de.schildbach.wallet_test.databinding.ActivityBuyAndSellIntegrationsBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.Constants.*
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.data.Status
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.NetworkUnavailableFragment
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.integration.liquid.data.LiquidClient
import org.dash.wallet.integration.liquid.data.LiquidConstants
import org.dash.wallet.integration.liquid.data.LiquidUnauthorizedException
import org.dash.wallet.integration.liquid.ui.LiquidBuyAndSellDashActivity
import org.dash.wallet.integration.liquid.ui.LiquidSplashActivity
import org.dash.wallet.integration.liquid.ui.LiquidViewModel
import org.dash.wallet.integration.uphold.api.UpholdClient
import org.dash.wallet.integration.uphold.data.UpholdConstants
import org.dash.wallet.integration.uphold.ui.UpholdAccountActivity
import org.json.JSONObject
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import javax.inject.Inject
import kotlin.concurrent.schedule

@ExperimentalCoroutinesApi
@AndroidEntryPoint
class BuyAndSellIntegrationsActivity : LockScreenActivity() {

    private var liquidClient: LiquidClient? = null
    private var loadingDialog: AdaptiveDialog? = null
    private var currentExchangeRate: ExchangeRate? = null

    @Inject
    lateinit var application: WalletApplication
    @Inject
    lateinit var config: Configuration
    @Inject
    lateinit var analytics: AnalyticsService

    private lateinit var binding: ActivityBuyAndSellIntegrationsBinding
    private val viewModel by viewModels<BuyAndSellViewModel>()
    private val liquidViewModel by viewModels<LiquidViewModel>()
    private val buyAndSellDashServicesAdapter: BuyAndSellDashServicesAdapter by lazy {
        BuyAndSellDashServicesAdapter(config) { model ->
            when (model.serviceType) {
                BuyAndSellDashServicesModel.ServiceType.LIQUID -> onLiquidItemClicked()
                BuyAndSellDashServicesModel.ServiceType.UPHOLD -> onUpHoldItemClicked()
                BuyAndSellDashServicesModel.ServiceType.COINBASE -> onCoinBaseItemClicked()
            }
        }
    }

    companion object {
        val log: Logger = LoggerFactory.getLogger(BuyAndSellIntegrationsActivity::class.java)
        fun createIntent(context: Context?): Intent {
            return Intent(context, BuyAndSellIntegrationsActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log.info("starting Buy and Sell Dash activity")
        binding = ActivityBuyAndSellIntegrationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        liquidClient = LiquidClient.getInstance()
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        title = ""
        loadingDialog = AdaptiveDialog.progress(getString(R.string.loading))
        initViewModel()

        supportFragmentManager.beginTransaction()
            .replace(R.id.network_status_container, NetworkUnavailableFragment.newInstance())
            .commitNow()
        binding.networkStatusContainer.isVisible = false

        // check for missing keys from service.properties
        if (!LiquidConstants.hasValidCredentials() || !UpholdConstants.hasValidCredentials()) {
            binding.keysMissingError.isVisible = true
        }
        binding.dashServicesList.itemAnimator = null
        binding.dashServicesList.adapter = buyAndSellDashServicesAdapter

        viewModel.showLoading.observe(this){ showDialog ->
            if (showDialog) loadingDialog?.show(this)
            else if (loadingDialog?.isAdded == true) loadingDialog?.dismiss()
        }
    }

    private fun onUpHoldItemClicked() {
        if (UpholdConstants.hasValidCredentials()) {
            analytics.logEvent(if (UpholdClient.getInstance().isAuthenticated) {
                AnalyticsConstants.Uphold.ENTER_CONNECTED
            } else {
                AnalyticsConstants.Uphold.ENTER_DISCONNECTED
            }, bundleOf())

            startActivity(UpholdAccountActivity.createIntent(this))
        }
    }

    private fun onLiquidItemClicked() {
        if (LiquidConstants.hasValidCredentials()) {
            analytics.logEvent(
                if (LiquidClient.getInstance()?.isAuthenticated == true) {
                    AnalyticsConstants.Liquid.ENTER_CONNECTED
                } else {
                    AnalyticsConstants.Liquid.ENTER_DISCONNECTED
                },
                bundleOf()
            )

            startActivityForResult(
                LiquidBuyAndSellDashActivity.createIntent(this),
                USER_BUY_SELL_DASH
            )
        }
    }

    private fun onCoinBaseItemClicked() {
        if (viewModel.isUserConnectedToCoinbase()) {
            launchCoinBasePortal()
            analytics.logEvent(AnalyticsConstants.Coinbase.ENTER_CONNECTED, bundleOf())
        } else {
            lifecycleScope.launch {
              val goodToGo = if (viewModel.shouldShowAuthInfoPopup) {
                    AdaptiveDialog.custom(
                        R_coinbase.layout.dialog_withdrawal_limit_info,
                        null,
                        getString(R_coinbase.string.set_auth_limit),
                        getString(R_coinbase.string.change_withdrawal_limit),
                        "",
                        getString(R_coinbase.string.got_it)
                    ).showAsync(this@BuyAndSellIntegrationsActivity) ?: false
                } else true

                if (goodToGo) {
                    viewModel.shouldShowAuthInfoPopup = false
                    startActivityForResult(
                        Intent(
                            this@BuyAndSellIntegrationsActivity,
                            CoinBaseWebClientActivity::class.java
                        ),
                        COIN_BASE_AUTH
                    )
                    analytics.logEvent(AnalyticsConstants.Coinbase.ENTER_DISCONNECTED, bundleOf())
                }
            }
        }
    }

    fun initViewModel() {
        viewModel.isDeviceConnectedToInternet.observe(this) { isConnected ->
            if (isConnected != null) {
                buyAndSellDashServicesAdapter.updateIconState(isConnected)
                setNetworkState(isConnected)
            }
        }

        viewModel.servicesList.observe(this) {
            buyAndSellDashServicesAdapter.submitList(it.toMutableList())
        }

        viewModel.upholdBalanceLiveData.observe(this) {
            if (it != null) {
                when (it.status) {
                    Status.LOADING -> {
                        loadingDialog?.show(this)
                    }
                    Status.SUCCESS -> {
                        if (!isFinishing) {
                            val balance = it.data.toString()
                            config.lastUpholdBalance = balance
                            viewModel.showRowBalance(
                                BuyAndSellDashServicesModel.ServiceType.UPHOLD,
                                currentExchangeRate,
                                balance
                            )
                        }
                        loadingDialog?.dismiss()
                    }
                    Status.ERROR -> {
                        if (!isFinishing) {

                            // TODO: if the exception is UnknownHostException and isNetworkOnline is true
                            // then there is a problem contacting the server and we don't have
                            // error handling for it
                            config.lastUpholdBalance?.let {
                                viewModel.showRowBalance(
                                    BuyAndSellDashServicesModel.ServiceType.UPHOLD,
                                    currentExchangeRate,
                                    config.lastUpholdBalance
                                )
                            }
                        }
                        loadingDialog?.dismiss()
                    }
                    Status.CANCELED -> {
                        config.lastUpholdBalance?.let {
                            viewModel.showRowBalance(
                                BuyAndSellDashServicesModel.ServiceType.UPHOLD,
                                currentExchangeRate,
                                config.lastUpholdBalance
                            )
                        }
                        loadingDialog?.dismiss()
                    }
                }
            }
        }

        liquidViewModel.liquidBalanceLiveData.observe(this) {
            if (it != null) {
                when (it.status) {
                    Status.LOADING -> {
                       loadingDialog?.show(this)
                    }
                    Status.SUCCESS -> {
                        if (!isFinishing) {
                            showDashLiquidBalance(it.data!!)
                        }
                        loadingDialog?.dismiss()
                    }
                    Status.ERROR -> {
                        if (!isFinishing) {
                            if (it.exception is LiquidUnauthorizedException) {
                                AdaptiveDialog.create(
                                    org.dash.wallet.integration.liquid.R.drawable.ic_liquid_icon,
                                    getString(org.dash.wallet.integration.liquid.R.string.liquid_logout_title),
                                    getString(org.dash.wallet.integration.liquid.R.string.liquid_forced_logout),
                                    "",
                                    getString(android.R.string.ok)
                                ).show(this) { okClicked ->
                                    if (okClicked == true) {
                                        startActivity(LiquidSplashActivity.createIntent(this@BuyAndSellIntegrationsActivity))
                                    }
                                }
                            }
                            // TODO: if the exception is UnknownHostException and isNetworkOnline is true
                            // then there is a problem contacting the server and we don't have
                            // error handling for it
                            liquidViewModel.lastLiquidBalance?.let { it1 ->
                                viewModel.showRowBalance(BuyAndSellDashServicesModel.ServiceType.LIQUID, currentExchangeRate,
                                    it1
                                )
                            }
                        }
                        loadingDialog?.dismiss()
                    }
                    Status.CANCELED -> {
                        liquidViewModel.lastLiquidBalance?.let { it1 ->
                            viewModel.showRowBalance(BuyAndSellDashServicesModel.ServiceType.LIQUID, currentExchangeRate,
                                it1
                            )
                        }
                        loadingDialog?.dismiss()
                    }
                }
            }
        }

        // for getting currency exchange rates
        val exchangeRatesViewModel = ViewModelProvider(this)[ExchangeRatesViewModel::class.java]
        exchangeRatesViewModel.getRate(config.exchangeCurrencyCode).observe(
            this
        ) { exchangeRate ->
            if (exchangeRate != null) {
                currentExchangeRate = exchangeRate
                liquidViewModel.lastLiquidBalance?.let {
                    viewModel.showRowBalance(
                        BuyAndSellDashServicesModel.ServiceType.LIQUID,
                        currentExchangeRate,
                        it
                    )
                }

                config.lastUpholdBalance?.let {
                    viewModel.showRowBalance(
                        BuyAndSellDashServicesModel.ServiceType.UPHOLD,
                        currentExchangeRate,
                        config.lastUpholdBalance
                    )
                }

                config.lastCoinbaseBalance
                    ?.let {
                        viewModel.showRowBalance(
                            BuyAndSellDashServicesModel.ServiceType.COINBASE,
                            currentExchangeRate,
                            it
                        )
                    }
            }
        }

        viewModel.isAuthenticatedOnCoinbase.observe(this){ setLoginStatus() }

        viewModel.coinbaseAuthTokenCallback.observe(this) {
            Timer().schedule(1000) {
                launchCoinBasePortal()
            }
        }

        viewModel.coinbaseBalance.observe(this){ balance ->
            balance?.let {
                viewModel.showRowBalance(BuyAndSellDashServicesModel.ServiceType.COINBASE, currentExchangeRate, it)
            }

        }
    }

    private fun setNetworkState(online: Boolean) {
        if (online && binding.networkStatusContainer.isVisible) {
            // Just got back online
            updateBalances()
        }

        binding.networkStatusContainer.isVisible = !online
        setLoginStatus()
    }

    private fun updateBalances() {
        if (LiquidClient.getInstance()!!.isAuthenticated) {
            liquidViewModel.updateLiquidBalance()
        }
        if (UpholdClient.getInstance().isAuthenticated) {
            viewModel.updateUpholdBalance()
        }
        if (viewModel.isUserConnectedToCoinbase()) {
            viewModel.updateCoinbaseBalance()
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.monitorNetworkStateChange()
        setLoginStatus()
        updateBalances()
    }

    private fun setLoginStatus() {
        viewModel.setServicesStatus(config.lastCoinbaseAccessToken.isNullOrEmpty().not(),
            LiquidClient.getInstance()!!.isAuthenticated, UpholdClient.getInstance().isAuthenticated)
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
        }
        return super.onOptionsItemSelected(item)
    }

    // TODO: Can these next two functions be refactored into the liquid module?
    private fun showDashLiquidBalance(data: String) {

        try {
            val jsonObject = JSONObject(data)
            val cryptoArray = jsonObject.getJSONObject("payload").getJSONArray("crypto_accounts")
            var amount = "0.00"
            liquidViewModel.lastLiquidBalance = amount
            for (i in 0 until cryptoArray.length()) {
                val currency = cryptoArray.getJSONObject(i).getString("currency")
                if (currency == "DASH") {
                    amount = cryptoArray.getJSONObject(i).getString("balance")
                    liquidViewModel.lastLiquidBalance = amount
                }
            }

            viewModel.showRowBalance(BuyAndSellDashServicesModel.ServiceType.LIQUID, currentExchangeRate, amount)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // TODO: can this be refactored into the uphold module>?

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == USER_BUY_SELL_DASH && resultCode == RESULT_CODE_GO_HOME) {
            log.info("liquid: activity result for user buy sell dash was RESULT_CODE_GO_HOME")
            if (LiquidClient.getInstance()!!.isAuthenticated) {
                liquidViewModel.updateLiquidBalance()
            }
            setResult(RESULT_CODE_GO_HOME)
            finish()
        } else if (requestCode == COIN_BASE_AUTH) {
            if (resultCode == RESULT_OK) {
                if (data?.hasExtra(CoinBaseWebClientActivity.RESULT_TEXT) == true) {
                    data?.extras?.getString(CoinBaseWebClientActivity.RESULT_TEXT)?.let { code ->
                        viewModel.loginToCoinbase(code)
                    }
                }
            }
        }
    }

    override fun onPause() {
        viewModel.setLoadingState(false)
        super.onPause()
    }

    private fun launchCoinBasePortal(){
        startActivityForResult(Intent(this, CoinbaseActivity::class.java), USER_BUY_SELL_DASH)
    }
}
