/*
 * Copyright 2021 Dash Core Group.
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
package org.dash.wallet.integration.coinbase_integration.ui

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.View
import androidx.fragment.app.activityViewModels
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integration.coinbase_integration.R
import org.dash.wallet.integration.coinbase_integration.databinding.TransferDashFragmentBinding
import org.dash.wallet.integration.coinbase_integration.viewmodels.EnterAmountToTransferViewModel
import org.dash.wallet.integration.coinbase_integration.viewmodels.TransferDashViewModel

@ExperimentalCoroutinesApi
@AndroidEntryPoint
class TransferDashFragment : Fragment(R.layout.transfer_dash_fragment) {

    companion object {
        fun newInstance() = TransferDashFragment()
    }

    private val enterAmountToTransferViewModel by activityViewModels<EnterAmountToTransferViewModel>()
    private val transferDashViewModel by activityViewModels<TransferDashViewModel>()
    private val binding by viewBinding(TransferDashFragmentBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    }

}