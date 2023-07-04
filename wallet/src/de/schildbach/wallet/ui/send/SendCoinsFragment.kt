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

package de.schildbach.wallet.ui.send

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.integration.android.BitcoinIntegration
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet.ui.transactions.TransactionResultActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.SendCoinsFragmentBinding
import kotlinx.coroutines.launch
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.dialogs.MinimumBalanceDialog
import org.dash.wallet.common.ui.enter_amount.EnterAmountFragment
import org.dash.wallet.common.ui.enter_amount.EnterAmountViewModel
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.GenericUtils
import org.slf4j.LoggerFactory
import javax.inject.Inject

@AndroidEntryPoint
class SendCoinsFragment: Fragment(R.layout.send_coins_fragment) {
    companion object {
        private val log = LoggerFactory.getLogger(SendCoinsFragment::class.java)
        private const val SEND_COINS_SOUND = "send_coins_broadcast_1"
    }

    private val binding by viewBinding(SendCoinsFragmentBinding::bind)
    private val viewModel by activityViewModels<SendCoinsViewModel>()
    private val enterAmountViewModel by activityViewModels<EnterAmountViewModel>()
    private val args by navArgs<SendCoinsFragmentArgs>()

    @Inject lateinit var authManager: AuthenticationManager
    private var enterAmountFragment: EnterAmountFragment? = null
    private var revealBalance = false
    private var userAuthorizedDuring: Boolean = false
        get() = field || enterAmountFragment?.didAuthorize == true
        set(value) {
            field = value
            enterAmountFragment?.didAuthorize = value
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val requirePinForBalance = (requireActivity() as LockScreenActivity).keepUnlocked

        binding.titleBar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        viewModel.initPaymentIntent(args.paymentIntent)

        if (savedInstanceState == null) {
            val intentAmount = args.paymentIntent.amount
            var dashToFiat = viewModel.isDashToFiatPreferred
            // If an amount is specified (in Dash), then set the active currency to Dash
            // If amount is 0 Dash or not specified, then don't change the active currency
            if (intentAmount != null && !intentAmount.isZero) {
                dashToFiat = true
            }

            val fragment = EnterAmountFragment.newInstance(
                initialAmount = args.paymentIntent.amount,
                dashToFiat = dashToFiat,
                requirePinForMaxButton = requirePinForBalance
            )
            childFragmentManager.beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.enter_amount_fragment_placeholder, fragment)
                .commitNow()
            enterAmountFragment = fragment
        } else {
            enterAmountFragment = childFragmentManager.findFragmentById(
                R.id.enter_amount_fragment_placeholder
            ) as EnterAmountFragment
        }

        binding.hideButton.setOnClickListener {
            lifecycleScope.launch { revealOrHideBalance(requirePinForBalance) }
        }

        viewModel.isBlockchainReplaying.observe(viewLifecycleOwner) { updateView() }
        viewModel.dryRunSuccessful.observe(viewLifecycleOwner) { isSuccess ->
            if (!isSuccess && viewModel.shouldAdjustAmount()) {
                val newAmount = viewModel.getAdjustedAmount()
                enterAmountFragment?.setAmount(newAmount)
            } else {
                updateView()
            }
        }
        viewModel.state.observe(viewLifecycleOwner) { updateView() }
        viewModel.address.observe(viewLifecycleOwner) { binding.address.text = it }
        viewModel.maxOutputAmount.observe(viewLifecycleOwner) { balance ->
            enterAmountViewModel.setMaxAmount(balance)
            updateBalanceLabel(balance, enterAmountViewModel.selectedExchangeRate.value)
        }

