package de.schildbach.wallet.ui.send

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import de.schildbach.wallet.data.CreditBalanceInfo
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.integration.android.BitcoinIntegration
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.MinimumBalanceDialog
import org.slf4j.LoggerFactory

class BuyCreditsFragment : SendCoinsFragment() {

    companion object {
        private val log = LoggerFactory.getLogger(BuyCreditsFragment::class.java)
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.paymentHeader.setTitle(getString(R.string.credit_balance_button_buy))
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
        val amount = enterAmountViewModel.amount.value ?: Coin.CENT
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

        AdaptiveDialog.create(
            null,
            "Not Implemented",
            "The feature to topup your credits is not completed",
            getString(R.string.button_close)
        ).showAsync(requireActivity())

//        val dryRunRequest = viewModel.dryrunSendRequest ?: return
//        val address = viewModel.basePaymentIntent.address?.toBase58() ?: return
//
//        val txFee = dryRunRequest.tx.fee
//        val amount: Coin?
//        val total: String?
//
//        if (dryRunRequest.emptyWallet) {
//            amount = enterAmountViewModel.amount.value?.minus(txFee)
//            total = enterAmountViewModel.amount.value?.toPlainString()
//        } else {
//            amount = enterAmountViewModel.amount.value
//            total = amount?.add(txFee ?: Coin.ZERO)?.toPlainString()
//        }
//
//        val rate = enterAmountViewModel.selectedExchangeRate.value
//        val exchangeRate = rate?.let { ExchangeRate(Coin.COIN, rate.fiat) }
//        val amountStr = MonetaryFormat.BTC.noCode().format(amount).toString()
//        val fee = txFee?.toPlainString() ?: ""
//
//        var dashPayProfile: DashPayProfile? = null
//
//        if (viewModel.contactData.value?.requestReceived == true) {
//            dashPayProfile = viewModel.contactData.value?.dashPayProfile
//        }
//
//        val isPendingContactRequest = viewModel.contactData.value?.isPendingRequest == true
//        val username = dashPayProfile?.username
//        val displayName = (dashPayProfile?.displayName ?: "").ifEmpty { username }
//        val avatarUrl = dashPayProfile?.avatarUrl
//
//        // need to put the conformation for used with Create UserName
//        val dialog = ConfirmTransactionDialog()
//        val confirmed = dialog.show(
//            requireActivity(),
//            address,
//            amountStr,
//            exchangeRate,
//            fee,
//            total ?: "",
//            null,
//            null,
//            null,
//            username,
//            displayName,
//            avatarUrl,
//            isPendingContactRequest
//        )
//
//        if (confirmed == true) {
//            handleGo(true, dialog.autoAcceptContactRequest)
//        }
    }

    private suspend fun handleGo(checkBalance: Boolean, autoAcceptContactRequest: Boolean) {
        if (viewModel.dryrunSendRequest == null) {
            log.error("illegal state dryrunSendRequest == null")
            return
        }

        val editedAmount = enterAmountViewModel.amount.value
        val rate = enterAmountViewModel.selectedExchangeRate.value

        if (editedAmount != null) {
            val exchangeRate = rate?.fiat?.let { ExchangeRate(Coin.COIN, it) }

            try {
                viewModel.logEvent(AnalyticsConstants.SendReceive.ENTER_AMOUNT_SEND)

                if (enterAmountFragment?.maxSelected == true) {
                    viewModel.logEvent(AnalyticsConstants.SendReceive.ENTER_AMOUNT_MAX)
                }

                // buy do an asset lock transaction.
                val tx = viewModel.signAndSendPayment(editedAmount, exchangeRate, checkBalance)

                onSignAndSendPaymentSuccess(tx, autoAcceptContactRequest)
            } catch (ex: LeftoverBalanceException) {
                val shouldContinue = MinimumBalanceDialog().showAsync(requireActivity())

                if (shouldContinue == true) {
                    handleGo(false, autoAcceptContactRequest)
                }
            } catch (ex: InsufficientMoneyException) {
                showInsufficientMoneyDialog(ex.missing ?: Coin.ZERO)
            } catch (ex: KeyCrypterException) {
                showFailureDialog(ex)
            } catch (ex: Wallet.CouldNotAdjustDownwards) {
                showEmptyWalletFailedDialog()
            } catch (ex: Exception) {
                showFailureDialog(ex)
            }

            viewModel.resetState()
        }
    }

    private fun onSignAndSendPaymentSuccess(transaction: Transaction, autoAcceptContactRequest: Boolean) {
        viewModel.logSentEvent(enterAmountViewModel.dashToFiatDirection.value ?: true)
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

        showTransactionResult(transaction, autoAcceptContactRequest)
        playSentSound()
        requireActivity().finish()
    }
}