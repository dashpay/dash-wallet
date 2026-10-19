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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import de.schildbach.wallet.ui.compose_views.createApproveConnectionDialog
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
                                try {
                                    val details = viewModel.connectionDetails(connection)
                                    MaterialAlertDialogBuilder(requireContext()).setTitle(connection.name)
                                        .setMessage(details).setPositiveButton(android.R.string.ok, null)
                                        .setNeutralButton(R.string.dash_connect_revoke) { _, _ -> viewModel.disconnect(connection) }.show()
                                } catch (e: Exception) {
                                    if (e is kotlinx.coroutines.CancellationException) throw e
                                    Toast.makeText(requireContext(), e.message, Toast.LENGTH_LONG).show()
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

        if (savedInstanceState == null) {
            // A DashConnect QR scanned from another screen's scanner routes here with the URI.
            arguments?.getString(ARG_SCANNED_URI)?.let { uri ->
                arguments?.remove(ARG_SCANNED_URI)
                viewModel.onQrScanned(uri)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.scanOutcome.collect { outcome ->
                    when (outcome) {
                        is ConnectionsViewModel.ScanOutcome.ConnectionRequested -> {
                            showApproveConnectionDialog()
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
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.callbackOffer.collect { callback ->
                    if (callback != null) {
                        viewModel.resetCallbackOffer()
                        val uri = Uri.parse(callback)
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(getString(R.string.dash_connect_return_title, uri.host))
                            .setMessage(R.string.dash_connect_return_warning)
                            .setPositiveButton(R.string.dash_connect_return_open) { _, _ ->
                                try { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                                catch (e: android.content.ActivityNotFoundException) {
                                    Toast.makeText(requireContext(), R.string.dash_connect_return_unavailable, Toast.LENGTH_LONG).show()
                                }
                            }.setNegativeButton(android.R.string.cancel, null).show()
                    }
                }
            }
        }
    }

    private fun launchScanner() {
        scanLauncher.launch(ScanActivity.getIntent(requireActivity()))
    }

    private fun showApproveConnectionDialog() {
        createApproveConnectionDialog(
            viewModel = viewModel
        ).show(parentFragmentManager, "approve_connection_dialog")
    }

    companion object {
        /** Matches the `scannedUri` argument in nav_home.xml. */
        const val ARG_SCANNED_URI = "scannedUri"
    }
}
