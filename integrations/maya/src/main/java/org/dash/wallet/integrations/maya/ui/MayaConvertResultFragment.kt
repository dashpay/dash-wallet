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
import android.content.DialogInterface
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.ui.dialogs.ComposeBottomSheet
import org.dash.wallet.common.util.openCustomTab
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.model.MayaResultType
import org.dash.wallet.integrations.maya.model.TransactionType
import org.dash.wallet.integrations.maya.ui.convert_currency.ConvertViewViewModel

/**
 * Maya transaction-result bottom sheet (Figma 34195:9065), expanded to full height. Hosts the
 * Compose [MayaConvertResultScreen] and keeps the original behavior: translating transaction
 * type + outcome into the displayed result, the Maya support link and the settlement-network
 * explorer link. Dismissal is blocked after success (the swap is already sent) — the Close
 * button is the only way out; error states can be dismissed with back/swipe, which pops back
 * like the old screen.
 *
 * The wallet lock screen auto-dismisses all dialogs; the preview screen re-shows this sheet
 * from [ConvertViewViewModel.pendingConversionResult] once the lock screen goes away, and the
 * pending record is cleared here when the user acknowledges the result.
 */
@AndroidEntryPoint
class MayaConvertResultFragment : ComposeBottomSheet() {

    override val forceExpand: Boolean = true

    private val viewModel by viewModels<MayaConvertResultViewModel>()
    private val convertViewModel by mayaViewModels<ConvertViewViewModel>()
    private var currentType: MayaResultType? = null
    private var explorerUrl: String? = null

    private var uiState by mutableStateOf(MayaConvertResultUIState())

    @Composable
    override fun Content() {
        MayaConvertResultScreen(
            state = uiState,
            onButtonClick = ::handlePositiveButtonClick,
            onContactSupportClick = ::openMayaHelp,
            onExplorerLinkClick = {
                explorerUrl?.let { requireActivity().openCustomTab(it) }
            }
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Not dismissable while the result is being determined; per-state below.
        isCancelable = false

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
                convertViewModel.pendingConversionResult = null
                findNavController().popBackStack()
            }
            MayaResultType.CONVERSION_SUCCESS,
            MayaResultType.DEPOSIT_SUCCESS,
            MayaResultType.TRANSFER_DASH_SUCCESS -> {
                viewModel.logClose(type)
                // The flow is finished: drop everything it persisted (entered amount, pending
                // result) so nothing is restored on a later visit. The fragment-scoped saved
                // state (entered address, order/quote time) dies with the screens popped below.
                convertViewModel.clearSavedState()
                val navController = findNavController()
                navController.popBackStack(navController.graph.startDestinationId, false)
            }
            else -> {}
        }
    }

    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        // The user dismissed an error state via back/swipe: the result was acknowledged, so it
        // must not be re-shown after an unlock. (The lock screen tears the sheet down with
        // dismissAllowingStateLoss, which doesn't come through here — the pending record
        // survives that on purpose.)
        convertViewModel.pendingConversionResult = null
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

        // A successful swap is already sent — the sheet must not be swiped/backed away, only
        // closed via the button (which pops home). Errors can be dismissed, popping back to the
        // preview like the old screen's back handling.
        isCancelable = !spec.isSuccess

        // Explorer link so the user can follow the swap on the settlement network (mayascan
        // for Maya routes, nearintents.org for NEAR routes). Shown for successful conversions,
        // and for error states where a transaction was actually generated — the funds left the
        // wallet, so the user needs a way to follow what happened to them.
        val hasTransaction = !params?.params?.txid.isNullOrBlank() ||
            !params?.params?.depositAddress.isNullOrBlank()
        val explorer = if (spec.resultType == MayaResultType.CONVERSION_SUCCESS ||
            (!spec.isSuccess && hasTransaction)
        ) {
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
            explorerLinkText = explorer?.let { getString(it.linkTextRes) },
            buttonText = getString(spec.buttonTextRes)
        )
    }
}
