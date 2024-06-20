/*
 * Copyright 2023 Dash Core Group.
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

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import org.dash.wallet.common.databinding.FragmentIntegrationPortalBinding
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.integrations.maya.R

@AndroidEntryPoint
class MayaPortalFragment : Fragment(R.layout.fragment_integration_portal) {

    private val binding by viewBinding(FragmentIntegrationPortalBinding::bind)
    private val viewModel by mayaViewModels<MayaViewModel>()
    private var balanceAnimator: ObjectAnimator? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding.balanceDash.isVisible = false
        binding.balanceDash.setFormat(viewModel.fiatFormat)
        binding.balanceDash.setApplyMarkup(false)

        binding.toolbarTitle.text = getString(R.string.maya_service_name)
        binding.toolbarIcon.setImageResource(R.drawable.ic_maya_logo)

        binding.balanceHeader.isVisible = false
        binding.dashIcon.isVisible = false
        binding.balanceDash.isVisible = false
        binding.balanceLocal.isVisible = false
        binding.lastKnownBalance.isVisible = false
        binding.additionalInfo.isVisible = false
        // we are only supporting sell swaps
        binding.convertBtn.isVisible = true
        binding.convertTitle.text = getString(R.string.maya_portal_convert_title)
        binding.convertSubtitle.text = getString(R.string.maya_portal_convert_subtitle)
        binding.buyBtn.isVisible = false
        binding.transferBtn.isVisible = false

        // Maya has no account
        binding.linkAccountBtn.isVisible = false
        binding.disconnectBtn.isVisible = false
        binding.disconnectedIndicator.isVisible = false

        binding.toolbar.setOnClickListener {
            findNavController().popBackStack()
        }

        binding.convertBtn.setOnClickListener {
            // TODO: add handler code here
            safeNavigate(MayaPortalFragmentDirections.mayaPortalToCurrencyPicker())
        }
    }
}
