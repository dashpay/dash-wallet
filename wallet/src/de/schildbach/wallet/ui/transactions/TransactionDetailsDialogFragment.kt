package de.schildbach.wallet.ui.transactions

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.ReportIssueDialogBuilder
import de.schildbach.wallet.ui.TransactionResultViewModel
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.transactions.PrivateMemoDialog
import org.dash.wallet.common.UserInteractionAwareCallback
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.TransactionDetailsDialogBinding
import de.schildbach.wallet_test.databinding.TransactionResultContentBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dashj.platform.dashpay.BlockchainIdentity
import org.slf4j.LoggerFactory

/**
 * @author Samuel Barbosa
 */
@FlowPreview
@AndroidEntryPoint
@ExperimentalCoroutinesApi
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

            val blockchainIdentity: BlockchainIdentity? = PlatformRepo.getInstance().getBlockchainIdentity()
            val userId = initializeIdentity(tx, blockchainIdentity)

            if (blockchainIdentity == null || userId == null) {
                finishInitialization(tx, null)
            }

            viewModel.transactionMetadata.observe(this) { metadata ->
                if(metadata != null && tx.txId == metadata.txId) {
                    transactionResultViewBinder.setTransactionMetadata(metadata)
                }
            }
        } else {
            log.error("Transaction not found. TxId:", txId)
            dismiss()
            return
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

    private fun initializeIdentity(tx: Transaction, blockchainIdentity: BlockchainIdentity?): String? {
        var profile: DashPayProfile?
        var userId: String? = null

        if (blockchainIdentity != null) {
            userId = blockchainIdentity.getContactForTransaction(tx)
            if (userId != null) {
                AppDatabase.getAppDatabase().dashPayProfileDaoAsync().loadByUserIdDistinct(userId).observe(this) {
                    if (it != null) {
                        profile = it
                        finishInitialization(tx, profile)
                    }
                }
            }
        }

        return userId
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