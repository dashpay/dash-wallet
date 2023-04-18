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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
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
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.slf4j.LoggerFactory

/**
 * @author Samuel Barbosa
 */
@AndroidEntryPoint
class TransactionDetailsDialogFragment : OffsetDialogFragment() {
    private val log = LoggerFactory.getLogger(javaClass.simpleName)
    private val txId by lazy { arguments?.get(TX_ID) as Sha256Hash }
    private val binding by viewBinding(TransactionDetailsDialogBinding::bind)
    private lateinit var contentBinding: TransactionResultContentBinding
    private lateinit var transactionResultViewBinder: TransactionResultViewBinder
    private val viewModel: TransactionResultViewModel by viewModels()

    override val backgroundStyle = R.style.PrimaryBackground
    override val forceExpand = true

    companion object {

        const val TX_ID = "tx_id"

        @JvmStatic
        fun newInstance(txId: Sha256Hash): TransactionDetailsDialogFragment {
            val fragment = TransactionDetailsDialogFragment()
            val args = Bundle()
            args.putSerializable(TX_ID, txId)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.transaction_details_dialog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.init(txId)
        val tx = viewModel.transaction

        if (tx != null) {
            transactionResultViewBinder = TransactionResultViewBinder(
                viewModel.wallet!!,
                viewModel.dashFormat,
                binding.transactionResultContainer
            )
        } else {
            log.error("Transaction not found. TxId: {}", txId)
            dismiss()
            return
        }

        viewModel.transactionMetadata.observe(this) { metadata ->
            if(metadata != null && tx.txId == metadata.txId) {
                transactionResultViewBinder.setTransactionMetadata(metadata)
            }
        }

        viewModel.contact.observe(this) { profile ->
            finishInitialization(tx, profile)
        }
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
            WalletApplication.getInstance()
        )
            .buildAlertDialog().show()
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
}
