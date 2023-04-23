package de.schildbach.wallet.ui.transactions

import android.content.DialogInterface
import android.os.Bundle
import android.view.View
import de.schildbach.wallet.database.entity.DashPayProfile
import androidx.core.os.bundleOf
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.database.dao.DashPayProfileDaoAsync
import de.schildbach.wallet.service.PackageInfoProvider
import de.schildbach.wallet.ui.ReportIssueDialogBuilder
import de.schildbach.wallet.ui.TransactionResultViewModel
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.transactions.PrivateMemoDialog
import org.dash.wallet.common.UserInteractionAwareCallback
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.TransactionDetailsDialogBinding
import de.schildbach.wallet_test.databinding.TransactionResultContentBinding
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dashj.platform.dashpay.BlockchainIdentity
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
    @Inject lateinit var dashPayProfileDaoAsync: DashPayProfileDaoAsync

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
                dashPayProfileDaoAsync.loadByUserIdDistinct(userId).observe(this) {
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
            packageInfoProvider,
            configuration,
            viewModel.walletData.wallet
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
}
