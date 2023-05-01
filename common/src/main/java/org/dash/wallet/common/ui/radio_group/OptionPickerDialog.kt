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

package org.dash.wallet.common.ui.radio_group

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.DialogOptionPickerBinding
import org.dash.wallet.common.ui.decorators.ListDividerDecorator
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.viewBinding

class OptionPickerDialog(
    private val dialogTitle: String,
    private val itemList: List<IconifiedViewItem>,
    private val selectedIndex: Int = 0,
    private val showSearch: Boolean = true,
    private val useCheckMark: Boolean = false,
    private val clickListener: (IconifiedViewItem, Int, DialogFragment) -> Unit
) : OffsetDialogFragment(R.layout.dialog_option_picker) {
    private val binding by viewBinding(DialogOptionPickerBinding::bind)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.searchBox.isVisible = showSearch
        binding.searchTitle.text = dialogTitle

        val adapter = RadioGroupAdapter(selectedIndex, useCheckMark) { item, index ->
            clickListener.invoke(item, index, this)
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
        adapter.submitList(itemList)
        binding.contentList.scrollToPosition(selectedIndex)

        binding.searchQuery.doOnTextChanged { text, _, _, _ ->
            binding.clearBtn.isVisible = !text.isNullOrEmpty()
            if (text.isNullOrBlank()) {
                adapter.submitList(itemList)
            } else {
                val filteredList = itemList.filter {
                    it.title.lowercase().contains(text.toString().lowercase())
                }
                adapter.submitList(filteredList)
            }
        }

        binding.searchQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val inputManager = requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputManager.toggleSoftInput(0, 0)
            }

            true
        }

        binding.clearBtn.setOnClickListener {
            binding.searchQuery.text.clear()
        }
    }

    override fun dismiss() {
        lifecycleScope.launch {
            delay(300)
            super.dismiss()
        }
    }
}
