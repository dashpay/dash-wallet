package de.schildbach.wallet.ui.send

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.drop
import de.schildbach.wallet.data.CreditBalanceInfo
import de.schildbach.wallet.integration.android.BitcoinIntegration
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.ui.more.tools.ConfirmTopUpDialogFragment
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.utils.ExchangeRate
import org.dash.wallet.common.money.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.MinimumBalanceDialog
import org.slf4j.LoggerFactory
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.setAmount
import de.schildbach.wallet.util.setFiatAmount
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toDashjCoin
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import de.schildbach.wallet.util.toTxId
import de.schildbach.wallet.util.toSha256Hash

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
        viewModel.isAssetLock = true

        // Show what the identity already holds, under the amount field.
        //
        // viewLifecycleOwner + repeatOnLifecycle(STARTED), and drop(1): a
        // StateFlow replays its CURRENT value synchronously on subscribe, so
        // collecting here without dropping re-entered updateView() from inside
        // onViewCreated — before the child EnterAmountFragment's view existed —
        // and setMessage() crashed on its view LifecycleOwner. The replayed
        // value is always the initial null anyway (the balance loads async), so
        // dropping it costs nothing; the real value arrives later, once STARTED.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                buyCreditsViewModel.identityBalance.drop(1).collect {
                    // The child fragment's view can still be down (config change,
                    // backgrounded): setMessage() would touch a dead binding.
                    if (enterAmountFragment?.view != null) {
                        updateView()
                    }
                }
            }
        }
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
        val estimate = getString(
            R.string.buy_credits_estimated_items,
            if (amount.isZero) { Coin.CENT } else { amount }.toFriendlyString(),
            operations,
            operations
        )
        // Append the current identity balance when it is known. Hidden entirely
        // when unknown rather than shown as zero, which would read as "you have
        // no credits" on a balance we simply could not fetch.
        val balance = buyCreditsViewModel.identityBalance.value
        enterAmountFragment?.setMessage(
            if (balance != null) {
                estimate + "\n" + getString(
                    R.string.buy_credits_current_identity_balance,
                    balance.toFriendlyString()
                )
            } else {
                estimate
            }
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
                    handleGo(true)
                }
            }
        }
    }

    private suspend fun handleGo(checkBalance: Boolean) {
        if (viewModel.dryrunSendRequest == null) {
            log.error("illegal state dryrunSendRequest == null")
            return
        }

        val editedAmount = enterAmountViewModel.amount.value
        val rate = enterAmountViewModel.selectedExchangeRate.value

        if (editedAmount != null) {
            // Post-cutover the dashj L1 engine is HELD (0 UTXOs), so building
            // the top-up asset lock with dashj fails InsufficientMoneyException
            // — the funds live in the SDK. Route top-up funding through the
            // SDK's fused topUpFromCore (resume-gated) instead of the dashj
            // asset-lock + TopupIdentityWorker chain. There is NO dashj tx/txid,
            // so the SDK outcome is observed directly (no TransactionResult
            // screen). Pre-cutover this branch is skipped and the dashj path
            // below is byte-for-byte unchanged.
            if (buyCreditsViewModel.isCutoverCommitted()) {
                handleSdkTopUp(editedAmount.toDashjCoin().value)
                viewModel.resetState()
                return
            }

            val exchangeRate = rate?.fiat?.let { ExchangeRate(Coin.COIN, it.toDashjFiat()) }

            try {
                // TODO: there are no events for Topups
                // viewModel.logEvent(AnalyticsConstants.Topup.ENTER_AMOUNT_TOPUP)

                val maxSelected = enterAmountFragment?.maxSelected ?: false
                if (maxSelected) {
                    viewModel.logEvent(AnalyticsConstants.SendReceive.ENTER_AMOUNT_MAX)
                }
                // The topup key is issued inside signAndSendAssetLock's dashj
                // branch only — the SDK route derives its own key, and issuing
                // one here would burn an unused dashj chain index per SDK top-up.
                val tx = viewModel.signAndSendAssetLock(editedAmount.toDashjCoin(), exchangeRate, checkBalance, maxSelected)
                buyCreditsViewModel.topUpTransaction = tx

                onSignAndSendPaymentSuccess(tx)
            } catch (ex: LeftoverBalanceException) {
                val shouldContinue = MinimumBalanceDialog().showAsync(requireActivity())

                if (shouldContinue == true) {
                    handleGo(false)
                }
            } catch (ex: InsufficientMoneyException) {
                showInsufficientMoneyDialog(ex.missing ?: Coin.ZERO)
            } catch (ex: KeyCrypterException) {
                log.info("send topup failure (encryption)", ex)
                showFailureDialog(ex)
            } catch (ex: Wallet.CouldNotAdjustDownwards) {
                showEmptyWalletFailedDialog()
            } catch (ex: Exception) {
                showFailureDialog(ex)
            }

            viewModel.resetState()
        }
    }

    private fun onSignAndSendPaymentSuccess(transaction: Transaction) {
//        viewModel.logSentEvent(enterAmountViewModel.dashToFiatDirection.value ?: true)
        val callingActivity = requireActivity().callingActivity

        if (callingActivity != null) {
            log.info("returning result to calling activity: {}", callingActivity.flattenToString())
            val resultIntent = Intent()
            BitcoinIntegration.transactionHashToResult(
                resultIntent,
                transaction.txId.toString()
            )
            requireActivity().setResult(Activity.RESULT_OK, resultIntent)
        }
        lifecycleScope.launch {
            buyCreditsViewModel.topUpOnPlatform()
            showTransactionResult(transaction, false)
            playSentSound()
            requireActivity().finish()
        }
    }

    /**
     * Post-cutover top-up: fund the identity's credit balance through the SDK's
     * resume-gated, fused topUpFromCore and observe the three-valued outcome
     * directly. Unlike the dashj path there is no funding Transaction, so the
     * TransactionResultActivity screen is skipped — the new credit balance is
     * surfaced by the credits UI on return.
     *
     * Funds safety: the executor runs the mandatory resume gate before any
     * fresh build and never falls back to dashj. NotBroadcast means nothing was
     * spent (retry-safe); Ambiguous means the top-up MAY be on chain — the
     * executor keeps it sticky (refuses any further attempt this process) and
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
        val progress = AdaptiveDialog.progress(getString(R.string.send_coins_sending_msg))
        progress.show(parentFragmentManager, "buy_credits_sdk_topup")
        val result = try {
            buyCreditsViewModel.topUpViaSdk(amountDuffs)
        } finally {
            progress.dismissAllowingStateLoss()
        }

        when (result) {
            is SdkWriteResult.Broadcast -> {
                log.info("SDK top-up broadcast; new credit balance {}", result.value)
                onSdkTopUpSuccess()
            }
            is SdkWriteResult.NotBroadcast -> {
                // Provably nothing spent — retry-safe. Surface the standard
                // send-error dialog so the user can try again.
                log.warn("SDK top-up not sent: {}", result.reason)
                showFailureDialog(Exception(result.reason))
            }
            is SdkWriteResult.Ambiguous -> {
                // The top-up MAY have gone through; the executor is sticky and
                // refuses any retry. Never offer a retry (double-pay risk).
                log.error("SDK top-up outcome unconfirmed", result.cause)
                showSdkTopUpAmbiguousDialog()
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