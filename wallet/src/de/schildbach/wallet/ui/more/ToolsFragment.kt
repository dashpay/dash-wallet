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
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.google.android.material.transition.MaterialFadeThrough
import com.google.common.base.Stopwatch
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.ui.AddressBookActivity
import de.schildbach.wallet.ui.NetworkMonitorActivity
import de.schildbach.wallet.ui.more.tools.WhatAreCreditsDialogFragment
import de.schildbach.wallet.ui.more.tools.ZenLedgerDialogFragment
import de.schildbach.wallet.ui.payments.SweepWalletActivity
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet.util.Toast
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentToolsBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.FlowPreview
import org.dash.wallet.common.SecureActivity
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.BaseAlertDialogBuilder
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.Qr
import org.dash.wallet.common.util.observe
import org.slf4j.LoggerFactory
import javax.inject.Inject

@FlowPreview
@AndroidEntryPoint
class ToolsFragment : Fragment(R.layout.fragment_tools) {
    @Inject lateinit var authManager: SecurityFunctions

    companion object {
        private val log = LoggerFactory.getLogger(ToolsFragment::class.java)
    }

    private val binding by viewBinding(FragmentToolsBinding::bind)

    @Inject
    lateinit var analytics: AnalyticsService
    private val viewModel: ToolsViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = MaterialFadeThrough()
        reenterTransition = MaterialFadeThrough()

        binding.appBar.toolbar.title = getString(R.string.tools_title)
        binding.appBar.toolbar.setNavigationOnClickListener {
            findNavController().popBackStack()
        }

        binding.addressBook.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.Settings.ADDRESS_BOOK, mapOf())
            startActivity(Intent(requireContext(), AddressBookActivity::class.java))
        }
        binding.importKeys.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.Settings.IMPORT_PRIVATE_KEY, mapOf())
            startActivity(Intent(requireContext(), SweepWalletActivity::class.java))
        }
        binding.networkMonitor.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.Settings.NETWORK_MONITORING, mapOf())
            startActivity(Intent(requireContext(), NetworkMonitorActivity::class.java))
        }
        binding.masternodeKeys.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
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
        }

        binding.showXpub.setOnClickListener {
            handleExtendedPublicKey()
        }

        var isSyncing = false
        viewModel.blockchainState.observe(viewLifecycleOwner) {
            isSyncing = it?.replaying == true
        }

        binding.transactionExport.setOnClickListener {
            if (isSyncing) {
                val dialog = AdaptiveDialog.create(
                    null,
                    getString(R.string.report_transaction_history_not_synced_title),
                    getString(R.string.report_transaction_history_not_synced_message),
                    "",
                    getString(R.string.button_close)
                )
                dialog.show(requireActivity().supportFragmentManager, "requireSyncing")
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    val watch = Stopwatch.createStarted()
                    val transactionExporter = viewModel.getTransactionExporter()
                    log.info("export transaction csv time: {}", watch)
                    viewModel.logEvent(AnalyticsConstants.Tools.EXPORT_CSV)
                    (requireActivity() as? SecureActivity)?.turnOffAutoLogout()
                    ExportCSVDialogFragment().show(requireActivity(), transactionExporter) {
                        (requireActivity() as? SecureActivity)?.turnOnAutoLogout()
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            binding.buyCreditsContainer.isVisible = viewModel.hasUsername()
        }
        binding.buyCreditsInfoButton.setOnClickListener {
            WhatAreCreditsDialogFragment.newInstance(false).show(requireActivity())
        }

        binding.buyCreditsButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                if (!viewModel.creditsExplained()) {
                    WhatAreCreditsDialogFragment.newInstance(true).show(requireActivity()) {
                        SendCoinsActivity.startBuyCredits(requireActivity())
                    }
                } else {
                    SendCoinsActivity.startBuyCredits(requireActivity())
                }
            }
        }

        binding.zenledgerExport.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Tools.ZENLEDGER)
            ZenLedgerDialogFragment().show(requireActivity())
        }
    }

    private fun handleExtendedPublicKey() {
        showExtendedPublicKeyDialog(viewModel.xpubWithCreationDate, viewModel.xpub)
    }

    private fun showExtendedPublicKeyDialog(xpubWithCreationDate: String, xpub: String) {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.extended_public_key_dialog, null)
        val drawable = Qr.themeAwareDrawable(xpubWithCreationDate, resources)
        val imageView = view.findViewById<ImageView>(R.id.extended_public_key_dialog_image)
        val xpubView = view.findViewById<TextView>(R.id.extended_public_key_dialog_xpub)
        imageView.setImageDrawable(drawable)
        xpubView.text = xpub

        xpubView.setOnClickListener {
            handleCopyAddress(xpub)
        }

        val baseAlertDialogBuilder = BaseAlertDialogBuilder(requireContext())
        baseAlertDialogBuilder.view = view
        baseAlertDialogBuilder.negativeText = getString(R.string.button_dismiss)
        baseAlertDialogBuilder.positiveText = getString(R.string.button_share)
        baseAlertDialogBuilder.positiveAction = {
            createAndLaunchShareIntent(xpubWithCreationDate)
            Unit
        }
        val alertDialog = baseAlertDialogBuilder.buildAlertDialog()
        alertDialog.show()
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

    private fun handleCopyAddress(xpub: String) {
        viewModel.copyXpubToClipboard()

        Toast(requireContext()).toast(R.string.copied)
        log.info("xpub copied to clipboard: {}", xpub)
    }
}
