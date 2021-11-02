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

package org.dash.wallet.features.exploredash.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.SearchHeaderViewBinding
import org.dash.wallet.features.exploredash.ui.ExploreTopic

class SearchHeaderAdapter(private val topic: ExploreTopic) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private lateinit var binding: SearchHeaderViewBinding
    private var onFilterOptionChosen: ((String, Int) -> Unit)? = null
    private var onSearchQueryChanged: ((String) -> Unit)? = null
    private var onSearchQuerySubmitted: ((Unit) -> Unit)? = null
    private var onFilterButtonClicked: ((Unit) -> Unit)? = null

    override fun getItemCount() = 1

    override fun getItemViewType(position: Int) = R.layout.search_header_view

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        binding = SearchHeaderViewBinding.inflate(inflater, parent, false)

        return object : RecyclerView.ViewHolder(binding.root) { }
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        binding.searchTitle.text = "United States" // TODO: use location to resolve

        binding.filterOptions.provideOptions(binding.root.resources.getStringArray(
            if (topic == ExploreTopic.Merchants) {
                R.array.merchants_filter_options
            } else {
                R.array.atms_filter_options
            }).toList()
        )
        binding.filterOptions.setOnOptionPickedListener { option, index ->
            onFilterOptionChosen?.invoke(option, index)
        }

        binding.search.doOnTextChanged { text, _, _, _ ->
            binding.clearBtn.isVisible = !text.isNullOrEmpty()
            onSearchQueryChanged?.invoke(text.toString())
        }

        binding.search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                onSearchQuerySubmitted?.invoke(Unit)
            }

            true
        }

        binding.clearBtn.setOnClickListener {
            binding.search.text.clear()
        }

        binding.filterBtn.setOnClickListener {
            onFilterButtonClicked?.invoke(Unit)
        }
    }

    fun setOnFilterOptionChosen(listener: (String, Int) -> Unit) {
        onFilterOptionChosen = listener
    }

    fun setOnSearchQueryChanged(listener: (String) -> Unit) {
        onSearchQueryChanged = listener
    }

    fun setOnSearchQuerySubmitted(listener: (Unit) -> Unit) {
        onSearchQuerySubmitted = listener
    }

    fun setOnFilterButtonClicked(listener: (Unit) -> Unit) {
        onFilterButtonClicked = listener
    }
}