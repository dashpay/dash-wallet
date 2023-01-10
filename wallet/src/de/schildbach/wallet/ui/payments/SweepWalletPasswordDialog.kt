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

package de.schildbach.wallet.ui.payments

import android.os.Bundle
import android.view.View
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.SweepWalletDecryptDialogBinding
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding

@AndroidEntryPoint
class SweepWalletPasswordDialog : AdaptiveDialog(R.layout.sweep_wallet_decrypt_dialog) {
    private val binding by viewBinding(SweepWalletDecryptDialogBinding::bind)

    val password: String
        get() = binding.sweepWalletFragmentPassword.text.toString()

    var isBadPassword = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        arguments = bundleOf (
            ICON_RES_ARG to R.drawable.ic_info_blue,
            TITLE_ARG to getString(R.string.sweep_wallet_fragment_encrypted),
            MESSAGE_ARG to "",
            NEG_BUTTON_ARG to "",
            POS_BUTTON_ARG to getString(R.string.sweep_wallet_fragment_button_decrypt)
        )

        super.onViewCreated(view, savedInstanceState)
        binding.sweepWalletFragmentBadPassword.isVisible = isBadPassword
    }
}