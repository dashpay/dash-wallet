/*
 * Copyright (c) 2023. Dash Core Group.
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
package de.schildbach.wallet.ui.transactions

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import de.schildbach.wallet.database.entity.DashPayProfile
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.service.platform.work.TopupIdentityWorker
import de.schildbach.wallet.ui.TransactionResultViewModel
import de.schildbach.wallet.ui.compose_views.ComposeBottomSheet
import de.schildbach.wallet.ui.dashpay.transactions.PrivateMemoDialog
import de.schildbach.wallet.ui.more.ContactSupportDialogFragment
import org.dash.wallet.common.UserInteractionAwareCallback
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.TransactionDetailsDialogBinding
import de.schildbach.wallet_test.databinding.TransactionResultContentBinding
import kotlinx.coroutines.flow.filterNotNull
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.Status
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.observe
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * @author Samuel Barbosa
 */
@AndroidEntryPoint
class TransactionDetailsDialogFragment : OffsetDialogFragment(R.layout.transaction_details_dialog) {

    private val log = LoggerFactory.getLogger(javaClass.simpleName)
    private val txId by lazy {
        if (arguments?.get(TX_ID) is Sha256Hash) {
            arguments?.get(TX_ID) as Sha256Hash
        } else {
            Sha256Hash.wrap(arguments?.get(TX_ID) as String)
        }
    }
    private val binding by viewBinding(TransactionDetailsDialogBinding::bind)
    private lateinit var contentBinding: TransactionResultContentBinding
    private lateinit var transactionResultViewBinder: TransactionResultViewBinder
    private val viewModel: TransactionResultViewModel by viewModels()

    @Inject lateinit var configuration: Configuration
    @Inject lateinit var packageInfoProvider: PackageInfoProvider
    @Inject lateinit var walletApplication: WalletApplication
    @Inject lateinit var dashPayProfileDao: DashPayProfileDao

    override val backgroundStyle = R.style.PrimaryBackground
    override val forceExpand = true