        enterAmountViewModel.amount.observe(viewLifecycleOwner) { viewModel.currentAmount = it }
        enterAmountViewModel.dashToFiatDirection.observe(viewLifecycleOwner) { viewModel.isDashToFiatPreferred = it }
        enterAmountViewModel.onContinueEvent.observe(viewLifecycleOwner) {
            lifecycleScope.launch { authenticateOrConfirm() }
        }
    }

    private fun updateView() {
        val isReplaying = viewModel.isBlockchainReplaying.value
        val dryRunException = viewModel.dryRunException
        val state = viewModel.state.value ?: SendCoinsViewModel.State.INPUT
        var errorMessage = ""

        if (isReplaying == true) {
            errorMessage = getString(R.string.send_coins_fragment_hint_replaying)
        } else if (dryRunException != null) {
            errorMessage = when (dryRunException) {
                is Wallet.DustySendRequested -> getString(R.string.send_coins_error_dusty_send)
                is InsufficientMoneyException -> getString(R.string.send_coins_error_insufficient_money)
                is Wallet.CouldNotAdjustDownwards -> getString(R.string.send_coins_error_dusty_send)
                else -> dryRunException.toString()
            }
        }

        enterAmountFragment?.setError(errorMessage)
        enterAmountViewModel.blockContinue = errorMessage.isNotEmpty() ||
            !viewModel.everythingPlausible() ||
            viewModel.isBlockchainReplaying.value ?: false

        enterAmountFragment?.setViewDetails(
            getString(
                when (state) {
                    SendCoinsViewModel.State.INPUT -> R.string.send_coins_fragment_button_send
                    SendCoinsViewModel.State.SENDING -> R.string.send_coins_sending_msg
                    SendCoinsViewModel.State.SENT -> R.string.send_coins_sent_msg
                    SendCoinsViewModel.State.FAILED -> R.string.send_coins_failed_msg
                }
            )
        )
    }

    private suspend fun authenticateOrConfirm() {
        if (!viewModel.everythingPlausible()) {
            return
        }

        if (!userAuthorizedDuring || viewModel.isSpendingConfirmationEnabled) {
            val allowBiometric = viewModel.allowBiometric()
            authManager.authenticate(requireActivity(), !allowBiometric) ?: return
            userAuthorizedDuring = true
        }

        if (viewModel.everythingPlausible() && viewModel.dryrunSendRequest != null) {
            showPaymentConfirmation()
        }

        updateView()
    }

    private suspend fun handleGo(checkBalance: Boolean) {
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

                val tx = viewModel.signAndSendPayment(editedAmount, exchangeRate, checkBalance)
                onSignAndSendPaymentSuccess(tx)
            } catch (ex: LeftoverBalanceException) {
                val shouldContinue = MinimumBalanceDialog().showAsync(requireActivity())

                if (shouldContinue == true) {
                    handleGo(false)
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

    private suspend fun showPaymentConfirmation() {
        val dryRunRequest = viewModel.dryrunSendRequest ?: return
        val address = viewModel.basePaymentIntent.address?.toBase58() ?: return

        val txFee = dryRunRequest.tx.fee
        val amount: Coin?
        val total: String?

        if (dryRunRequest.emptyWallet) {
            amount = enterAmountViewModel.amount.value?.minus(txFee)
            total = enterAmountViewModel.amount.value?.toPlainString()
        } else {
            amount = enterAmountViewModel.amount.value
            total = amount?.add(txFee ?: Coin.ZERO)?.toPlainString()
        }

        val rate = enterAmountViewModel.selectedExchangeRate.value
        val exchangeRate = rate?.let { ExchangeRate(Coin.COIN, rate.fiat) }
        val amountStr = MonetaryFormat.BTC.noCode().format(amount).toString()
        val fee = txFee?.toPlainString() ?: ""

        val confirmed = ConfirmTransactionDialog.showDialogAsync(
            requireActivity(),
            address,
            amountStr,
            exchangeRate,
            fee,
            total ?: ""
        )

        if (confirmed) {
            handleGo(true)
        }
    }

    private fun onSignAndSendPaymentSuccess(transaction: Transaction) {
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

        showTransactionResult(transaction)
        playSentSound()
        requireActivity().finish()
    }

    private fun showTransactionResult(transaction: Transaction) {
        if (!isAdded) {
            return
        }

        val transactionResultIntent = TransactionResultActivity.createIntent(
            requireActivity(),
            requireActivity().intent.action,
            transaction,
            false
        )
        startActivity(transactionResultIntent)
    }

    private fun playSentSound() {
        if (!viewModel.shouldPlaySounds) {
            return
        }

        // play sound effect
        val soundResId = resources.getIdentifier(
            SEND_COINS_SOUND,
            "raw",
            requireActivity().packageName
        )

        if (soundResId > 0) {
            RingtoneManager.getRingtone(
                requireActivity(),
                Uri.parse("android.resource://" + requireActivity().packageName + "/" + soundResId)
            )
                .play()
        }
    }

    private fun updateBalanceLabel(balance: Coin, rate: org.dash.wallet.common.data.ExchangeRate?) {
        val exchangeRate = rate?.let { ExchangeRate(Coin.COIN, it.fiat) }

        if (revealBalance) {
            var balanceText = viewModel.dashFormat.format(balance).toString()
            exchangeRate?.let { balanceText += " ~ ${GenericUtils.fiatToString(exchangeRate.coinToFiat(balance))}" }
            binding.balanceLabel.text = balanceText
        } else {
            binding.balanceLabel.text = "**********"
        }
    }

    private suspend fun showInsufficientMoneyDialog(missing: Coin) {
        val msg = StringBuilder(
            getString(
                R.string.send_coins_fragment_insufficient_money_msg1,
                viewModel.dashFormat.format(missing)
            )
        )

        val pending = viewModel.getPendingBalance()

        if (pending.signum() > 0) {
            msg.append("\n\n")
                .append(getString(R.string.send_coins_fragment_pending, viewModel.dashFormat.format(pending)))
        }

        val mayEditAmount = viewModel.basePaymentIntent.mayEditAmount()

        if (mayEditAmount) {
            msg.append("\n\n")
                .append(getString(R.string.send_coins_fragment_insufficient_money_msg2))
        }

        var positiveAction = ""
        val negativeAction: String

        if (mayEditAmount) {
            positiveAction = getString(R.string.send_coins_options_empty)
            negativeAction = getString(R.string.button_cancel)
        } else {
            negativeAction = getString(R.string.button_dismiss)
        }

        val useMax = AdaptiveDialog.create(
            R.drawable.ic_warning_filled,
            getString(R.string.send_coins_fragment_insufficient_money_title),
            msg.toString(),
            negativeAction,
            positiveAction
        ).showAsync(requireActivity())

        if (mayEditAmount && useMax == true) {
            enterAmountFragment?.applyMaxAmount()
        }
    }

    private suspend fun showEmptyWalletFailedDialog() {
        AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.send_coins_fragment_empty_wallet_failed_title),
            getString(R.string.send_coins_fragment_hint_empty_wallet_failed),
            getString(R.string.button_dismiss),
            null
        ).showAsync(requireActivity())
    }

    private suspend fun showFailureDialog(exception: Exception) {
        AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.send_coins_error_msg),
            exception.toString(),
            getString(R.string.button_dismiss),
            null
        ).showAsync(requireActivity())
    }

    private suspend fun revealOrHideBalance(requirePin: Boolean) {
        val isRevealing = !revealBalance

        if (isRevealing && requirePin && !userAuthorizedDuring) {
            authManager.authenticate(requireActivity(), false) ?: return
            userAuthorizedDuring = true
        }

        revealBalance = isRevealing
        viewModel.logEvent(
            if (revealBalance) {
                AnalyticsConstants.SendReceive.ENTER_AMOUNT_SHOW_BALANCE
            } else {
                AnalyticsConstants.SendReceive.ENTER_AMOUNT_HIDE_BALANCE
            }
        )
        viewModel.maxOutputAmount.value?.let { balance ->
            updateBalanceLabel(balance, enterAmountViewModel.selectedExchangeRate.value)
        }
    }
}
