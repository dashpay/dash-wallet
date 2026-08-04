package de.schildbach.wallet.ui.send

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import de.schildbach.wallet.data.CreditBalanceInfo
import androidx.work.WorkInfo
import de.schildbach.wallet.service.platform.work.PerformTopUpOperation
import de.schildbach.wallet.service.platform.work.PerformTopUpWorker
import de.schildbach.wallet.service.work.BaseWorker
import de.schildbach.wallet.ui.more.tools.ConfirmTopUpDialogFragment
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.money.MonetaryFormat
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.slf4j.LoggerFactory
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.toDashjCoin

class BuyCreditsFragment : SendCoinsFragment() {
    companion object {
        private val log = LoggerFactory.getLogger(BuyCreditsFragment::class.java)
    }

    private val buyCreditsViewModel by viewModels<BuyCreditsViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.paymentHeader.setTitle(getString(R.string.credit_balance_button_buy))
        enterAmountViewModel.setMinAmount(org.dash.wallet.common.money.Coin.valueOf(50_000))
        binding.paymentHeader.setPreposition("")
    }

    override fun updateView() {
        val isReplaying = viewModel.isBlockchainReplaying.value
        val dryRunException = viewModel.dryRunException

        if (isReplaying != true && dryRunException != null) {
            when (dryRunException) {
                is InsufficientMoneyException -> {
                    val errorMessage = getErrorMessage(R.string.credit_balance_insufficient_error_message)
                    enterAmountFragment?.setError(errorMessage)
                    return
                }
                else -> {}
            }
        }

        // if there is no value (null) or it is zero, then display the message in the
        // enter amount fragment using 0.01 DASH
        val amount = enterAmountViewModel.amount.value?.toDashjCoin() ?: Coin.CENT
        val operations = if (amount.isZero) {
            Coin.CENT.value
        } else {
            amount.value
        } / CreditBalanceInfo.MAX_OPERATION_COST_COIN
        enterAmountFragment?.setMessage(
            getString(R.string.buy_credits_estimated_items,
                if (amount.isZero) { Coin.CENT } else { amount }.toFriendlyString(),
                operations,
                operations
            )
        )

        super.updateView()
    }

    override suspend fun showPaymentConfirmation() {
        val dryRunRequest = viewModel.dryrunSendRequest ?: return
        //val address = viewModel.basePaymentIntent.address?.toBase58() ?: return

        val txFee = dryRunRequest.tx.fee
        val amount: Coin?
        val total: String?

        if (dryRunRequest.emptyWallet) {
            amount = enterAmountViewModel.amount.value?.toDashjCoin()?.minus(txFee)
            total = enterAmountViewModel.amount.value?.toPlainString()
        } else {
            amount = enterAmountViewModel.amount.value?.toDashjCoin()
            total = amount?.add(txFee ?: Coin.ZERO)?.toPlainString()
        }

        val rate = enterAmountViewModel.selectedExchangeRate.value
        val exchangeRate = rate?.let {
            org.dash.wallet.common.money.ExchangeRate(org.dash.wallet.common.money.Coin.COIN, rate.fiat)
        }
        val amountStr = amount?.let { MonetaryFormat.BTC.noCode().format(it).toString() } ?: ""
        val fee = txFee?.toPlainString() ?: ""

        //var dashPayProfile: DashPayProfile? = null

//        if (viewModel.contactData.value?.requestReceived == true) {
//            dashPayProfile = viewModel.contactData.value?.dashPayProfile
//        }
//
//        val isPendingContactRequest = viewModel.contactData.value?.isPendingRequest == true
//        val username = dashPayProfile?.username
//        val displayName = (dashPayProfile?.displayName ?: "").ifEmpty { username }
//        val avatarUrl = dashPayProfile?.avatarUrl

        // need to put the conformation for used with Create UserName
        val dialog = ConfirmTopUpDialogFragment()
        dialog.show(requireActivity()) { confirmed ->
            if (confirmed) {
                lifecycleScope.launch {
                    handleGo()
                }
            }
        }
    }

    /**
     * Phase 2/3 (MO-998): SDK-only — the dashj purchase path
     * (signAndSendAssetLock + TopupIdentityWorker) is deleted. Pre-cutover,
     * [de.schildbach.wallet.service.platform.sdk.SdkTransparentTopUp]'s
     * fail-closed gate refuses with NotBroadcast and nothing is spent.
     */
    private suspend fun handleGo() {
        val editedAmount = enterAmountViewModel.amount.value
        if (editedAmount != null) {
            handleSdkTopUp(editedAmount.toDashjCoin().value)
            viewModel.resetState()
        }
    }

    /**
     * The purchase runs as UNIQUE background work ([PerformTopUpWorker] via
     * the ViewModel) so a lock screen / rotation / process death cannot
     * cancel it mid-flight; this screen only OBSERVES the work. Success →
     * finish (the credits UI shows the new balance on return); failure with
     * nothing spent → standard error dialog, retry-safe; unconfirmed →
     * the recovery worker completes any tracked lock in the background and
     * the user is told NOT to retry.
     */
    private suspend fun handleSdkTopUp(amountDuffs: Long) {
        // PRE-FLIGHT funding eligibility: the asset-lock build only selects
        // FINAL (confirmed/IS-locked) BIP44 coins — refuse HERE, before the
        // spend attempt, when a display balance backed by non-final or
        // out-of-account outputs cannot fund the lock (fail-open on any
        // preflight hiccup; the real build stays authoritative).
        if (!buyCreditsViewModel.canFundTopUp(amountDuffs)) {
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(R.string.credit_balance_button_buy),
                getString(
                    R.string.buy_credits_funds_settling,
                    Coin.valueOf(amountDuffs).toFriendlyString()
                ),
                getString(R.string.button_dismiss),
                null
            ).showAsync(requireActivity())
            return
        }
        buyCreditsViewModel.startTopUp(amountDuffs)
        observeTopUpWork()
    }

    private fun observeTopUpWork() {
        buyCreditsViewModel.topUpWorkStatus().observe(viewLifecycleOwner) { infos ->
            val work = infos.lastOrNull() ?: return@observe
            when (work.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                    // Progress circle on the Send button ONLY until the worker
                    // has started and handed the purchase to the SDK — from
                    // that marker on, the outcome no longer needs this screen.
                    val sdkCallStarted =
                        work.progress.getBoolean(PerformTopUpWorker.KEY_SDK_CALL_STARTED, false)
                    enterAmountFragment?.setContinueLoading(!sdkCallStarted)
                }
                WorkInfo.State.SUCCEEDED -> {
                    enterAmountFragment?.setContinueLoading(false)
                    buyCreditsViewModel.pruneTopUpWork()
                    log.info(
                        "SDK top-up credited; new balance {}",
                        work.outputData.getLong(PerformTopUpWorker.KEY_NEW_BALANCE, -1)
                    )
                    onSdkTopUpSuccess()
                }
                WorkInfo.State.FAILED -> {
                    enterAmountFragment?.setContinueLoading(false)
                    buyCreditsViewModel.pruneTopUpWork()
                    val ambiguous = work.outputData.getBoolean(PerformTopUpWorker.KEY_AMBIGUOUS, false)
                    val reason = work.outputData.getString(BaseWorker.KEY_ERROR_MESSAGE) ?: "top-up failed"
                    log.warn("SDK top-up failed (ambiguous={}): {}", ambiguous, reason)
                    lifecycleScope.launch {
                        if (ambiguous) showSdkTopUpAmbiguousDialog() else showFailureDialog(Exception(reason))
                    }
                }
                WorkInfo.State.CANCELLED -> enterAmountFragment?.setContinueLoading(false)
            }
        }
    }

    private fun onSdkTopUpSuccess() {
        // The SDK fuses the asset-lock build with the Platform top-up
        // registration, so there is no dashj funding tx to return to a calling
        // activity or to show on the TransactionResult screen. Buy Credits is
        // always launched via startActivity (no result expected).
        playSentSound()
        requireActivity().finish()
    }

    private suspend fun showSdkTopUpAmbiguousDialog() {
        if (!isAdded) {
            return
        }
        AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.credit_balance_button_buy),
            getString(R.string.buy_credits_topup_unconfirmed),
            getString(R.string.button_dismiss),
            null
        ).showAsync(requireActivity())
    }
}