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
        val params = arguments?.let { MayaConvertResultFragmentArgs.fromBundle(it).transactionParams }

        // All the type/outcome → content decisions live in the (unit-tested) mapper; this
        // fragment only resolves the resource IDs it returns.
        val spec = MayaConvertResultStateMapper.buildResultSpec(
            type = transactionType,
            isSuccess = state.isTransactionSuccessful,
            errorMessage = state.responseMessage,
            sendToWalletError = getString(R.string.send_to_wallet_error),
            conversionSource = params?.coinbaseWalletName ?: org.dash.wallet.common.util.Constants.DASH_CURRENCY,
            conversionDestination = params?.params?.amount?.cryptoCode ?: getString(R.string.error)
        )
        currentType = spec.resultType

        // Explorer link so the user can follow a successful swap on the settlement network.
        val explorer = if (spec.resultType == MayaResultType.CONVERSION_SUCCESS) {
            MayaConvertResultStateMapper.explorerFor(
                routeName = params?.routeName,
                txid = params?.params?.txid,
                depositAddress = params?.params?.depositAddress
            )
        } else {
            null
        }
        explorerUrl = explorer?.let { spec2 ->
            spec2.urlArg?.let { getString(spec2.urlRes, it) } ?: getString(spec2.urlRes)
        }

        uiState = uiState.copy(
            isLoading = false,
            isSuccess = spec.isSuccess,
            title = getString(spec.titleRes),
            message = spec.messageText
                ?: getString(spec.messageRes!!, *spec.messageArgs.toTypedArray()),
            showContactSupport = spec.showContactSupport,
            buttonText = getString(spec.buttonTextRes),
            explorerDescription = explorer?.let { getString(it.descriptionRes) },
            explorerLinkText = explorer?.let { getString(it.linkTextRes) }
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
