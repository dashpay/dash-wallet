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
import de.schildbach.wallet.ui.TransactionResultViewModel
import de.schildbach.wallet.ui.compose_views.ComposeBottomSheet
import de.schildbach.wallet.ui.dashpay.transactions.PrivateMemoDialog
import de.schildbach.wallet.ui.dashpay.user.DashPayUserBottomSheet
import de.schildbach.wallet.ui.more.ContactSupportDialogFragment
import de.schildbach.wallet.ui.util.viewOnBlockExplorer
import de.schildbach.wallet.util.toTxId
import org.dash.wallet.common.UserInteractionAwareCallback
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.TransactionDetailsDialogBinding
import de.schildbach.wallet_test.databinding.TransactionResultContentBinding
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.Status
import org.dash.wallet.common.data.entity.SwapOrder
import org.dash.wallet.common.util.openCustomTab
import org.dash.wallet.integrations.maya.ui.MayaConvertResultStateMapper
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
        ) {
            DashPayUserBottomSheet.newInstance(it).show(requireActivity())
            dismissAllowingStateLoss()
        }

        viewModel.init(txId)
        viewModel.transaction.filterNotNull().observe(viewLifecycleOwner) { tx ->
            // the transactionResultViewBinder.bind is called later

            viewModel.transactionIcon.observe(this) {
                transactionResultViewBinder.setTransactionIcon(it)
            }

            viewModel.merchantName.observe(this) {
                transactionResultViewBinder.setCustomTitle(getString(R.string.gift_card_tx_title, it))
                transactionResultViewBinder.setMerchantName(it)
            }

            viewModel.swapOrder.observe(this) { swapOrder ->
                if (swapOrder != null) {
                    transactionResultViewBinder.setTransactionIcon(R.drawable.ic_convert_circle)
                    showProviderExplorer(swapOrder)
                }
            }

            viewModel.transactionMetadata.observe(this) { metadata ->
                // Compare via the neutral TxId. Step A changed
                // TransactionMetadata.txId from dashj Sha256Hash to the neutral
                // TxId, so the old `tx.txId == metadata.txId` compares two
                // unrelated types and is ALWAYS false — which silently stopped
                // setTransactionMetadata from ever running (tax category stuck
                // on "Loading"). toTxId() puts both sides in the same type.
                if (metadata != null && tx.txId.toTxId() == metadata.txId) {
                    transactionResultViewBinder.setTransactionMetadata(metadata)
                }
            }

            viewModel.contact.observe(this) { profile ->
                finishInitialization(tx, profile)
            }
            transactionResultViewBinder.setOnRescanTriggered { rescanBlockchain() }
        }

        transactionResultViewBinder.setOnRescanTriggered { rescanBlockchain() }

        // Step B1 fallback: the transaction is NOT in the dashj wallet
        // (post-cutover SDK-only tx) — bind the neutral SDK-sourced detail
        // instead of leaving the sheet blank. Metadata (tax category,
        // private memo, gift-card icon) is txid-keyed and works unchanged.
        viewModel.sdkTxDetail.filterNotNull().observe(viewLifecycleOwner) { detail ->
            transactionResultViewBinder.bindSdkDetail(detail)

            // SDK top-up: swap the OP_RETURN row's pending label for
            // "Platform credits" once the credits have landed.
            lifecycleScope.launch {
                viewModel.sdkTopUpCredited(txId)?.let { credited ->
                    transactionResultViewBinder.setSdkTopUpState(error = false, completed = credited)
                }
            }

            viewModel.transactionIcon.observe(this) {
                transactionResultViewBinder.setTransactionIcon(it)
            }
            viewModel.merchantName.observe(this) {
                transactionResultViewBinder.setCustomTitle(getString(R.string.gift_card_tx_title, it))
            }
            viewModel.transactionMetadata.observe(this) { metadata ->
                if (metadata != null && metadata.txId.toString().equals(detail.txIdDisplayHex, ignoreCase = true)) {
                    transactionResultViewBinder.setTransactionMetadata(metadata)
                }
            }

            contentBinding.openExplorerCard.setOnClickListener { viewOnBlockExplorer() }
            contentBinding.reportIssueCard.setOnClickListener { showReportIssue() }
            contentBinding.taxCategoryLayout.setOnClickListener { viewOnTaxCategory() }
            contentBinding.addPrivateMemoBtn.setOnClickListener {
                PrivateMemoDialog().apply {
                    arguments = bundleOf(PrivateMemoDialog.TX_ID_ARG to txId)
                }.show(requireActivity().supportFragmentManager, "private_memo")
            }
            dialog?.window!!.callback = UserInteractionAwareCallback(dialog?.window!!.callback, requireActivity())
        }


        viewModel.topUpStatus(txId).observe(this) { topUp ->
            lifecycleScope.launch {
                // Legacy top-ups have a `topups` row; SDK top-ups don't —
                // their credited state comes from the SDK's recovery queue
                // (lock still queued = pending, gone = credited).
                viewModel.topUpComplete = topUp?.used() == true ||
                    (topUp == null && viewModel.sdkTopUpCredited(txId) == true)
                viewModel.transaction.value?.let {
                    transactionResultViewBinder.setSentToReturn(
                        it.versionShort,
                        it.type,
                        viewModel.topUpError,
                        viewModel.topUpComplete
                    )
                }
            }
        }
    }

    private fun finishInitialization(tx: Transaction, dashPayProfile: DashPayProfile?) {
        initiateTransactionBinder(tx, dashPayProfile)
        val mainThreadExecutor = ContextCompat.getMainExecutor(walletApplication)
        tx.confidence.addEventListener(mainThreadExecutor, transactionResultViewBinder)
    }

    private fun initiateTransactionBinder(tx: Transaction, dashPayProfile: DashPayProfile?) {
        contentBinding = TransactionResultContentBinding.bind(binding.transactionResultContainer)
        // Bug A: pass the SDK direction/amount override (non-null only post-cutover,
        // when the held dashj wallet misreads an SDK-authored send).
        transactionResultViewBinder.bind(
            tx, dashPayProfile, sdkOverride = viewModel.sdkDirectionOverride.value
        )
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

    /**
     * Swap transactions get a second explorer button, in the same format as "View on
     * block explorer", linking to the swap provider's tracker (MayaChain scan or the
     * NEAR Intents explorer, picked by the order's route).
     */
    private fun showProviderExplorer(swapOrder: SwapOrder) {
        val spec = MayaConvertResultStateMapper.explorerFor(
            swapOrder.provider,
            swapOrder.txId.toString(),
            swapOrder.depositAddress,
            // Only the native Maya backend persists orders without a route name; a
            // provider-less SwapKit order gets no link rather than a guessed one.
            emptyRouteIsMaya = swapOrder.service == ServiceName.Maya
        ) ?: return

        contentBinding.viewOnProviderExplorer.setText(spec.linkTextRes)
        contentBinding.openProviderExplorerCard.isVisible = true
        contentBinding.openProviderExplorerCard.setOnClickListener {
            imitateUserInteraction()
            val url = spec.urlArg?.let { getString(spec.urlRes, it) } ?: getString(spec.urlRes)
            requireActivity().openCustomTab(url)
        }
    }

    private fun viewOnBlockExplorer() {
        imitateUserInteraction()
        // The txid argument serves both sources (dashj tx or SDK-only detail).
        if (viewModel.transaction.value != null || viewModel.sdkTxDetail.value != null) {
            ComposeBottomSheet(R.style.PrimaryBackground) { dialog ->
                BlockExplorerSelectionView(viewModel.analytics) { explorer ->
                    requireActivity().viewOnBlockExplorer(explorer, "tx/$txId")
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
