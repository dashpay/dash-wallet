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
import org.dash.wallet.common.money.Coin
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.slf4j.LoggerFactory
import de.schildbach.wallet.util.toNeutralCoin

class BuyCreditsFragment : SendCoinsFragment() {
    companion object {
        private val log = LoggerFactory.getLogger(BuyCreditsFragment::class.java)

        /** The smallest top-up this screen accepts (exclusive bound). */
        private val MIN_TOP_UP = Coin.valueOf(50_000)
    }

    private val buyCreditsViewModel by viewModels<BuyCreditsViewModel>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.paymentHeader.setTitle(getString(R.string.credit_balance_button_buy))
        enterAmountViewModel.setMinAmount(MIN_TOP_UP)
        binding.paymentHeader.setPreposition("")
    }

    override fun updateView() {
        val isReplaying = viewModel.isBlockchainReplaying.value

        if (isReplaying != true && viewModel.isInsufficientFunds) {
            val errorMessage = getErrorMessage(R.string.credit_balance_insufficient_error_message)
            enterAmountFragment?.setError(errorMessage)
            return
        }

        // Below the top-up minimum the Continue button greys out; say WHY
        // instead of leaving the user guessing (the amount must exceed the
        // minimum — the button enables above it, not at it).
        val entered = enterAmountViewModel.amount.value
        if (entered != null && entered.isPositive && entered.isLessThanOrEqualTo(MIN_TOP_UP)) {
            enterAmountFragment?.setError(
                getString(R.string.buy_credits_below_minimum, MIN_TOP_UP.toFriendlyString())
            )
            return
        }
        enterAmountFragment?.setError("")

        // if there is no value (null) or it is zero, then display the message in the
        // enter amount fragment using 0.01 DASH
        val amount = entered ?: Coin.CENT
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
        // The dialog reads the amount and rate it displays from the shared
        // SendCoinsViewModel and its own ViewModel, so nothing is computed or
        // passed here. (The dashj dry-run figures this method used to derive —
        // fee, total, send-max amount — were never read by anything, and the
        // null `tx.fee` post-cutover made deriving them a crash risk.)
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
    /**
     * Whether [amount] is a MAX ("spend everything") purchase — the whole
     * spendable balance, keyed off the SDK-overlaid balance (dashj's is held
     * at 0 post-cutover). Same rule the shielded Internal Transfer screen
     * uses (`amount == availableBalance` in ShieldedTransferViewModel).
     */
    private fun isMaxSpend(amount: Coin): Boolean {
        val available = viewModel.maxOutputAmount.value?.toNeutralCoin() ?: return false
        return available.isPositive && amount.isGreaterThanOrEqualTo(available)
    }

    private suspend fun handleGo() {
        val editedAmount = enterAmountViewModel.amount.value ?: return
        // MAX follows the shielded Internal Transfer pattern: submit the FULL
        // balance and let the worker make ONE fee-adjusted retry when the
        // asset-lock coin selection comes up short pre-broadcast (the exact
        // L1 fee is unknowable app-side). See PerformTopUpWorker.
        val maxSpend = enterAmountFragment?.maxSelected == true || isMaxSpend(editedAmount)
        handleSdkTopUp(editedAmount.value, maxSpend)
        viewModel.resetState()
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
    private suspend fun handleSdkTopUp(amountDuffs: Long, isMaxSpend: Boolean) {
        // PRE-FLIGHT funding eligibility: the asset-lock build only selects
        // FINAL (confirmed/IS-locked) BIP44 coins — refuse HERE, before the
        // spend attempt, when a display balance backed by non-final or
        // out-of-account outputs cannot fund the lock (fail-open on any
        // preflight hiccup; the real build stays authoritative). A MAX spend
        // is preflighted on its fee-adjusted retry amount — the full balance
        // can never clear the preflight's fee headroom by definition.
        if (!buyCreditsViewModel.canFundTopUp(amountDuffs, isMaxSpend)) {
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
        buyCreditsViewModel.startTopUp(amountDuffs, isMaxSpend)
        observeTopUpWork()
    }

    private fun observeTopUpWork() {
        buyCreditsViewModel.topUpWorkStatus().observe(viewLifecycleOwner) { infos ->
            val work = infos.lastOrNull() ?: return@observe
            when (work.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                    // Progress circle on the Send button for as long as the
                    // purchase is actually running — the screen must stay
                    // busy until the work reaches a terminal state, because
                    // a FAILURE must be shown HERE (closing at the SDK
                    // hand-off was tried and reverted: it silenced every
                    // post-hand-off failure dialog, e.g. a MAX retry refused
                    // below the Platform floor). A MAX purchase waiting for
                    // a chain-locked block legitimately holds this spinner
                    // for minutes; the purchase itself survives the screen
                    // (unique work + recovery worker) if the user backs out.
                    enterAmountFragment?.setContinueLoading(true)
                }
                WorkInfo.State.SUCCEEDED -> {
                    // Deliberately do NOT clear the loading state: the screen
                    // is about to finish, and re-enabling the button first
                    // leaves a brief window where it looks tappable again.
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