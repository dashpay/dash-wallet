/*
 * Copyright 2021 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dash.wallet.features.exploredash.ui.dialogs

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.ListDividerDecorator
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.DialogTerritoryFilterBinding
import org.dash.wallet.features.exploredash.ui.adapters.RadioGroupAdapter
import org.dash.wallet.features.exploredash.ui.viewitems.IconifiedViewItem

class TerritoryFilterDialog(
    private val itemList: List<IconifiedViewItem>,
    private val selectedIndex: Int,
    private val clickListener: (IconifiedViewItem, Int, DialogFragment) -> Unit
) : OffsetDialogFragment<LinearLayout>() {
    private val binding by viewBinding(DialogTerritoryFilterBinding::bind)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_territory_filter, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.searchTitle.text = getString(R.string.explore_location)

        val adapter = RadioGroupAdapter(selectedIndex) { item, index ->
            clickListener.invoke(item, index, this)
        }
        val divider = ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!
        val decorator = ListDividerDecorator(
            divider,
            showAfterLast = false,
            marginStart = resources.getDimensionPixelOffset(R.dimen.divider_margin_start)
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
                    it.name.lowercase().contains(text.toString().lowercase())
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