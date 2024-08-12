/*
 * Copyright 2022 Dash Core Group.
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
package de.schildbach.wallet.ui.transactions

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.ui.ReportIssueDialogBuilder
import de.schildbach.wallet.ui.TransactionResultViewModel
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.TransactionDetailsDialogBinding
import de.schildbach.wallet_test.databinding.TransactionResultContentBinding
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.Configuration
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
    private val viewModel: TransactionResultViewModel by viewModels()

    @Inject lateinit var configuration: Configuration
    @Inject lateinit var packageInfoProvider: PackageInfoProvider
    @Inject lateinit var walletApplication: WalletApplication

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
        val transactionResultViewBinder = TransactionResultViewBinder(
            viewModel.wallet!!,
            viewModel.dashFormat,
            contentBinding
        )

        viewModel.init(txId)
        val tx = viewModel.transaction

        if (tx != null) {
            transactionResultViewBinder.bind(tx)
        } else {
            log.error("Transaction not found. TxId: {}", txId)
            dismiss()
            return
        }

        viewModel.transactionMetadata.observe(this) { metadata ->
            transactionResultViewBinder.setTransactionMetadata(metadata)
        }

        viewModel.transactionIcon.observe(this) {
            transactionResultViewBinder.setTransactionIcon(it)
        }

        viewModel.merchantName.observe(this) {
            transactionResultViewBinder.setCustomTitle(getString(R.string.gift_card_tx_title, it))
        }

        contentBinding.openExplorerCard.setOnClickListener { viewOnBlockExplorer() }
        contentBinding.reportIssueCard.setOnClickListener {
            showReportIssue()
        }
        contentBinding.taxCategoryLayout.setOnClickListener {
            viewOnTaxCategory()
        }
        transactionResultViewBinder.setOnRescanTriggered { rescanBlockchain() }
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
        val tx = viewModel.transaction
        if (tx != null) {
            WalletUtils.viewOnBlockExplorer(activity, tx.purpose, tx.txId.toString())
        }
    }

    private fun viewOnTaxCategory() {
        // this should eventually trigger the observer to update the view
        viewModel.toggleTaxCategory()
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
