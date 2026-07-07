/*
 * Copyright 2024 Dash Core Group.
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

package org.dash.wallet.integrations.maya.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.LockScreenAware
import org.dash.wallet.common.util.openCustomTab
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.model.MayaResultType
import org.dash.wallet.integrations.maya.model.TransactionType

/**
 * Maya transaction-result screen. Hosts the Compose [MayaConvertResultScreen] while keeping the
 * original behavior: translating transaction type + outcome into the displayed result, blocking
 * back navigation after success (to prevent re-submitting the transaction), the Maya support
 * link and the settlement-network explorer link.
 */
@AndroidEntryPoint
class MayaConvertResultFragment : Fragment(), LockScreenAware {

    private val viewModel by viewModels<MayaConvertResultViewModel>()
    private var onBackPressedCallback: OnBackPressedCallback? = null
    private var currentType: MayaResultType? = null
    private var explorerUrl: String? = null

    private var uiState by mutableStateOf(MayaConvertResultUIState())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MayaConvertResultScreen(
                    state = uiState,
                    onButtonClick = ::handlePositiveButtonClick,
                    onContactSupportClick = ::openMayaHelp,
                    onExplorerLinkClick = {
                        explorerUrl?.let { requireActivity().openCustomTab(it) }
                    }
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        handleBackPress()

        val params = arguments?.let { MayaConvertResultFragmentArgs.fromBundle(it).transactionParams }

        viewModel.loadingState.observe(viewLifecycleOwner) { isLoading ->
            uiState = uiState.copy(isLoading = isLoading)
        }

        viewModel.transactionState.observe(viewLifecycleOwner) { state ->
            params?.let { setTransactionState(it.type, state) }
        }

        viewModel.showTransactionResult(isSuccess = true)
    }

    private fun handlePositiveButtonClick() {
        val type = currentType ?: return
        when (type) {
            MayaResultType.TRANSFER_DASH_ERROR,
            MayaResultType.DEPOSIT_ERROR -> {
                viewModel.logRetry(type)
                viewModel.isRetryingTransfer(true)
            }
            MayaResultType.CONVERSION_ERROR -> {
                viewModel.logRetry(type)
                findNavController().popBackStack()
            }
            MayaResultType.CONVERSION_SUCCESS,
            MayaResultType.DEPOSIT_SUCCESS,
            MayaResultType.TRANSFER_DASH_SUCCESS -> {
                viewModel.logClose(type)
                val navController = findNavController()
                navController.popBackStack(navController.graph.startDestinationId, false)
            }
            else -> {}
        }
    }

