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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.ui.components.DashWalletTheme
import javax.inject.Inject

/**
 * The Internal page of [de.schildbach.wallet.ui.payments.AdvancedPaymentsFragment] (MO-1125):
 * transfers between the user's own balances. Basic mode keeps
 * [de.schildbach.wallet.ui.payments.PaymentsInternalFragment].
 *
 * Confirm captures the exact request, then asks for PIN/biometric (the External Send's auth
 * path), then hands it to the app-scoped [InternalTransferExecutor]: leaving the screen does not
 * cancel the transfer. Back closes an open sheet; otherwise it belongs to the host.
 */
@AndroidEntryPoint
class InternalTransferFragment : Fragment() {

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
                    BackHandler(enabled = uiState.picker != null || uiState.confirm != null) { viewModel.onBack() }
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
                            onResultClosed = viewModel::onResultAcknowledged
                        )
                    )
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
