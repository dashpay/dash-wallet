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

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.address_input.AddressInputViewModel
import org.dash.wallet.common.ui.components.DashWalletTheme
import org.dash.wallet.common.ui.scan.ScanActivity
import org.dash.wallet.common.util.DeepLinkDestination
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.integrations.maya.payments.MayaCurrencyList
import org.slf4j.LoggerFactory
import org.dash.wallet.common.R as CommonR

/**
 * Maya sell "Enter address" screen (Figma node 24007:13081).
 *
 * Hosts the Compose [MayaAddressInputScreen] while keeping the original behavior of the
 * view-based [org.dash.wallet.common.ui.address_input.AddressInputFragment] base class:
 * address parsing/validation via [AddressInputViewModel], QR scanning, clipboard paste,
 * exchange address sources and the bootstrap-quote check before navigating to enter amount.
 */
@AndroidEntryPoint
class MayaAddressInputFragment : Fragment() {
    companion object {
        private val log = LoggerFactory.getLogger(MayaAddressInputFragment::class.java)
    }

    private val viewModel by viewModels<AddressInputViewModel>()
    private val mayaViewModel by mayaViewModels<MayaViewModel>()
    private val mayaAddressInputViewModel by viewModels<MayaAddressInputViewModel>()
    private val args by navArgs<MayaAddressInputFragmentArgs>()

    private var uiState by mutableStateOf(MayaAddressInputUIState())

