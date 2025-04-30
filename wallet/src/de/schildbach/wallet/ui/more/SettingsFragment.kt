/*
 * Copyright (c) 2019-2025 Dash Core Group
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.more

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.WalletBalanceWidgetProvider
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.MixingStatus
import de.schildbach.wallet.service.platform.work.PublishTransactionMetadataOperation
import de.schildbach.wallet.service.platform.work.PublishTransactionMetadataWorker
import de.schildbach.wallet.service.work.BaseWorker
import de.schildbach.wallet.ui.coinjoin.CoinJoinActivity
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentSettingsBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.services.SystemActionsService
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.exchange_rates.ExchangeRatesDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import org.slf4j.LoggerFactory
import java.text.SimpleDateFormat
import java.util.Date
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment(R.layout.fragment_settings) {
    companion object {
        private val log = LoggerFactory.getLogger(SettingsFragment::class.java)
    }
    private val binding by viewBinding(FragmentSettingsBinding::bind)
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

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = MaterialFadeThrough()
        reenterTransition = MaterialFadeThrough()

        binding.appBar.toolbar.title = getString(R.string.settings_title)
        binding.appBar.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.about.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Settings.ABOUT)
            requireActivity().startActivity(Intent(requireContext(), AboutActivity::class.java))
        }
        binding.localCurrency.setOnClickListener {
            lifecycleScope.launch {
                viewModel.logEvent(AnalyticsConstants.Settings.LOCAL_CURRENCY)
                val currentOption = walletUIConfig.getExchangeCurrencyCode()
                ExchangeRatesDialog(currentOption) { rate, _, dialog ->
                    setSelectedCurrency(rate.currencyCode)
                    dialog.dismiss()
                }.show(requireActivity())
            }
        }

        binding.rescanBlockchain.setOnClickListener { resetBlockchain() }
        binding.notifications.setOnClickListener { systemActions.openNotificationSettings() }
        binding.batteryOptimization.setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    requireContext(),
                    Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                ) == PackageManager.PERMISSION_GRANTED &&
                !viewModel.isIgnoringBatteryOptimizations
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
                                Uri.parse("package:${requireContext().packageName}")
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
                        if (intent.resolveActivity(requireContext().packageManager) != null) {
                            startActivity(intent)
                        }
                    }
                }
            }
        }
        setBatteryOptimizationText()
        binding.coinjoin.setOnClickListener {
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
        }

        viewModel.localCurrencySymbol.observe(viewLifecycleOwner) {
            binding.localCurrencySymbol.text = it
        }

        viewModel.coinJoinMixingMode.observe(this) { mode ->
            if (mode == CoinJoinMode.NONE) {
                binding.coinjoinSubtitle.text = getText(R.string.turned_off)
                binding.coinjoinSubtitleIcon.isVisible = false
                binding.progressBar.isVisible = false
                binding.balance.isVisible = false
                binding.coinjoinProgress.isVisible = false
            } else {
                if (viewModel.coinJoinMixingStatus == MixingStatus.FINISHED) {
                    binding.coinjoinSubtitle.text = getString(R.string.coinjoin_progress_finished)
                    binding.coinjoinSubtitleIcon.isVisible = false
                    binding.progressBar.isVisible = false
                    binding.coinjoinProgress.isVisible = false
                } else {
                    @StringRes val statusId = when(viewModel.coinJoinMixingStatus) {
                        MixingStatus.NOT_STARTED -> R.string.coinjoin_not_started
                        MixingStatus.MIXING -> R.string.coinjoin_mixing
                        MixingStatus.FINISHING -> R.string.coinjoin_mixing_finishing
                        MixingStatus.PAUSED -> R.string.coinjoin_paused
                        MixingStatus.FINISHED -> R.string.coinjoin_progress_finished
                        else -> R.string.error
                    }

                    binding.coinjoinSubtitle.text = getString(statusId)
                    binding.coinjoinSubtitleIcon.isVisible = true
                    binding.progressBar.isVisible = viewModel.coinJoinMixingStatus == MixingStatus.MIXING
                    binding.coinjoinProgress.text = getString(R.string.percent, viewModel.mixingProgress.toInt())
                    binding.coinjoinProgress.isVisible = true
                    binding.balance.isVisible = true
                    binding.balance.text = getString(R.string.coinjoin_progress_balance, viewModel.mixedBalance, viewModel.walletBalance)
                }
            }
        }
        viewModel.blockchainIdentity.observe(viewLifecycleOwner) {
            binding.transactionMetadata.isVisible = it?.creationComplete ?: false
        }
        transactionMetadataSettingsViewModel.lastSaveWorkId.filterNotNull().observe(viewLifecycleOwner) { workId ->
            transactionMetadataSettingsViewModel.publishOperationLiveData(workId).observe(viewLifecycleOwner) {
                val progress = it.data?.progress?.let { data -> BaseWorker.extractProgress(data) } ?: 0
                setTransactionMetadataText(progress != 100 && progress != -1, progress)
            }
        }
        binding.transactionMetadata.setOnClickListener {
            lifecycleScope.launch {
                if (viewModel.isTransactionMetadataInfoShown()) {
                    safeNavigate(SettingsFragmentDirections.settingsToTransactionMetadata(false))
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
        }
    }

    private fun setBatteryOptimizationText() {
        binding.batterySettingsSubtitle.text = getString(
            if (viewModel.isIgnoringBatteryOptimizations) {
                R.string.battery_optimization_subtitle_unrestricted
            } else {
                R.string.battery_optimization_subtitle_optimized
            }
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun setTransactionMetadataText(isSaving: Boolean, saveProgress: Int) {
        lifecycleScope.launch {
            if (isSaving) {
                binding.transactionMetadataSubtitle.text = getString(R.string.transaction_metadata_saving_to_network, saveProgress)
                binding.transactionMetadataSubtitle.isGone = false
            } else {
                val lastSavedDate = transactionMetadataSettingsViewModel.lastSaveDate.value
                if (lastSavedDate != -0L) {
                    binding.transactionMetadataSubtitle.text =
                        getString(
                            R.string.transaction_metadata_last_saved_date,
                            dateFormat.format(
                                Date(lastSavedDate)
                            )
                        )
                    binding.transactionMetadataSubtitle.isGone = false
                } else if (viewModel.isSavingTransactionMetadata()) {
                    binding.transactionMetadataSubtitle.text = getString(R.string.transaction_metadata_data_being_saved)
                    binding.transactionMetadataSubtitle.isGone = !viewModel.isSavingTransactionMetadata()
                } else {
                    binding.transactionMetadataSubtitle.isGone = true
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setBatteryOptimizationText()
        //setTransactionMetadataText()
    }

    private fun resetBlockchain() {
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
                viewModel.updateLastBlockchainResetTime()
                startActivity(MainActivity.createIntent(requireContext()))
            } else {
                viewModel.logEvent(AnalyticsConstants.Settings.RESCAN_BLOCKCHAIN_DISMISS)
            }
        }
    }

    private fun setSelectedCurrency(code: String) {
        lifecycleScope.launch {
            walletUIConfig.set(WalletUIConfig.SELECTED_CURRENCY, code)
            val balance = viewModel.getTotalWalletBalance()
            WalletBalanceWidgetProvider.updateWidgets(requireActivity(), balance)
        }
    }
}
