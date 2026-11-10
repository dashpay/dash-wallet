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

package de.schildbach.wallet.ui.payments.internal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.service.platform.sdk.ExternalSendResult
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet.ui.payments.send.ExternalSendStatus
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.ui.LockScreenAware
import org.dash.wallet.common.ui.components.DashWalletTheme
import javax.inject.Inject

/**
 * The Internal page of [de.schildbach.wallet.ui.payments.AdvancedPaymentsFragment] (MO-1125):
 * transfers between the user's own balances. Basic mode keeps
 * [de.schildbach.wallet.ui.payments.PaymentsInternalFragment].
 *
 * Confirm captures the exact request, then asks for PIN/biometric (the External Send's auth
 * path), then hands it to the app-scoped [InternalTransferExecutor]: leaving the screen does not
 * cancel the transfer. Back closes an open sheet; otherwise it belongs to the host (as in the
 * External Send, Back while a transfer runs leaves it running). Closing a finished transfer's
 * outcome returns Home, as the External Send does; Not sent stays here.
 *
 * The lock screen is drawn in MainActivity's content, over this page and its in-place sheets but
 * under the outcome dialog's window: on lock ([onLockScreenActivated]) the outcome is withheld
 * until the wallet is unlocked ([LockScreenActivity.walletLocked] turns false).
 */
@AndroidEntryPoint
class InternalTransferFragment : Fragment(), LockScreenAware {

    companion object {
        fun newInstance() = InternalTransferFragment()
    }

    private val viewModel by viewModels<InternalTransferViewModel>()

    @Inject
    lateinit var authManager: AuthenticationManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DashWalletTheme {
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    // ViewPager2 lifts only the selected page to RESUMED: an offscreen Internal page
                    // must not take Back from the page on show. Nor does a sheet the lock screen covers.
                    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
                    val selectedPage = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
                    val sheetOpen = uiState.picker != null || uiState.confirm != null
                    BackHandler(enabled = selectedPage && !uiState.lockScreenShown && sheetOpen) {
                        viewModel.onBack()
                    }
                    InternalTransferScreen(
                        uiState = uiState,
                        actions = InternalTransferActions(
                            onKeyInput = viewModel::onKeyInput,
                            onCurrencySelected = viewModel::onCurrencySelected,
                            onMaxClick = viewModel::onMaxClick,
                            onShowPicker = viewModel::onShowPicker,
                            onDismissPicker = viewModel::onDismissPicker,
                            onBalancePicked = viewModel::onBalancePicked,
                            onSwap = viewModel::onSwap,
                            onContinue = viewModel::onContinue,
                            onDismissConfirm = viewModel::onDismissConfirm,
                            onConfirm = ::authenticateAndTransfer,
                            onHideSending = viewModel::onHideSending,
                            onResultClosed = ::onResultClosed
                        )
                    )
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val walletLocked = (activity as? LockScreenActivity)?.walletLocked ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            // The lock screen covers the activity in place, so this page stays RESUMED under it:
            // the lock state, not the lifecycle, says when it is gone. A state, so a view
            // recreated after the unlock still releases the withheld outcome.
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                walletLocked.collect { locked -> if (!locked) viewModel.onLockScreenDismissed() }
            }
        }
    }

    /** Called (synchronously) by LockScreenActivity as the lock screen comes up. */
    override fun onLockScreenActivated() {
        viewModel.onLockScreen()
    }

    /**
     * A terminal outcome (sent, may have gone through — including the one shown after a process
     * restart — or any other result but Not sent) returns Home. Not sent stays on this tab so the
     * user can try again. The outcome is read before acknowledging, which clears it.
     */
    private fun onResultClosed() {
        val result = (viewModel.uiState.value.status as? ExternalSendStatus.Done)?.result
        viewModel.onResultAcknowledged()
        // Only the selected (RESUMED) pager page shows the outcome and may navigate.
        val selectedPage = viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (result != null && result !is ExternalSendResult.NotSent && selectedPage) {
            leaveToHome()
        }
    }

    /**
     * Pops back to Home (the bottom navigation follows the destination change). Falls back to a
     * single pop when Home is not on the back stack so the user is never stranded here.
     */
    private fun leaveToHome() {
        val navController = findNavController()
        // A second tap while the pop runs would act on the screen below.
        if (navController.currentDestination?.id == R.id.advancedPaymentsFragment) {
            if (!navController.popBackStack(R.id.walletFragment, false)) {
                navController.popBackStack()
            }
        }
    }

    /**
     * Capture what the confirm sheet shows BEFORE authenticating, then transfer exactly that.
     * A failed or cancelled authentication transfers nothing.
     */
    private fun authenticateAndTransfer() {
        val request = viewModel.confirmedRequest() ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val activity = activity ?: return@launch
            if (authManager.authenticate(activity) == null) return@launch
            viewModel.submit(request)
        }
    }
}