    companion object {

        const val TX_ID = "tx_id"

        @JvmStatic
        fun newInstance(txId: Sha256Hash? = null): TransactionDetailsDialogFragment {
            val fragment = TransactionDetailsDialogFragment()

            if (txId != null) {
                fragment.arguments = bundleOf(TX_ID to txId)
            }
            return fragment
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        contentBinding = TransactionResultContentBinding.bind(binding.transactionResultContainer)
        transactionResultViewBinder = TransactionResultViewBinder(
            viewModel.wallet!!,
            viewModel.dashFormat,
            contentBinding
        )

        viewModel.init(txId)
        viewModel.transaction.filterNotNull().observe(viewLifecycleOwner) { tx ->
            // the transactionResultViewBinder.bind is called later

            viewModel.transactionIcon.observe(this) {
                transactionResultViewBinder.setTransactionIcon(it)
            }

            viewModel.merchantName.observe(this) {
                transactionResultViewBinder.setCustomTitle(getString(R.string.gift_card_tx_title, it))
            }

            viewModel.transactionMetadata.observe(this) { metadata ->
                if (metadata != null && tx.txId == metadata.txId) {
                    transactionResultViewBinder.setTransactionMetadata(metadata)
                }
            }

            viewModel.contact.observe(this) { profile ->
                finishInitialization(tx, profile)
            }
            transactionResultViewBinder.setOnRescanTriggered { rescanBlockchain() }
        }

        transactionResultViewBinder.setOnRescanTriggered { rescanBlockchain() }

        viewModel.topUpWork(txId).observe(this) { workData ->
            log.info("topup work data: {}", workData)
            try {
                val txIdString = workData.data?.outputData?.getString(TopupIdentityWorker.KEY_TOPUP_TX)
                log.info("txId from work matches viewModel: {} ==? {}", txIdString, txId)

                when (workData.status) {
                    Status.LOADING -> {
                        log.info("  loading: {}", workData.data?.outputData)
                    }

                    Status.SUCCESS -> {
                        log.info("  success: {}", workData.data?.outputData)
                    }

                    Status.ERROR -> {
                        log.info("  error: {}", workData.data?.outputData)
                        viewModel.topUpError = true
                        transactionResultViewBinder.setSentToReturn(viewModel.topUpError, viewModel.topUpComplete)
                    }

                    Status.CANCELED -> {
                        log.info("  cancel: {}", workData.data?.outputData)
                    }
                }
            } catch (e: Exception) {
                log.error("error processing topup information", e)
            }
        }

        viewModel.topUpStatus(txId).observe(this) { topUp ->
            viewModel.topUpComplete = topUp?.used() == true
            transactionResultViewBinder.setSentToReturn(viewModel.topUpError, viewModel.topUpComplete)
        }
    }

    private fun finishInitialization(tx: Transaction, dashPayProfile: DashPayProfile?) {
        initiateTransactionBinder(tx, dashPayProfile)
        val mainThreadExecutor = ContextCompat.getMainExecutor(walletApplication)
        tx.confidence.addEventListener(mainThreadExecutor, transactionResultViewBinder)
    }

    private fun initiateTransactionBinder(tx: Transaction, dashPayProfile: DashPayProfile?) {
        contentBinding = TransactionResultContentBinding.bind(binding.transactionResultContainer)
        transactionResultViewBinder.bind(tx, dashPayProfile)
        contentBinding.openExplorerCard.setOnClickListener { viewOnBlockExplorer() }
        contentBinding.reportIssueCard.setOnClickListener { showReportIssue() }
        contentBinding.taxCategoryLayout.setOnClickListener { viewOnTaxCategory() }
        contentBinding.addPrivateMemoBtn.setOnClickListener {
            viewModel.transaction.value?.txId?.let { hash ->
                PrivateMemoDialog().apply {
                    arguments = bundleOf(PrivateMemoDialog.TX_ID_ARG to hash)
                }.show(requireActivity().supportFragmentManager, "private_memo")
            }
        }
        dialog?.window!!.callback = UserInteractionAwareCallback(dialog?.window!!.callback, requireActivity())
    }

    private fun showReportIssue() {
        ContactSupportDialogFragment.newInstance(
            getString(R.string.report_issue_dialog_title_issue),
            getString(R.string.report_issue_dialog_message_issue),
            contextualData = viewModel.transaction.toString()
        ).show(requireActivity())
    }

    private fun viewOnBlockExplorer() {
        imitateUserInteraction()
        val tx = viewModel.transaction.value
        if (tx != null) {
            ComposeBottomSheet(R.style.PrimaryBackground) { dialog ->
                BlockExplorerSelectionView { explorer ->
                    WalletUtils.viewOnBlockExplorer(requireActivity(), tx.purpose, tx.txId.toString())// TODO:, explorer.url)
                    dialog.dismiss()
                }
            }.show(requireActivity())
        }
    }

    private fun viewOnTaxCategory() {
        // this should eventually trigger the observer to update the view
        viewModel.toggleTaxCategory()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        viewModel.transaction.value?.confidence?.removeEventListener(transactionResultViewBinder)
    }

    private fun imitateUserInteraction() {
        requireActivity().onUserInteraction()
    }

    private fun rescanBlockchain() {
        AdaptiveDialog.create(
            null,
            getString(R.string.preferences_initiate_reset_title),
            getString(R.string.preferences_initiate_reset_dialog_message),
            getString(R.string.button_cancel),
            getString(R.string.preferences_initiate_reset_dialog_positive)
        ).show(requireActivity()) {
            if (it == true) {
                log.info("manually initiated blockchain reset")
                viewModel.rescanBlockchain()
                dismiss()
            } else {
                viewModel.logEvent(AnalyticsConstants.Settings.RESCAN_BLOCKCHAIN_DISMISS)
            }
        }
    }
}
