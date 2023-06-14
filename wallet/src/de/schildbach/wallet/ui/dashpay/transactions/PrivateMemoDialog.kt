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
import android.view.View
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.DialogPrivateMemoBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.util.KeyboardUtil
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding

@AndroidEntryPoint
class PrivateMemoDialog: OffsetDialogFragment(R.layout.dialog_private_memo) {
    companion object {
        const val TX_ID_ARG = "tx_id"
    }

    override val forceExpand: Boolean = true
    private val keyboardUtil = KeyboardUtil()

    private val binding by viewBinding(DialogPrivateMemoBinding::bind)
    private val viewModel: PrivateMemoViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        lifecycleScope.launchWhenCreated {
            delay(250) // Wait for the dialog animation to finish before raising keyboard
            keyboardUtil.enableAdjustLayout(requireActivity().window, binding.root)
            KeyboardUtil.showSoftKeyboard(requireContext(), binding.memoInput)
        }

        requireArguments().apply {
            val txId = get(TX_ID_ARG) as Sha256Hash
            viewModel.init(txId)
        }

        binding.inputWrapper.counterMaxLength = PrivateMemoViewModel.MAX_MEMO_CHARS
        binding.memoInput.doAfterTextChanged {
            viewModel.memo.value = it?.toString() ?: ""
        }

        binding.continueBtn.setOnClickListener {
            lifecycleScope.launch {
                KeyboardUtil.hideKeyboard(requireContext(), binding.memoInput)
                viewModel.saveMemo()
                dismiss()
            }
        }

        viewModel.canSave.observe(viewLifecycleOwner) {
            binding.continueBtn.isEnabled = it
        }

        viewModel.memo.observe(viewLifecycleOwner) {
            if (binding.memoInput.text.toString() != it) {
                binding.memoInput.setText(it)
                binding.memoInput.setSelection(binding.memoInput.length())
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        keyboardUtil.disableAdjustLayout()
    }
}