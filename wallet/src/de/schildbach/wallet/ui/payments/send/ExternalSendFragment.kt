/*
 * Copyright 2026 Dash Core Group.
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

package de.schildbach.wallet.ui.payments.send

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.service.platform.sdk.ExternalSendResult
import de.schildbach.wallet.ui.transactions.TransactionResultActivity
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.services.LockScreenBroadcaster
import org.dash.wallet.common.ui.LockScreenHost
import org.dash.wallet.common.ui.handleWhenResumed
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.scan.ScanActivity
import javax.inject.Inject

/**
 * The Advanced-mode external send flow (MO-1121), a `nav_home` destination without the bottom
 * navigation. Its argument [ExternalSendViewModel.ARG_PREFILL] is an already-validated scanned
 * destination; without it the flow starts on the empty address page.
 *
 * Confirm captures the exact request, then asks for PIN/biometric, then hands it to the
 * app-scoped [ExternalSendExecutor]: leaving the screen does not cancel the send.
 */
@AndroidEntryPoint
class ExternalSendFragment : Fragment() {

    private val viewModel by viewModels<ExternalSendViewModel>()

    @Inject
    lateinit var authManager: AuthenticationManager

    @Inject
    lateinit var lockScreenBroadcaster: LockScreenBroadcaster

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val input = result.data?.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
        if (result.resultCode == Activity.RESULT_OK && input != null) {
            viewModel.onAddressChanged(input)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DashWalletTheme {
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    BackHandler { onBack() }
                    // Built once: a new instance per uiState emission (every key press) would
                    // make every child taking it recompose.
                    val actions = remember {
                        ExternalSendActions(
                            onBack = ::onBack,
                            onAddressChanged = viewModel::onAddressChanged,
                            onScanClick = ::scan,
                            onShowClipboard = ::showClipboard,
                            onClipboardEntrySelected = viewModel::onClipboardEntrySelected,
                            onAddressContinue = viewModel::onAddressContinue,
                            onKeyInput = viewModel::onKeyInput,
                            onCurrencySelected = viewModel::onCurrencySelected,
                            onMaxClick = viewModel::onMaxClick,
                            onShowSourceSheet = viewModel::onShowSourceSheet,
                            onDismissSourceSheet = viewModel::onDismissSourceSheet,
                            onSourceSelected = viewModel::onSourceSelected,
                            onRetryIdentity = viewModel::onRetryIdentity,
                            onAmountContinue = viewModel::onAmountContinue,
                            onDismissConfirm = viewModel::onDismissConfirm,
                            onConfirm = ::authenticateAndSend,
                            onLeave = ::leave,
                            onResultClosed = ::onResultClosed
                        )
                    }
                    ExternalSendScreen(uiState = uiState, actions = actions)
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // A sent outcome with its txid opens the transaction result screen, once, and only while
        // this screen is resumed and the wallet unlocked (nothing over the PIN screen). The
        // executor keeps the outcome until it is acknowledged, so a screen recreated or returned
        // to meanwhile opens it then. Opening acknowledges it in the same turn.
        handleWhenResumed(
            viewModel.openTransactionResult,
            lockScreenBroadcaster.unlocked,
            viewModel::takeTransactionResult,
            putBack = {}
        ) { txId ->
            showTransactionResult(txId)
            true
        }
    }

    /**
     * Back is ignored while the lock screen covers this screen: the activity's own callback,
     * which ignores it too, runs only after this one. While the outcome shows, Back acknowledges
     * it, as the dialog it replaced did on dismiss-on-back.
     */
    private fun onBack() {
        if ((activity as? LockScreenHost)?.lockScreenDisplayed == true) return
        val state = viewModel.uiState.value
        val txId = state.openTransactionResult
        if (txId != null) {
            showTransactionResult(txId)
            return
        }
        val status = state.status
        if (status is ExternalSendStatus.Done) {
            onResultClosed(status.result)
            return
        }
        if (!viewModel.onBack()) leave()
    }

    private fun leave() {
        val navController = findNavController()
        // A second tap while the pop runs would pop the payments screen too.
        if (navController.currentDestination?.id == R.id.externalSendFragment) {
            navController.popBackStack()
        }
    }

    private fun scan() {
        scanLauncher.launch(ScanActivity.getIntent(activity))
    }

    /** The clipboard is read only here, on the user's tap; its text is never logged. */
    private fun showClipboard() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(requireContext())?.toString()
        } else {
            null
        }
        viewModel.onClipboardRead(text)
    }

    /**
     * Capture what the confirm sheet shows BEFORE authenticating (the
     * `captureAuthenticateAndSubmit` contract of the shielded flows), then send exactly that.
     * A failed or cancelled authentication sends nothing.
     */
    private fun authenticateAndSend() {
        val request = viewModel.confirmedRequest() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val activity = activity ?: return@launch
            if (authManager.authenticate(activity) == null) return@launch
            viewModel.submit(request)
        }
    }

    /**
     * A terminal outcome (sent, or may have gone through — including the one shown after a
     * process restart) returns Home, the way a completed send does elsewhere. NotSent stays
     * on this screen so the user can try again.
     */
    private fun onResultClosed(result: ExternalSendResult) {
        viewModel.onResultAcknowledged()
        if (result !is ExternalSendResult.NotSent) {
            leaveToHome()
        }
    }

    /**
     * Opens the transaction result screen for [txId] (as [de.schildbach.wallet.ui.send.SendCoinsFragment]
     * does after a send), acknowledges the outcome and returns Home. Called on the main thread
     * with [txId] the current [ExternalSendUIState.openTransactionResult]; acknowledging clears
     * it before anything else can run, so it is opened once.
     */
    private fun showTransactionResult(txId: String) {
        if (!isAdded) return
        startActivity(TransactionResultActivity.createIntent(requireActivity(), Sha256Hash.wrap(txId), false))
        viewModel.onResultAcknowledged()
        leaveToHome()
    }

    /**
     * Pops back to Home (the bottom navigation follows the destination change). Falls back
     * to [leave] when Home is not on the back stack so the user is never stranded here.
     */
    private fun leaveToHome() {
        val navController = findNavController()
        // A second tap while the pop runs would act on the screen below.
        if (navController.currentDestination?.id == R.id.externalSendFragment) {
            if (!navController.popBackStack(R.id.walletFragment, false)) {
                navController.popBackStack()
            }
        }
    }
}
