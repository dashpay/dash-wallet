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
import androidx.fragment.app.viewModels
import org.dash.wallet.common.R
import org.dash.wallet.common.ui.address_input.AddressInputFragment
import org.dash.wallet.common.ui.address_input.AddressSource
import org.dash.wallet.common.ui.decorators.ListDividerDecorator
import org.dash.wallet.common.ui.recyclerview.IconifiedListAdapter
import org.dash.wallet.common.util.observe
import org.dash.wallet.common.util.safeNavigate

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
            setAddressSources(it)
        }
    }

    private fun clickListener(item: AddressSource) {
        if (item.address != null && item.address != "") {
            binding.addressInput.setText(item.address!!)
        }
    }

    override fun continueAction() {
        safeNavigate(
            MayaAddressInputFragmentDirections.mayaAddressInputToEnterAmount(
                viewModel.currency,
                viewModel.addressResult.paymentIntent!!
            )
        )
        // TODO: add event monitoring here
        // viewModel.logEvent(AnalyticsConstants.AddressInput.CONTINUE)
    }
}
