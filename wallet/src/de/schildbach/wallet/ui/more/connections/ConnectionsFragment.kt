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

package de.schildbach.wallet.ui.more.connections

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import de.schildbach.wallet_test.R
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.compose_views.ApproveConnectionDialog
import de.schildbach.wallet.ui.compose_views.ApproveTokenPurchaseDialog
import de.schildbach.wallet.ui.LockScreenActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.scan.ScanActivity

/**
 * Hosts the DashConnect "Connections" screen (MO-945).
 */
@AndroidEntryPoint
class ConnectionsFragment : Fragment() {
    private val viewModel: ConnectionsViewModel by viewModels()

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)?.let { qrContent ->
                viewModel.onQrScanned(qrContent)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DashWalletTheme {
                    ConnectionsScreen(
                        onBackClick = { findNavController().popBackStack() },
                        onScanClick = { launchScanner() },
                        onConnectionClick = { connection ->
                            viewLifecycleOwner.lifecycleScope.launch {
                                if (!viewModel.isWalletUnlocked || !viewModel.canUseConnections) return@launch
                                try {
                                    val details = viewModel.connectionDetails(connection)
                                    val confirmed = showUnlockedDialog {
                                        connectionDetailsDialog(requireContext(), connection.name, details, connection.status, connection.legacyOnly)
                                    }
                                    if (confirmed == true && viewModel.isWalletUnlocked && viewModel.canUseConnections) {
                                        if (connection.legacyOnly) viewModel.removeConnection(connection)
                                        else if (connection.status == ConnectionStatus.DISCONNECTED) launchScanner()
                                        else viewModel.disconnect(connection)
                                    }
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    if (viewModel.isWalletUnlocked) Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        onConnectionQrClick = { launchScanner() },
                        onConnectionToggle = { connection, enabled ->
                            // The switch is only ON for active connections; moving it OFF disconnects
                            // directly (no confirmation dialog — the toggle itself is the action).
                            if (!enabled) {
                                viewModel.disconnect(connection)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val host = activity as? LockScreenActivity
        val foreground = ConnectionForegroundObserver(requireContext(), viewModel::onForegroundChanged)
        viewLifecycleOwner.lifecycle.addObserver(foreground)
        viewModel.bindWalletUnlockCheck {
            host != null && !host.walletLocked.value && !host.lockScreenDisplayed && foreground.isDeviceAvailable()
        }
        viewModel.bindWalletLockState(host?.walletLocked ?: kotlinx.coroutines.flow.MutableStateFlow(true)) {
            host?.walletLockGeneration ?: -1L
        }

        if (savedInstanceState == null) {
            // Scanner routes default to no callback; only an app-opening URL opts in.
            arguments?.getString(ARG_SCANNED_URI)?.let { uri ->
                val openedFromUrl = arguments?.getBoolean(ARG_OPENED_FROM_URL) == true
                arguments?.remove(ARG_SCANNED_URI)
                arguments?.remove(ARG_OPENED_FROM_URL)
                if (openedFromUrl) viewModel.onUrlOpened(uri) else viewModel.onQrScanned(uri)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.scanOutcome.collect { outcome ->
                    when (outcome) {
                        is ConnectionsViewModel.ScanOutcome.ConnectionRequested -> {
                            viewModel.resetScanOutcome()
                        }
                        is ConnectionsViewModel.ScanOutcome.Error -> {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.dash_connect_error, outcome.message ?: ""),
                                Toast.LENGTH_LONG
                            ).show()
                            viewModel.resetScanOutcome()
                        }
                        is ConnectionsViewModel.ScanOutcome.Idle -> Unit
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val lockState = host?.walletLocked ?: kotlinx.coroutines.flow.MutableStateFlow(true)
                combine(viewModel.uiState, lockState) { state, locked -> state to locked }.collect { (state, locked) ->
                    if (locked || !viewModel.isWalletUnlocked || state.recordsStatus == ConnectionRecordsStatus.UNAVAILABLE) {
                        listOf(APPROVAL_DIALOG, PURCHASE_DIALOG).forEach { tag ->
                            (childFragmentManager.findFragmentByTag(tag) as? androidx.fragment.app.DialogFragment)?.let {
                                it.dialog?.hide()
                                it.dismissAllowingStateLoss()
                            }
                        }
                        return@collect
                    }
                    if (state.recordsStatus == ConnectionRecordsStatus.READY && state.pendingRequest != null &&
                        childFragmentManager.findFragmentByTag(APPROVAL_DIALOG) == null &&
                        !childFragmentManager.isStateSaved) {
                        ApproveConnectionDialog().showNow(childFragmentManager, APPROVAL_DIALOG)
                    }
                    if (state.recordsStatus == ConnectionRecordsStatus.READY && state.pendingPurchase != null &&
                        childFragmentManager.findFragmentByTag(PURCHASE_DIALOG) == null &&
                        !childFragmentManager.isStateSaved) {
                        ApproveTokenPurchaseDialog().showNow(childFragmentManager, PURCHASE_DIALOG)
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                val lockState = host?.walletLocked ?: kotlinx.coroutines.flow.MutableStateFlow(true)
                combine(viewModel.callbackOffer, lockState) { callback, locked -> callback to locked }
                    .collectLatest { (callback, locked) ->
                        if (callback != null && !locked && viewModel.isWalletUnlocked) {
                            val uri = Uri.parse(callback)
                            val confirmed = showUnlockedDialog { connectionCallbackDialog(requireContext(), uri.host.orEmpty()) }
                            if (!viewModel.isWalletUnlocked) return@collectLatest
                            if (confirmed == true) {
                                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                catch (e: android.content.ActivityNotFoundException) {
                                    Toast.makeText(requireContext(), R.string.dash_connect_return_unavailable, Toast.LENGTH_LONG).show()
                                }
                            }
                            viewModel.resetCallbackOffer()
                        }
                    }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshConnections()
    }

    private fun launchScanner() {
        if (!viewModel.isWalletUnlocked || !viewModel.canUseConnections) return
        scanLauncher.launch(ScanActivity.getIntent(requireActivity()))
    }

    private suspend fun showUnlockedDialog(create: () -> org.dash.wallet.common.ui.dialogs.AdaptiveDialog): Boolean? {
        val host = activity as? LockScreenActivity ?: return null
        return awaitUnlockedConnectionDialog(host, viewLifecycleOwner, host.walletLocked,
            { viewModel.isWalletUnlocked }, create)
    }

    override fun onDestroyView() {
        viewModel.bindWalletUnlockCheck { false }
        super.onDestroyView()
    }

    companion object {
        private const val APPROVAL_DIALOG = "approve_connection_dialog"
        private const val PURCHASE_DIALOG = "approve_token_purchase_dialog"
        /** Matches the `scannedUri` argument in nav_home.xml. */
        const val ARG_SCANNED_URI = "scannedUri"
        const val ARG_OPENED_FROM_URL = "openedFromUrl"
    }
}
