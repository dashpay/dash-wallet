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

package de.schildbach.wallet.ui.more

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.ui.AddressBookActivity
import de.schildbach.wallet.ui.NetworkMonitorActivity
import de.schildbach.wallet.ui.more.tools.WhatAreCreditsDialogFragment
import de.schildbach.wallet.ui.more.tools.ZenLedgerViewModel
import de.schildbach.wallet.ui.compose_views.createExportCSVDialog
import de.schildbach.wallet.ui.compose_views.createExtendedPublicKeyDialog
import de.schildbach.wallet.ui.compose_views.createImportPrivateKeyDialog
import de.schildbach.wallet.ui.compose_views.createZenLedgerDialog
import de.schildbach.wallet.ui.payments.SweepWalletActivity
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet.util.Toast
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.dash.wallet.common.SecureActivity
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.slf4j.LoggerFactory
import javax.inject.Inject

@AndroidEntryPoint
class ToolsFragment : Fragment() {
    @Inject lateinit var authManager: SecurityFunctions

    companion object {
        private val log = LoggerFactory.getLogger(ToolsFragment::class.java)
    }

    @Inject
    lateinit var analytics: AnalyticsService
    private val viewModel: ToolsViewModel by viewModels()
    private val zenLedgerViewModel: ZenLedgerViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                DashWalletTheme {
                    ToolsScreen(
                        onBackClick = { findNavController().popBackStack() },
                        onAddressBookClick = { onAddressBook() },
                        onImportPrivateKeyClick = { onImportKeys() },
                        onNetworkMonitorClick = { onNetworkMonitor() },
                        onExtendPublicKeyClick = { handleExtendedPublicKey() },
                        onMasternodeKeysClick = { onMasternodeKeys() },
                        onCsvExportClick = { onTransactionExport() },
                        onZenLedgerExport = { onZenLedgerExport() },
                        onCreditsInfoClick = { onCreditsInfo() },
                        onBuyCredits = { onBuyCredits() }
                    )
                }
            }
        }
    }

    private fun onZenLedgerExport() {
        viewModel.logEvent(AnalyticsConstants.Tools.ZENLEDGER)
        val secureActivity = requireActivity() as? SecureActivity
        secureActivity?.turnOffAutoLogout()
        createZenLedgerDialog(
            viewModel = zenLedgerViewModel,
            onDismiss = { secureActivity?.turnOnAutoLogout() }
        ).show(parentFragmentManager, "zenledger_dialog")
    }

    private fun onCreditsInfo() {
        WhatAreCreditsDialogFragment.newInstance(false).show(requireActivity())
    }

    private fun onBuyCredits() = lifecycleScope.launch {
        if (!viewModel.creditsExplained()) {
            WhatAreCreditsDialogFragment.newInstance(true).show(requireActivity()) {
                SendCoinsActivity.startBuyCredits(requireActivity())
            }
        } else {
            SendCoinsActivity.startBuyCredits(requireActivity())
        }
    }

    private fun onTransactionExport() {
        if (viewModel.uiState.value.isSyncing) {
            AdaptiveDialog.create(
                null,
                getString(R.string.report_transaction_history_not_synced_title),
                getString(R.string.report_transaction_history_not_synced_message),
                "",
                getString(R.string.button_close)
            ).show(parentFragmentManager, "requireSyncing")
        } else {
            viewModel.logEvent(AnalyticsConstants.Tools.EXPORT_CSV)
            val secureActivity = requireActivity() as? SecureActivity
            secureActivity?.turnOffAutoLogout()
            createExportCSVDialog(
                viewModel = viewModel,
                onDismiss = { secureActivity?.turnOnAutoLogout() }
            ).show(parentFragmentManager, "export_csv_dialog")
        }
    }

    private fun onMasternodeKeys() = lifecycleScope.launch {
        val pin = authManager.authenticate(requireActivity(), true)
        pin?.let {
            findNavController().navigate(
                R.id.masternodeKeyTypeFragment,
                bundleOf(),
                NavOptions.Builder()
                    .setEnterAnim(R.anim.slide_in_bottom)
                    .build()
            )
        }
    }

    private fun onNetworkMonitor() {
        analytics.logEvent(AnalyticsConstants.Settings.NETWORK_MONITORING, mapOf())
        startActivity(Intent(requireContext(), NetworkMonitorActivity::class.java))
    }

    private fun onImportKeys() {
        analytics.logEvent(AnalyticsConstants.Settings.IMPORT_PRIVATE_KEY, mapOf())
        createImportPrivateKeyDialog(
            onScanPrivateKey = {
                SweepWalletActivity.start(requireContext(), false)
            }
        ).show(parentFragmentManager, "import_private_key")

    }

    private fun onAddressBook() {
        analytics.logEvent(AnalyticsConstants.Settings.ADDRESS_BOOK, mapOf())
        startActivity(Intent(requireContext(), AddressBookActivity::class.java))
    }

    private fun handleExtendedPublicKey() {
        createExtendedPublicKeyDialog(
            xpubWithCreationDate = viewModel.xpubWithCreationDate,
            xpub = viewModel.xpub,
            onCopy = {
                viewModel.copyXpubToClipboard()
                Toast(requireContext()).toast(R.string.copied)
                log.info("xpub copied to clipboard: {}", viewModel.xpub)
            },
            onShare = { xpubWithCreationDate ->
                createAndLaunchShareIntent(xpubWithCreationDate)
            }
        ).show(parentFragmentManager, "extended_public_key")
    }

    private fun createAndLaunchShareIntent(xpub: String) {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, xpub)
        intent.putExtra(
            Intent.EXTRA_SUBJECT,
            getString(R.string.extended_public_key_fragment_title)
        )
        startActivity(
            Intent.createChooser(
                intent,
                getString(R.string.extended_public_key_fragment_share)
            )
        )
        log.info("xpub shared via intent: {}", xpub)
    }

}
