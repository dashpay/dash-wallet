/*
 * Copyright 2022 Dash Core Group.
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

package org.dash.wallet.integrations.crowdnode.ui.dialogs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.databinding.FragmentConfirmationBinding
import org.dash.wallet.integrations.crowdnode.model.OnlineAccountStatus
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants

class ConfirmationDialog : OffsetDialogFragment(R.layout.fragment_confirmation) {
    private val binding by viewBinding(FragmentConfirmationBinding::bind)
    private val viewModel by activityViewModels<CrowdNodeViewModel>()
    private var qrDialog: DialogFragment? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val accountAddress = viewModel.accountAddress.value
        requireNotNull(accountAddress)
        val amount = CrowdNodeConstants.API_CONFIRMATION_DASH_AMOUNT

        binding.description1.text = getString(
            R.string.crowdnode_how_to_verify_description1,
            amount.toFriendlyString()
        )
        binding.primaryDashAddress.text = viewModel.primaryDashAddress.toString()
        binding.copyPrimaryAddressBtn.setOnClickListener {
            viewModel.copyPrimaryAddress()
            Toast.makeText(requireContext(), getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }
        binding.dashAddress.text = viewModel.accountAddress.value.toString()
        binding.copyAddressBtn.setOnClickListener {
            viewModel.copyAccountAddress()
            Toast.makeText(requireContext(), getString(R.string.copied), Toast.LENGTH_SHORT).show()
        }

        binding.howToBtn.setOnClickListener {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.crowdnode_how_to_verify_url)))
            startActivity(browserIntent)
        }
        binding.showQrBtn.setOnClickListener {
            qrDialog = QRDialog(accountAddress, amount)
            qrDialog?.show(parentFragmentManager, "qr_dialog")
        }
        binding.shareUrlBtn.setOnClickListener {
            viewModel.shareConfirmationPaymentUrl()
        }

        viewModel.setConfirmationDialogShown(true)
        viewModel.observeOnlineAccountStatus().observe(viewLifecycleOwner) { status ->
            if (status == OnlineAccountStatus.Done) {
                qrDialog?.dismiss()
                dismiss()
            }
        }

        viewModel.observeCrowdNodeError().observe(viewLifecycleOwner) { error ->
            if (error != null) {
                qrDialog?.dismiss()
                dismiss()
            }
        }
    }
}
