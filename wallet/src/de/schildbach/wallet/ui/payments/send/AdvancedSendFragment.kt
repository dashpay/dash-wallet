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
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.dashpay.ContactsScreenMode
import de.schildbach.wallet.ui.more.connections.protocol.DashConnectUri
import de.schildbach.wallet.ui.payments.PaymentsFragment.Companion.ARG_SOURCE
import de.schildbach.wallet.ui.payments.PaymentsFragmentDirections
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet_test.R
import kotlinx.coroutines.launch
import org.dash.wallet.common.payments.parsers.AddressNetwork
import org.dash.wallet.common.payments.parsers.DashPaymentIntentParser
import org.dash.wallet.common.payments.parsers.PaymentIntentParserException
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.components.ActionItem
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.components.LocalDashColors
import org.dash.wallet.common.ui.components.Menu
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.scan.ScanActivity

/**
 * The Send page of [de.schildbach.wallet.ui.payments.AdvancedPaymentsFragment] (Figma
 * 6894:49688): Send to username, Send to Dash address and Scan Dash QR. Basic mode keeps
 * [de.schildbach.wallet.ui.payments.PaymentsPayFragment].
 *
 * - Send to username: the contact picker, exactly as the Basic Send tab opens it.
 * - Send to Dash address: [ExternalSendFragment] on its address page.
 * - Scan Dash QR: [AdvancedSendViewModel.scanActionFor] decides.
 */
@AndroidEntryPoint
class AdvancedSendFragment : Fragment() {

    companion object {
        fun newInstance(source: String) = AdvancedSendFragment().apply {
            arguments = bundleOf(ARG_SOURCE to source)
        }
    }

    private val viewModel by viewModels<AdvancedSendViewModel>()

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val input = result.data?.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
        if (result.resultCode == Activity.RESULT_OK && input != null) {
            handleScan(input)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DashWalletTheme {
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    AdvancedSendPage(
                        showSendToUsername = uiState.showSendToUsername,
                        onSendToUsername = ::sendToUsername,
                        onSendToAddress = { openExternalSend(prefill = null) },
                        onScan = ::scan
                    )
                }
            }
        }

    private val source: String
        get() = arguments?.getString(ARG_SOURCE) ?: ""

    /** As [de.schildbach.wallet.ui.payments.PaymentsPayFragment]: the contact picker pays on Core. */
    private fun sendToUsername() {
        val navController = findNavController()
        if (navController.currentDestination?.getAction(R.id.payments_to_contacts) == null) return
        viewModel.logEvent(AnalyticsConstants.UsersContacts.TAB_SEND_TO_CONTACT)
        navController.navigate(
            PaymentsFragmentDirections.paymentsToContacts(
                ShowNavBar = false,
                mode = ContactsScreenMode.SELECT_CONTACT,
                source = source
            )
        )
    }

    private fun scan() {
        viewModel.logEvent(AnalyticsConstants.SendReceive.SCAN_TO_SEND)
        scanLauncher.launch(ScanActivity.getIntent(activity))
    }

    private fun openExternalSend(prefill: String?) {
        val navController = findNavController()
        // Only from the Advanced payments screen, so a second tap after navigating does nothing.
        if (navController.currentDestination?.getAction(R.id.payments_to_externalSend) == null) return
        if (prefill == null) {
            viewModel.logEvent(AnalyticsConstants.SendReceive.SEND_TO_ADDRESS)
        }
        navController.navigate(
            R.id.payments_to_externalSend,
            bundleOf(ExternalSendViewModel.ARG_PREFILL to prefill)
        )
    }

    private fun handleScan(input: String) {
        val action = AdvancedSendViewModel.scanActionFor(
            input,
            AddressNetwork.fromId(Constants.NETWORK_PARAMETERS.id),
            Constants.SUPPORTS_CONNECT
        ) { DashConnectUri.isKeyUri(it) || DashConnectUri.isStUri(it) }
        when (action) {
            is AdvancedScanAction.DashConnect -> {
                val navController = findNavController()
                if (navController.currentDestination?.getAction(R.id.payments_to_connections) != null) {
                    navController.navigate(PaymentsFragmentDirections.paymentsToConnections(action.input))
                }
            }
            is AdvancedScanAction.PaymentRequest -> payInvoice(action.input)
            is AdvancedScanAction.ExternalSend -> openExternalSend(action.input)
            is AdvancedScanAction.Error -> showError(
                getString(if (action.wrongNetwork) R.string.external_send_wrong_network else R.string.advanced_send_invalid_qr)
            )
        }
    }

    /** A BIP70 invoice: the same path as the Basic Send tab. */
    private fun payInvoice(input: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val paymentIntent = DashPaymentIntentParser(AddressNetwork.fromId(Constants.NETWORK_PARAMETERS.id))
                    .parse(input, true)
                paymentIntent.source = source
                SendCoinsActivity.start(requireContext(), paymentIntent)
            } catch (ex: PaymentIntentParserException) {
                showError(ex.localizedMessage.format(resources))
            }
        }
    }

    /** The Basic Send tab's error dialog. */
    private fun showError(message: String) {
        val activity = activity ?: return
        AdaptiveDialog.create(
            R.drawable.ic_error,
            getString(R.string.error),
            message,
            getString(R.string.button_dismiss)
        ).show(activity)
    }
}

/** Figma 6894:49841: one menu card, 20dp under the tab picker. */
@Composable
fun AdvancedSendPage(
    showSendToUsername: Boolean,
    onSendToUsername: () -> Unit,
    onSendToAddress: () -> Unit,
    onScan: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Menu {
            if (showSendToUsername) {
                ActionItem(
                    title = stringResource(R.string.advanced_send_to_username),
                    icon = R.drawable.ic_payments_send_to_contact,
                    onClick = onSendToUsername
                )
            }
            ActionItem(
                title = stringResource(R.string.advanced_send_to_address),
                icon = R.drawable.ic_send_to_address,
                onClick = onSendToAddress
            )
            ActionItem(
                title = stringResource(R.string.advanced_send_scan_qr),
                icon = R.drawable.ic_payments_send_by_qr,
                onClick = onScan
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 400)
@Composable
private fun AdvancedSendPagePreview() {
    DashWalletTheme {
        Box(modifier = Modifier.background(LocalDashColors.current.backgroundPrimary)) {
            AdvancedSendPage(showSendToUsername = true, onSendToUsername = {}, onSendToAddress = {}, onScan = {})
        }
    }
}
