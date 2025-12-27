/*
 * Copyright 2019 Dash Core Group
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

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.WalletBalanceWidgetProvider
import de.schildbach.wallet.service.work.BaseWorker
import de.schildbach.wallet.ui.coinjoin.CoinJoinActivity
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet.ui.more.AboutActivity
import de.schildbach.wallet.ui.more.SettingsScreen
import de.schildbach.wallet.ui.more.SettingsViewModel
import de.schildbach.wallet.ui.more.TransactionMetadataSettingsViewModel
import de.schildbach.wallet_test.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.SystemActionsService
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.exchange_rates.ExchangeRatesDialog
import org.dash.wallet.common.util.observe
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {
    private val log = LoggerFactory.getLogger(SettingsFragment::class.java)
    //private val binding by viewBinding(FragmentSettingsBinding::bind)
    private val viewModel: SettingsViewModel by viewModels()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val transactionMetadataSettingsViewModel: TransactionMetadataSettingsViewModel by viewModels()
    @Inject
    lateinit var systemActions: SystemActionsService
    @Inject
    lateinit var walletUIConfig: WalletUIConfig
    @Inject
    lateinit var walletApplication: WalletApplication
    private val dateFormat = SimpleDateFormat.getDateInstance(SimpleDateFormat.MEDIUM)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        setupTransactionMetadataObservers()

        return ComposeView(requireContext()).apply {
            setContent {
                SettingsScreen(
                    onBackClick = {
                        findNavController().popBackStack()
                    },
                    onLocalCurrencyClick = {
                        lifecycleScope.launch {
                            viewModel.logEvent(AnalyticsConstants.Settings.LOCAL_CURRENCY)
                            val currentOption = walletUIConfig.getExchangeCurrencyCode()
                            ExchangeRatesDialog(currentOption) { rate, _, dialog ->
                                setSelectedCurrency(rate.currencyCode)
                                dialog.dismiss()
                            }.show(requireActivity())
                        }
                    },
                    onRescanBlockchainClick = { resetBlockchain() },
                    onAboutDashClick = {
                        viewModel.logEvent(AnalyticsConstants.Settings.ABOUT)
                        startActivity(Intent(requireContext(), AboutActivity::class.java))
                    },
                    onNotificationsClick = { systemActions.openNotificationSettings()  },
                    onCoinJoinClick = {
                        lifecycleScope.launch {
                            val shouldShowFirstTimeInfo = viewModel.shouldShowCoinJoinInfo()

                            if (shouldShowFirstTimeInfo) {
                                viewModel.setCoinJoinInfoShown()
                            }

                            val intent = Intent(requireContext(), CoinJoinActivity::class.java)
                            intent.putExtra(CoinJoinActivity.FIRST_TIME_EXTRA, shouldShowFirstTimeInfo)
                            viewModel.logEvent(AnalyticsConstants.Settings.COINJOIN)
                            startActivity(intent)
                        }
                    },
                    onTransactionMetadataClick = {
                        lifecycleScope.launch {
                            if (viewModel.isTransactionMetadataInfoShown()) {
                                // Use the correct direction - we need to find the actual navigation action
                                // For now, navigate directly to the transaction metadata fragment
                                findNavController().navigate(
                                    R.id.transaction_metadata_settings_fragment
                                )
                            } else {
                                findNavController().navigate(
                                    R.id.transaction_metadata_info_dialog,
                                    bundleOf(
                                        "first_time" to true,
                                        "use_navigation" to true
                                    )
                                )
                            }
                        }
                    },
                    onBatteryOptimizationClick = { batteryOptimization() }
                )
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun setupTransactionMetadataObservers() {
        lifecycleScope.launch {
            transactionMetadataSettingsViewModel.loadLastWorkId()
        }

        transactionMetadataSettingsViewModel.lastSaveWorkId.filterNotNull().observe(viewLifecycleOwner) { workId ->
            transactionMetadataSettingsViewModel.observePublishOperation(workId).observe(viewLifecycleOwner) {
                val progress = it.data?.progress?.let { data -> BaseWorker.extractProgress(data) } ?: 0
                setTransactionMetadataText(progress != 100 && progress != -1, progress)
            }
        }

        setTransactionMetadataText(isSaving = false, saveProgress = -1)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun setTransactionMetadataText(isSaving: Boolean, saveProgress: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            val subtitle = if (isSaving) {
                getString(R.string.transaction_metadata_saving_to_network, saveProgress)
            } else {
                val lastSavedDate = transactionMetadataSettingsViewModel.lastSaveDate.value
                when {
                    lastSavedDate > 0  -> {
                        getString(
                            R.string.transaction_metadata_last_saved_date,
                            dateFormat.format(Date(lastSavedDate))
                        )
                    }
                    viewModel.isSavingTransactionMetadata() -> {
                        getString(R.string.transaction_metadata_data_being_saved)
                    }
                    else -> null
                }
            }
            viewModel.updateTransactionMetadataSubtitle(subtitle)
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.updateIgnoringBatteryOptimizations()
    }

    private fun resetBlockchain() {
        val walletApplication = requireActivity().application as WalletApplication
        val configuration = walletApplication.configuration
        AdaptiveDialog.create(
            null,
            getString(R.string.preferences_initiate_reset_title),
            getString(R.string.preferences_initiate_reset_dialog_message),
            getString(R.string.button_cancel),
            getString(R.string.preferences_initiate_reset_dialog_positive)
        ).show(requireActivity()) {
            if (it == true) {
                log.info("manually initiated blockchain reset")
                viewModel.logEvent(AnalyticsConstants.Settings.RESCAN_BLOCKCHAIN_RESET)

                walletApplication.resetBlockchain()
                configuration.updateLastBlockchainResetTime()
                startActivity(MainActivity.createIntent(requireContext()))
            } else {
                viewModel.logEvent(AnalyticsConstants.Settings.RESCAN_BLOCKCHAIN_DISMISS)
            }
        }
    }

    private fun setSelectedCurrency(code: String) {
        lifecycleScope.launch {
            walletUIConfig.set(WalletUIConfig.SELECTED_CURRENCY, code)
            val walletApplication = requireActivity().application as WalletApplication
            val balance = walletApplication.wallet!!.getBalance(Wallet.BalanceType.ESTIMATED)
            WalletBalanceWidgetProvider.updateWidgets(requireContext(), balance)
        }
    }

    private fun batteryOptimization() {
        val walletApplication = requireActivity().application as WalletApplication
        if (ContextCompat.checkSelfPermission(
                walletApplication,
                Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ) == PackageManager.PERMISSION_GRANTED &&
            !viewModel.uiState.value.ignoringBatteryOptimizations
        ) {
            AdaptiveDialog.create(
                R.drawable.ic_bolt_border,
                getString(R.string.battery_optimization_dialog_optimized_title),
                getString(R.string.battery_optimization_dialog_message_optimized),
                getString(R.string.permission_deny),
                getString(R.string.permission_allow)
            ).show(requireActivity()) { allow ->
                if (allow == true) {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${requireActivity().packageName}")
                        )
                    )
                }
            }
        } else {
            AdaptiveDialog.create(
                R.drawable.ic_transaction_received_border,
                getString(R.string.battery_optimization_dialog_unrestricted_title),
                getString(R.string.battery_optimization_dialog_message_not_optimized),
                getString(R.string.close),
                getString(R.string.battery_optimization_dialog_button_change)
            ).show(requireActivity()) { allow ->
                if (allow == true) {
                    // Show the list of non-optimized apps
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    if (intent.resolveActivity(requireActivity().packageManager) != null) {
                        startActivity(intent)
                    }
                }
            }
        }
    }
}