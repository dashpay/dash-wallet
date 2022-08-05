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

package de.schildbach.wallet.ui.dashpay.transactions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenCreated
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogPrivateMemoBinding
import kotlinx.coroutines.delay
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding

class PrivateMemoDialog: OffsetDialogFragment() {
    private val binding by viewBinding(DialogPrivateMemoBinding::bind)
    override val forceExpand: Boolean = true
    private val keyboardUtil = KeyboardUtil()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        lifecycleScope.launchWhenCreated {
            delay(250) // Wait for the dialog animation to finish before raising keyboard
            keyboardUtil.enableAdjustLayout(requireActivity().window, binding.root)
            KeyboardUtil.showSoftKeyboard(requireContext(), binding.memoInput)
        }

        return inflater.inflate(R.layout.dialog_private_memo, container, false)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        keyboardUtil.disableAdjustLayout()
    }
}