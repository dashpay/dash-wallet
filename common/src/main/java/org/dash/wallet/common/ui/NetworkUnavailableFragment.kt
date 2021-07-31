/*
 * Copyright 2020 Dash Core Group.
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

package org.dash.wallet.common.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.NetworkUnavailableFragmentBinding

class NetworkUnavailableFragment(val messageId: Int = 0, val buttonTextId: Int = 0) :
    Fragment(R.layout.network_unavailable_fragment) {

    private lateinit var viewModel: NetworkUnavailableFragmentViewModel

    companion object {
        fun newInstance(
            messageId: Int = R.string.network_unavailable_check_connection,
            buttonTextId: Int = 0
        ): NetworkUnavailableFragment {
            return NetworkUnavailableFragment(messageId, buttonTextId)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel =
            ViewModelProvider(requireActivity())[NetworkUnavailableFragmentViewModel::class.java]
        //use message id
        val binding = NetworkUnavailableFragmentBinding.bind(view)
        if (messageId != 0) {
            binding.networkErrorSubtitle.text = getString(messageId)
        }
        if (buttonTextId != 0) {
            binding.button.isVisible = true
            binding.button.text = getString(buttonTextId)
            binding.button.setOnClickListener {
                viewModel.clickButton.call()
            }
        }
    }
}