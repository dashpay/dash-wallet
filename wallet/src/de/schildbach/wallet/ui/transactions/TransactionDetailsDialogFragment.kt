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
import de.schildbach.wallet.database.entity.DashPayProfile
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.ui.ReportIssueDialogBuilder
import de.schildbach.wallet.ui.TransactionResultViewModel
import de.schildbach.wallet.ui.dashpay.transactions.PrivateMemoDialog
import org.dash.wallet.common.UserInteractionAwareCallback
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.TransactionDetailsDialogBinding
import de.schildbach.wallet_test.databinding.TransactionResultContentBinding
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
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
        val tx = viewModel.transaction

        // the transactionResultViewBinder.bind is called later
        if (tx == null) {
            log.error("Transaction not found. TxId: {}", txId)
            dismiss()
            return
        }

        viewModel.transactionIcon.observe(this) {
            transactionResultViewBinder.setTransactionIcon(it)
        }

        viewModel.merchantName.observe(this) {
            transactionResultViewBinder.setCustomTitle(getString(R.string.gift_card_tx_title, it))
        }

        viewModel.transactionMetadata.observe(this) { metadata ->
            if(metadata != null && tx.txId == metadata.txId) {
                transactionResultViewBinder.setTransactionMetadata(metadata)
            }
        }

        viewModel.contact.observe(this) { profile ->
            finishInitialization(tx, profile)
        }
        transactionResultViewBinder.setOnRescanTriggered { rescanBlockchain() }
    }

    private fun finishInitialization(tx: Transaction, dashPayProfile: DashPayProfile?) {
        initiateTransactionBinder(tx, dashPayProfile)
        tx.confidence.addEventListener(transactionResultViewBinder)
    }

    private fun initiateTransactionBinder(tx: Transaction, dashPayProfile: DashPayProfile?) {
        contentBinding = TransactionResultContentBinding.bind(binding.transactionResultContainer)
        transactionResultViewBinder.bind(tx, dashPayProfile)
        contentBinding.viewOnExplorer.setOnClickListener { viewOnBlockExplorer() }
        contentBinding.reportIssueCard.setOnClickListener { showReportIssue() }
        contentBinding.taxCategoryLayout.setOnClickListener { viewOnTaxCategory() }
        contentBinding.addPrivateMemoBtn.setOnClickListener {
            viewModel.transaction?.txId?.let { hash ->
                PrivateMemoDialog().apply {
                    arguments = bundleOf(PrivateMemoDialog.TX_ID_ARG to hash)
                }.show(requireActivity().supportFragmentManager, "private_memo")
            }
        }
        dialog?.window!!.callback = UserInteractionAwareCallback(dialog?.window!!.callback, requireActivity())
    }

    private fun showReportIssue() {
        ReportIssueDialogBuilder.createReportIssueDialog(
            requireActivity(),
            packageInfoProvider,
            configuration,
            viewModel.walletData.wallet,
            walletApplication
        ).buildAlertDialog().show()
    }

    private fun viewOnBlockExplorer() {
        imitateUserInteraction()
        val tx = viewModel.transaction
        if (tx != null) {
            WalletUtils.viewOnBlockExplorer(activity, tx.purpose, tx.txId.toString())
        }
    }

    private fun viewOnTaxCategory() {
        // this should eventually trigger the observer to update the view
        viewModel.toggleTaxCategory()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        viewModel.transaction?.confidence?.removeEventListener(transactionResultViewBinder)
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