    private fun openMayaHelp() {
        val helpUrl = "https://www.mayaprotocol.com"
        try {
            val intent = Intent(ACTION_VIEW)
            intent.data = helpUrl.toUri()
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireActivity(), helpUrl, Toast.LENGTH_SHORT).show()
        }
    }

    private fun setTransactionState(transactionType: TransactionType, state: TransactionState) {
        if (state.isTransactionSuccessful) {
            when (transactionType) {
                TransactionType.BuyDash -> setDepositSuccess()
                TransactionType.BuySwap -> setConversionSuccess()
                TransactionType.TransferDash -> setTransferDashSuccess()
                TransactionType.SellSwap -> setConversionSuccess()
            }
        } else {
            when (transactionType) {
                TransactionType.BuyDash -> setDepositError(state.responseMessage)
                TransactionType.BuySwap -> setTransferDashError(state.responseMessage)
                TransactionType.TransferDash -> setTransferDashError(state.responseMessage)
                TransactionType.SellSwap -> setSellSwapError(state.responseMessage)
            }
        }
    }

    private fun setDepositSuccess() {
        currentType = MayaResultType.DEPOSIT_SUCCESS
        uiState = uiState.copy(
            isLoading = false,
            isSuccess = true,
            title = getString(R.string.purchase_successful),
            message = getString(R.string.maya_it_could_take_up_to_2_3_minutes),
            showContactSupport = false,
            buttonText = getString(R.string.button_close)
        )
    }

    private fun setDepositError(errorMessage: String?) {
        currentType = MayaResultType.DEPOSIT_ERROR
        val message = when {
            errorMessage.isNullOrEmpty() -> getString(R.string.transfer_failed_msg)
            errorMessage.contains(getString(R.string.send_to_wallet_error)) -> errorMessage
            else -> getString(R.string.transfer_failed_msg)
        }
        uiState = uiState.copy(
            isLoading = false,
            isSuccess = false,
            title = getString(R.string.transfer_failed),
            message = message,
            showContactSupport = true,
            buttonText = getString(R.string.button_retry)
        )
    }

    private fun setConversionSuccess() {
        currentType = MayaResultType.CONVERSION_SUCCESS
        val params = arguments?.let { MayaConvertResultFragmentArgs.fromBundle(it).transactionParams }
        val source = params?.coinbaseWalletName ?: org.dash.wallet.common.util.Constants.DASH_CURRENCY
        val destination = params?.params?.amount?.cryptoCode ?: getString(R.string.error)
        uiState = uiState.copy(
            isLoading = false,
            isSuccess = true,
            title = getString(R.string.conversion_successful),
            message = getString(R.string.maya_it_could_take_up_to_5_minutes, source, destination),
            showContactSupport = false,
            buttonText = getString(R.string.button_close)
        )
        showExplorerLink(params?.routeName, params?.params?.txid, params?.params?.depositAddress)
    }

    /**
     * Shows a link to the settlement network's explorer so the user can follow this swap.
     * Maya tracks a swap by its inbound (DASH) transaction hash, so with a [txid] the
     * link opens the swap itself on mayascan.org. NEAR Intents tracks a swap by the
     * one-time deposit address it issued, so NEAR routes link to
     * explorer.near-intents.org/transactions/[depositAddress]. If the identifier is
     * missing, the link falls back to the explorer's home page. Unrecognised routes show
     * nothing. Route classification mirrors the order preview: an empty route name
     * means Maya.
     */
    private fun showExplorerLink(routeName: String?, txid: String?, depositAddress: String?) {
        val raw = routeName?.trim().orEmpty()
        val isMayaRoute = raw.isEmpty() || raw.contains("MAYA", ignoreCase = true)
        val isNearRoute = !isMayaRoute && raw.contains("NEAR", ignoreCase = true)

        val explorer = when {
            isMayaRoute -> Triple(
                R.string.maya_explorer_description_maya,
                R.string.maya_explorer_view_maya,
                if (txid.isNullOrBlank()) {
                    getString(R.string.maya_explorer_url_maya)
                } else {
                    // MAYAChain indexes inbound transactions by uppercase hash.
                    getString(R.string.maya_explorer_tx_url_maya, txid.uppercase())
                }
            )
            isNearRoute -> Triple(
                R.string.maya_explorer_description_near,
                R.string.maya_explorer_view_near,
                if (depositAddress.isNullOrBlank()) {
                    getString(R.string.maya_explorer_url_near)
                } else {
                    getString(R.string.maya_explorer_tx_url_near, depositAddress)
                }
            )
            else -> null
        }

        explorerUrl = explorer?.third
        uiState = uiState.copy(
            explorerDescription = explorer?.let { getString(it.first) },
            explorerLinkText = explorer?.let { getString(it.second) }
        )
    }

    private fun setTransferDashSuccess() {
        currentType = MayaResultType.TRANSFER_DASH_SUCCESS
        uiState = uiState.copy(
            isLoading = false,
            isSuccess = true,
            title = getString(R.string.transfer_dash_successful),
            message = getString(R.string.maya_it_could_take_up_to_10_minutes),
            showContactSupport = false,
            buttonText = getString(R.string.button_close)
        )
    }

    private fun setTransferDashError(errorMessage: String?) {
        currentType = MayaResultType.TRANSFER_DASH_ERROR
        uiState = uiState.copy(
            isLoading = false,
            isSuccess = false,
            title = getString(R.string.transfer_failed),
            message = if (errorMessage.isNullOrEmpty()) {
                getString(R.string.transfer_dash_failed_msg)
            } else {
                errorMessage
            },
            showContactSupport = true,
            buttonText = getString(R.string.button_retry)
        )
    }

    private fun setSellSwapError(errorMessage: String?) {
        currentType = MayaResultType.SWAP_ERROR
        uiState = uiState.copy(
            isLoading = false,
            isSuccess = false,
            title = getString(R.string.conversion_failed),
            message = if (errorMessage.isNullOrEmpty()) {
                getString(R.string.transfer_failed_msg)
            } else {
                errorMessage
            },
            showContactSupport = true,
            buttonText = getString(R.string.button_retry)
        )
    }

    private fun handleBackPress() {
        // Block back navigation to prevent re-submitting the transaction
        onBackPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            // Intentionally consume back press without navigating for success states
            if (currentType != MayaResultType.TRANSFER_DASH_SUCCESS &&
                currentType != MayaResultType.CONVERSION_SUCCESS &&
                currentType != MayaResultType.DEPOSIT_SUCCESS
            ) {
                findNavController().popBackStack()
            }
        }
    }

    override fun onLockScreenActivated() {
        findNavController().popBackStack(R.id.mayaPortalFragment, false)
    }

    override fun onDestroy() {
        super.onDestroy()
        onBackPressedCallback?.remove()
    }
}
