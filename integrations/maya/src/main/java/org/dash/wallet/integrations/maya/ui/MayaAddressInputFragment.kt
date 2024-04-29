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

import android.os.Bundle
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.launch
import org.dash.wallet.integrations.maya.R
import org.dash.wallet.common.ui.address_input.AddressInputFragment
import org.dash.wallet.common.ui.address_input.AddressSource
import org.dash.wallet.common.ui.decorators.ListDividerDecorator
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.recyclerview.IconifiedListAdapter
import org.dash.wallet.common.util.DeepLinkDestination
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.maya.model.MayaErrorType
import org.dash.wallet.integrations.maya.model.getMayaErrorString
import org.dash.wallet.integrations.maya.model.getMayaErrorType

class MayaAddressInputFragment : AddressInputFragment() {
    private val mayaViewModel by viewModels<MayaViewModel>()
    private val mayaAddressInputViewModel by viewModels<MayaAddressInputViewModel>()
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.paymentParsers = mayaViewModel.paymentParsers
        mayaAddressInputViewModel.setCurrency(viewModel.currency)
        adapter = IconifiedListAdapter() { _, index ->
            val item = viewModel.addressSources[index]
            clickListener(item)
        }

        requireArguments().getString("asset")?.let {
            mayaAddressInputViewModel.asset = it
        }

        val divider = ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!
        val decorator = ListDividerDecorator(
            divider,
            showAfterLast = false,
            marginStart = resources.getDimensionPixelOffset(R.dimen.divider_margin_horizontal),
            marginEnd = resources.getDimensionPixelOffset(R.dimen.divider_margin_horizontal)
        )
        binding.contentList.addItemDecoration(decorator)
        binding.contentList.adapter = adapter

        mayaAddressInputViewModel.addressSources.observe(viewLifecycleOwner) {
            setAddressSources(it, getString(R.string.input_connect))
        }
    }

    private fun clickListener(item: AddressSource) {
        if (item.address != null && item.address != "") {
            binding.addressInput.setText(item.address!!)
        } else {
            // exchange login
            findNavController().navigate(DeepLinkDestination.Exchange(item.id, "login_and_close").deepLink)
        }
    }

    override fun continueAction() {
        lifecycleScope.launch {
            val quote = mayaAddressInputViewModel.getDefaultQuote()
            if (quote != null && quote.error == null) {
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
                if (getMayaErrorType(quote?.error ?: "") == MayaErrorType.INVALID_DESTINATION_ADDRESS) {
                    // show error
                    binding.inputWrapper.isErrorEnabled = true
                    binding.errorText.isVisible = true
                } else {
                    val message: String = if (quote?.error.isNullOrBlank()) {
                        requireContext().getString(R.string.something_wrong_title)
                    } else {
                        // get localized error
                        getMayaErrorString(quote?.error ?: "")?.let { id -> getString(id, viewModel.currency) }
                            ?: requireContext().getString(R.string.something_wrong_title)
                    }

                    AdaptiveDialog.create(
                        R.drawable.ic_error,
                        getString(org.dash.wallet.integrations.maya.R.string.error),
                        message,
                        getString(org.dash.wallet.integrations.maya.R.string.button_close)
                    ).show(requireActivity())
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mayaAddressInputViewModel.refreshAddressSources()
    }
}
