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
package org.dash.wallet.integration.coinbase_integration.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.bitcoinj.core.Coin
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.FragmentCoinbaseConvertCryptoBinding
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountDataUIModel
import org.dash.wallet.integration.coinbase_integration.model.CoinbaseGenericErrorUIModel
import org.dash.wallet.integration.coinbase_integration.ui.convert_currency.ConvertViewFragment
import org.dash.wallet.integration.coinbase_integration.viewmodels.CoinbaseConvertCryptoViewModel
import org.dash.wallet.integration.coinbase_integration.viewmodels.ConvertViewViewModel

@AndroidEntryPoint
class CoinbaseConvertCryptoFragment : Fragment(R.layout.fragment_coinbase_convert_crypto) {
    private val binding by viewBinding(FragmentCoinbaseConvertCryptoBinding::bind)
    private val viewModel by viewModels<CoinbaseConvertCryptoViewModel>()
    private var loadingDialog: FancyAlertDialog? = null
    private var currentExchangeRate: org.dash.wallet.common.data.ExchangeRate? = null
    private val _binding: FragmentCoinbaseConvertCryptoBinding? = null
    private val convertViewModel by activityViewModels<ConvertViewViewModel>()
    private var selectedCoinBaseAccount: CoinBaseUserAccountDataUIModel? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        if (savedInstanceState == null) {
            val fragment = ConvertViewFragment.newInstance()
            fragment.setViewDetails(getString(R.string.get_quote), null)

            parentFragmentManager.commit {
                setReorderingAllowed(true)
                add(R.id.enter_amount_fragment_placeholder, fragment)
            }

            viewModel.userAccountsInfo.observe(viewLifecycleOwner) {
                fragment.setUserAccountsInfo(it)
            }
        }

        viewModel.showLoading.observe(
            viewLifecycleOwner
        ) {
            if (it) {
                showProgress(R.string.loading)
            } else
                dismissProgress()
        }

        convertViewModel.selectedLocalExchangeRate.observe(viewLifecycleOwner) { rate ->
            binding.toolbarSubtitle.text = getString(
                R.string.exchange_rate_template,
                Coin.COIN.toPlainString(),
                GenericUtils.fiatToString(rate.fiat)
            )
        }

        convertViewModel.selectedCryptoCurrencyAccount.observe(viewLifecycleOwner) { account ->
            selectedCoinBaseAccount = account
        }

        convertViewModel.onContinueEvent.observe(viewLifecycleOwner) { pair ->
            if (!pair.first && selectedCoinBaseAccount?.coinBaseUserAccountData?.currency?.code != "DASH") {
                selectedCoinBaseAccount?.let { viewModel.swapTrade(pair.second, it) }
            }
        }

        viewModel.swapTradeOrder.observe(viewLifecycleOwner) {
            safeNavigate(CoinbaseConvertCryptoFragmentDirections.coinbaseConvertCryptoFragmentTocoinbaseConversionPreviewFragment(it))
        }

        viewModel.swapTradeFailedCallback.observe(viewLifecycleOwner) {
            val placeBuyOrderError = CoinbaseGenericErrorUIModel(
                R.string.error,
                it,
                R.drawable.ic_info_red,
                negativeButtonText = R.string.close
            )
            CoinbaseServicesFragmentDirections.coinbaseServicesToError(placeBuyOrderError)
        }
    }

    private fun showProgress(messageResId: Int) {
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
        loadingDialog = FancyAlertDialog.newProgress(messageResId, 0)
        loadingDialog?.show(parentFragmentManager, "progress")
    }

    private fun dismissProgress() {
        if (loadingDialog != null && loadingDialog?.isAdded == true) {
            loadingDialog?.dismissAllowingStateLoss()
        }
    }
}