    // Tracks clipboard availability so the clipboard row is re-derived when the clip changes
    // while this screen is open (AddressInputViewModel listens for primary-clip changes).
    private var hadClipboardText: Boolean? = null

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)?.let { scanned ->
                viewModel.setInput(normalizeCase(scanned.trim()))
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Same wiring the view-based fragment did in onViewCreated: the selected asset pins the
        // currency's payment parsers (falling back to the full Maya list).
        viewModel.currency = args.currency
        viewModel.paymentParsers = MayaCurrencyList.getPaymentProcessorForAsset(args.asset)
            ?: mayaViewModel.paymentParsers
        mayaAddressInputViewModel.setCurrency(args.currency)
        mayaAddressInputViewModel.asset = args.asset

        // The nav-arg title ("Convert DASH to %s") is intentionally not used here — the design
        // (Figma 24007:13081) titles this screen generically.
        uiState = uiState.copy(
            title = getString(R.string.maya_enter_address_title),
            fieldLabel = args.hint,
            // Restores the inline error shown before a configuration change.
            errorMessage = mayaAddressInputViewModel.inlineErrorMessage
        )

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                DashWalletTheme {
                    MayaAddressInputScreen(
                        state = uiState,
                        onBackClick = { findNavController().popBackStack() },
                        onAddressChanged = viewModel::setInput,
                        onScanClick = { launchScanner(this) },
                        onSourceClick = ::onSourceClick,
                        onClipboardClick = ::onClipboardClick,
                        onContinueClick = ::onContinue
                    )
                }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.uiState.observe(viewLifecycleOwner) { state ->
            // Any change of the entered address invalidates a previously shown error, exactly
            // like the old doOnTextChanged listener did. The comparison is against the
            // ViewModel-held last seen address (not the fragment's recreated uiState) so the
            // replay of the persisted address after a configuration change isn't mistaken
            // for an edit.
            if (state.addressInput != mayaAddressInputViewModel.lastSeenAddress) {
                mayaAddressInputViewModel.lastSeenAddress = state.addressInput
                mayaAddressInputViewModel.inlineErrorMessage = null
            }
            uiState = uiState.copy(
                address = state.addressInput,
                continueEnabled = state.addressInput.isNotEmpty(),
                errorMessage = mayaAddressInputViewModel.inlineErrorMessage
            )

            if (hadClipboardText != state.hasClipboardText) {
                hadClipboardText = state.hasClipboardText
                refreshClipboardRow()
            }
        }

        mayaAddressInputViewModel.addressSources.observe(viewLifecycleOwner) { sources ->
            uiState = uiState.copy(
                addressSources = sources.map { source ->
                    AddressSourceUIState(
                        id = source.id,
                        name = getString(source.name),
                        icon = source.icon,
                        address = source.address
                    )
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Clears the isLoading kept through a successful navigation (see onContinue) when the
        // user comes back to this screen.
        uiState = uiState.copy(isLoading = false)
        mayaAddressInputViewModel.refreshAddressSources()
        refreshClipboardRow()
    }

    private fun launchScanner(clickView: View) {
        viewModel.logEvent(AnalyticsConstants.AddressInput.SCAN_QR)
        scanLauncher.launch(ScanActivity.getTransitionIntent(requireActivity(), clickView))
    }

    private fun onSourceClick(source: AddressSourceUIState) {
        if (!source.address.isNullOrEmpty()) {
            viewModel.setInput(normalizeCase(source.address))
        } else {
            // exchange login
            findNavController().navigate(DeepLinkDestination.Exchange(source.id, "login_and_close").deepLink)
        }
    }

    private fun onClipboardClick() {
        uiState.clipboardAddress?.let { viewModel.setInput(normalizeCase(it)) }
    }

    /**
     * A bech32 address scanned or pasted in its all-uppercase QR form is shown and stored in
     * canonical lowercase; anything else (Base58, URIs, mixed case) is left as entered.
     */
    private fun normalizeCase(text: String): String {
        return viewModel.paymentParsers.getAddressParser(viewModel.currency)?.normalizeCase(text) ?: text
    }

    /**
     * Derives the Clipboard row of the sources card: the first address in the clipboard text
     * matching the selected currency's parser (the same matching the old highlighted
     * clipboard-content view used); null hides the row.
     */
    private fun refreshClipboardRow() {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(requireContext())?.toString()

        val address = text?.let { clipboardText ->
            viewModel.paymentParsers.getAddressParser(viewModel.currency)
                ?.findAll(clipboardText)
                ?.firstOrNull()
                ?.let { range -> clipboardText.substring(range.first, range.last) }
        }
        uiState = uiState.copy(clipboardAddress = address)
    }

    private fun onContinue() {
        if (uiState.isLoading) {
            return
        }

        lifecycleScope.launch {
            val input = uiState.address.trim()
            try {
                viewModel.parsePaymentIntent(input)
                viewModel.setAddressResult(input)
            } catch (ex: Exception) {
                log.error("problem processing $input", ex)
                // Address-format error: also restores the correct copy after a previous,
                // valid-format attempt replaced it with a swap-specific message.
                setInlineError(getString(CommonR.string.not_valid_address, viewModel.currency))
                return@launch
            }

            setInlineError(null)
            uiState = uiState.copy(isLoading = true)
            val quote = mayaAddressInputViewModel.getDefaultQuote(viewModel.addressResult.addressInputWithoutPrefix)

            if (quote != null && quote.error == null) {
                // Keep isLoading (Continue disabled) through the navigation so the button doesn't
                // flash enabled before the next screen appears; onResume resets it when the user
                // comes back, since this fragment instance survives on the back stack.
                safeNavigate(
                    MayaAddressInputFragmentDirections.mayaAddressInputToEnterAmount(
                        viewModel.currency,
                        mayaAddressInputViewModel.asset,
                        viewModel.addressResult.paymentIntent!!
                    )
                )
                // TODO: add event monitoring here
                // viewModel.logEvent(AnalyticsConstants.AddressInput.CONTINUE)
            } else {
                // Surface every quote error inline under the address input rather than a blocking
                // dialog, so the user can fix the address and retry without dismissing anything.
                // The message is resolved by the active backend's aggregator (Maya or SwapKit), so
                // this screen doesn't need to know which error vocabulary produced it.
                uiState = uiState.copy(isLoading = false)
                setInlineError(
                    getString(
                        mayaAddressInputViewModel.errorMessageRes(quote?.error),
                        viewModel.currency
                    )
                )
            }
        }
    }

    /** Shows/clears the inline validation error, mirroring it into the ViewModel so it survives rotation. */
    private fun setInlineError(message: String?) {
        mayaAddressInputViewModel.inlineErrorMessage = message
        uiState = uiState.copy(errorMessage = message)
    }
}
