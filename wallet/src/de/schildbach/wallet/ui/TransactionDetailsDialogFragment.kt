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
package de.schildbach.wallet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.TransactionDetailsDialogBinding
import de.schildbach.wallet_test.databinding.TransactionResultContentBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.slf4j.LoggerFactory
import javax.inject.Inject

/**
 * @author Samuel Barbosa
 */
@AndroidEntryPoint
@ExperimentalCoroutinesApi
class TransactionDetailsDialogFragment : OffsetDialogFragment() {

    private val log = LoggerFactory.getLogger(javaClass.simpleName)
    private val txId by lazy { arguments?.get(TX_ID) as Sha256Hash }
    private var tx: Transaction? = null
    private val binding by viewBinding(TransactionDetailsDialogBinding::bind)
    private lateinit var contentBinding: TransactionResultContentBinding

    override val backgroundStyle = R.style.PrimaryBackground
    override val forceExpand = true

    @Inject
    lateinit var walletData: WalletDataProvider

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
        tx = walletData.wallet!!.getTransaction(txId)
        contentBinding = TransactionResultContentBinding.bind(binding.transactionResultContainer)
        val transactionResultViewBinder = TransactionResultViewBinder(binding.transactionResultContainer)

        if (tx != null) {
            transactionResultViewBinder.bind(tx!!)
        } else {
            log.error("Transaction not found. TxId:", txId)
            dismiss()
            return
        }

        contentBinding.openExplorerCard.setOnClickListener { viewOnBlockExplorer() }
        contentBinding.reportIssueCard.setOnClickListener {
            showReportIssue()
        }
    }

    private fun showReportIssue() {
        ReportIssueDialogBuilder.createReportIssueDialog(requireActivity(), WalletApplication.getInstance())
            .buildAlertDialog().show()
    }

    private fun viewOnBlockExplorer() {
        if (tx != null) {
            WalletUtils.viewOnBlockExplorer(activity, tx!!.purpose, tx!!.txId.toString())
        }
    }

}