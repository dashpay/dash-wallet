/*
 * Copyright 2021 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.buy_sell

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.adapter.BuyAndSellDashServicesAdapter
import de.schildbach.wallet.data.BuyAndSellDashServicesModel
import de.schildbach.wallet.ui.coinbase.CoinbaseActivity
import de.schildbach.wallet.ui.rates.ExchangeRatesViewModel
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentBuySellIntegrationsBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.Constants.*
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.data.Status
import org.dash.wallet.common.ui.NetworkUnavailableFragment
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integration.uphold.api.UpholdClient
import org.dash.wallet.integration.uphold.data.UpholdConstants
import org.dash.wallet.integration.uphold.ui.UpholdAccountActivity
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.concurrent.schedule

@ExperimentalCoroutinesApi
@AndroidEntryPoint
class BuyAndSellIntegrationsFragment : Fragment(R.layout.fragment_buy_sell_integrations) {
    companion object {
        val log: Logger = LoggerFactory.getLogger(BuyAndSellIntegrationsFragment::class.java)
    }

    private var loadingDialog: AdaptiveDialog? = null
    private var currentExchangeRate: ExchangeRate? = null

    private val binding by viewBinding(FragmentBuySellIntegrationsBinding::bind)
    private val viewModel by viewModels<BuyAndSellViewModel>()
    private val exchangeRatesViewModel by viewModels<ExchangeRatesViewModel>()
    private val buyAndSellDashServicesAdapter: BuyAndSellDashServicesAdapter by lazy {
        BuyAndSellDashServicesAdapter(viewModel.config) { model ->
            when (model.serviceType) {
                BuyAndSellDashServicesModel.ServiceType.UPHOLD -> onUpHoldItemClicked()
                BuyAndSellDashServicesModel.ServiceType.COINBASE -> onCoinBaseItemClicked()
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        log.info("starting Buy and Sell Dash activity")

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        loadingDialog = AdaptiveDialog.progress(getString(R.string.loading))
        initViewModel()

//        parentFragmentManager.beginTransaction()
//            .replace(R.id.network_status_container, NetworkUnavailableFragment.newInstance())
//            .commitNow()
        binding.networkStatusContainer.isVisible = false

        // check for missing keys from service.properties
        if (!UpholdConstants.hasValidCredentials()) {
            binding.keysMissingError.isVisible = true
        }
        binding.dashServicesList.itemAnimator = null
        binding.dashServicesList.adapter = buyAndSellDashServicesAdapter

        viewModel.showLoading.observe(viewLifecycleOwner) { showDialog ->
            if (showDialog) loadingDialog?.show(requireActivity())
            else if (loadingDialog?.isAdded == true) loadingDialog?.dismiss()
        }

        lifecycleScope.launchWhenResumed {
            checkLiquidStatus()
        }
    }

    private fun onUpHoldItemClicked() {
        if (UpholdConstants.hasValidCredentials()) {
            viewModel.logEnterUphold()
            startActivity(UpholdAccountActivity.createIntent(requireContext()))
        }
    }

    private fun onCoinBaseItemClicked() {
        viewModel.logEnterCoinbase()
        if (viewModel.isUserConnectedToCoinbase()) {
            launchCoinBasePortal()
        } else {


//            lifecycleScope.launch {
//              val goodToGo = if (viewModel.shouldShowAuthInfoPopup) {
//                    AdaptiveDialog.custom(
//                        R_coinbase.layout.dialog_withdrawal_limit_info,
//                        null,
//                        getString(R_coinbase.string.set_auth_limit),
//                        getString(R_coinbase.string.change_withdrawal_limit),
//                        "",
//                        getString(R_coinbase.string.got_it)
//                    ).showAsync(this@BuyAndSellIntegrationsActivity) ?: false
//                } else true
//
//                if (goodToGo) {
//                    viewModel.shouldShowAuthInfoPopup = false
//                    startActivityForResult(
//                        Intent(
//                            this@BuyAndSellIntegrationsActivity,
//                            CoinBaseWebClientActivity::class.java
//                        ),
//                        COIN_BASE_AUTH
//                    )
//
//                }
//            }
        }
    }

    fun initViewModel() {
        viewModel.isDeviceConnectedToInternet.observe(viewLifecycleOwner) { isConnected ->
            if (isConnected != null) {
                buyAndSellDashServicesAdapter.updateIconState(isConnected)
                setNetworkState(isConnected)
            }
        }

        viewModel.servicesList.observe(viewLifecycleOwner) {
            buyAndSellDashServicesAdapter.submitList(it.toMutableList())
        }

        viewModel.upholdBalanceLiveData.observe(viewLifecycleOwner) {
            if (it != null) {
                when (it.status) {
                    Status.LOADING -> { }
                    Status.SUCCESS -> {
                        if (isAdded) {
                            val balance = it.data.toString()
                            viewModel.showRowBalance(
                                BuyAndSellDashServicesModel.ServiceType.UPHOLD,
                                currentExchangeRate,
                                balance
                            )
                        }
                    }
                    Status.ERROR -> {
                        if (!isAdded) {

                            // TODO: if the exception is UnknownHostException and isNetworkOnline is true
                            // then there is a problem contacting the server and we don't have
                            // error handling for it
                            viewModel.config.lastUpholdBalance?.let {
                                viewModel.showRowBalance(
                                    BuyAndSellDashServicesModel.ServiceType.UPHOLD,
                                    currentExchangeRate,
                                    viewModel.config.lastUpholdBalance
                                )
                            }
                        }
                    }
                    Status.CANCELED -> {
                        viewModel.config.lastUpholdBalance?.let {
                            viewModel.showRowBalance(
                                BuyAndSellDashServicesModel.ServiceType.UPHOLD,
                                currentExchangeRate,
                                viewModel.config.lastUpholdBalance
                            )
                        }
                    }
                }
            }
        }

        // for getting currency exchange rates
        exchangeRatesViewModel.getRate(
            viewModel.config.exchangeCurrencyCode
        ).observe(viewLifecycleOwner) { exchangeRate ->
            if (exchangeRate != null) {
                currentExchangeRate = exchangeRate

                viewModel.config.lastUpholdBalance?.let {
                    viewModel.showRowBalance(
                        BuyAndSellDashServicesModel.ServiceType.UPHOLD,
                        currentExchangeRate,
                        viewModel.config.lastUpholdBalance
                    )
                }

                viewModel.config.lastCoinbaseBalance
                    ?.let {
                        viewModel.showRowBalance(
                            BuyAndSellDashServicesModel.ServiceType.COINBASE,
                            currentExchangeRate,
                            it
                        )
                    }
            }
        }

        viewModel.isAuthenticatedOnCoinbase.observe(viewLifecycleOwner){ setLoginStatus() }

        viewModel.coinbaseAuthTokenCallback.observe(viewLifecycleOwner) {
            Timer().schedule(1000) {
                launchCoinBasePortal()
            }
        }

        viewModel.coinbaseBalance.observe(viewLifecycleOwner){ balance ->
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
        viewModel.setServicesStatus(
            viewModel.config.lastCoinbaseAccessToken.isNullOrEmpty().not(),
            UpholdClient.getInstance().isAuthenticated
        )
    }

    // TODO: can this be refactored into the uphold module>?
// todo
//    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
//        super.onActivityResult(requestCode, resultCode, data)
//        if (requestCode == USER_BUY_SELL_DASH && resultCode == RESULT_CODE_GO_HOME) {
//            log.info("activity result for user buy sell dash was RESULT_CODE_GO_HOME") // TODO
//            setResult(RESULT_CODE_GO_HOME)
//            finish()
//        } else if (requestCode == COIN_BASE_AUTH) {
//            if (resultCode == RESULT_OK) {
//                if (data?.hasExtra(CoinBaseWebClientActivity.RESULT_TEXT) == true) {
//                    data?.extras?.getString(CoinBaseWebClientActivity.RESULT_TEXT)?.let { code ->
//                        viewModel.loginToCoinbase(code)
//                    }
//                }
//            }
//        }
//    }

    override fun onPause() {
        viewModel.setLoadingState(false)
        super.onPause()
    }

    private fun launchCoinBasePortal() {
        startActivityForResult(Intent(requireContext(), CoinbaseActivity::class.java), USER_BUY_SELL_DASH)
    }

    private fun checkLiquidStatus() {
        val liquidClient = LiquidClient.getInstance()

        if (liquidClient.isAuthenticated) {
            AdaptiveDialog.custom(
                R.layout.dialog_liquid_unavailable,
                null,
                "",
                "",
                "",
                getString(android.R.string.ok)
            ).apply { isCancelable = false }
             .show(requireActivity()) {
                 liquidClient.clearLiquidData()
            }
        }
    }
}
