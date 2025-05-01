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

package org.dash.wallet.features.exploredash.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.RecyclerView
import org.dash.wallet.common.ui.segmented_picker.SegmentedOption
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.SearchHeaderViewBinding
import org.dash.wallet.features.exploredash.ui.explore.ExploreTopic
import org.dash.wallet.features.exploredash.ui.explore.FilterMode

class SearchHeaderAdapter(private val topic: ExploreTopic) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private lateinit var binding: SearchHeaderViewBinding
    private var currentFilterOption = 0

    private var onFilterOptionChosen: ((FilterMode) -> Unit)? = null
    private var onSearchQueryChanged: ((String) -> Unit)? = null
    private var onSearchQuerySubmitted: (() -> Unit)? = null
    private var onFilterButtonClicked: (() -> Unit)? = null

    var title: String = ""
        set(value) {
            field = value

            if (::binding.isInitialized) {
                binding.searchTitle.text = value
            }
        }

    var subtitle: String = ""
        set(value) {
            field = value

            if (::binding.isInitialized) {
                binding.searchSubtitle.isVisible = value.isNotEmpty()
                binding.searchSubtitle.text = value
            }
        }

    private var _searchText = ""
    var searchText: String
        get() = _searchText
        set(value) {
            _searchText = value

            if (::binding.isInitialized) {
                binding.search.setText(value)
            }
        }

    var controlsVisible: Boolean = true
        set(value) {
            field = value
            if (::binding.isInitialized) {
                refreshControls(value)
            }
        }

    var allowSpaceForMessage: Boolean = false
        set(value) {
            field = value
            if (::binding.isInitialized) {
                refreshSpaceForMessage(value)
            }
        }

    var isFilterButtonVisible: Boolean = true
        set(value) {
            field = value
            if (::binding.isInitialized) {
                binding.filterBtn.isVisible = value
            }
        }

    override fun getItemCount() = 1

    override fun getItemViewType(position: Int) = R.layout.search_header_view

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        binding = SearchHeaderViewBinding.inflate(inflater, parent, false)

        return object : RecyclerView.ViewHolder(binding.root) {}
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        val options = binding.root.resources
            .getStringArray(
                if (topic == ExploreTopic.Merchants) {
                    R.array.merchants_filter_options
                } else {
                    R.array.atms_filter_options
                }
            )
            .map { SegmentedOption(it) }
        binding.filterOptions.provideOptions(options)
        binding.filterOptions.setSelectedIndex(currentFilterOption)
        binding.filterOptions.setOnOptionPickedListener { _, index ->
            onFilterOptionChosen?.invoke(
                if (topic == ExploreTopic.Merchants) {
                    when (index) {
                        0 -> FilterMode.Online
                        1 -> FilterMode.Nearby
                        else -> FilterMode.All
                    }
                } else {
                    when (index) {
                        1 -> FilterMode.Buy
                        2 -> FilterMode.Sell
                        3 -> FilterMode.BuySell
                        else -> FilterMode.All
                    }
                }
            )
        }

        binding.search.doOnTextChanged { text, _, _, _ ->
            _searchText = text.toString()
            binding.clearBtn.isVisible = !text.isNullOrEmpty()
            onSearchQueryChanged?.invoke(text.toString())
        }

        binding.search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                onSearchQuerySubmitted?.invoke()
            }

            true
        }

        binding.clearBtn.setOnClickListener { clearSearchQuery() }
        binding.filterBtn.setOnClickListener { onFilterButtonClicked?.invoke() }

        binding.search.setText(searchText)
        binding.searchTitle.text = title
        binding.searchSubtitle.text = subtitle
        binding.searchSubtitle.isVisible = subtitle.isNotEmpty()
        binding.filterBtn.isVisible = isFilterButtonVisible
        refreshControls(controlsVisible)
        refreshSpaceForMessage(allowSpaceForMessage)
    }

    fun setFilterMode(mode: FilterMode) {
        val index = if (topic == ExploreTopic.Merchants) {
            when (mode) {
                FilterMode.Online -> 0
                FilterMode.Nearby -> 1
                else -> 2
            }
        } else {
            when (mode) {
                FilterMode.Buy -> 1
                FilterMode.Sell -> 2
                FilterMode.BuySell -> 3
                else -> 0
            }
        }

        currentFilterOption = index

        if (::binding.isInitialized) {
            binding.filterOptions.setSelectedIndex(index)
        }
    }

    fun clearSearchQuery() {
        searchText = ""
    }

    fun setOnFilterOptionChosen(listener: (FilterMode) -> Unit) {
        onFilterOptionChosen = listener
    }

    fun setOnSearchQueryChanged(listener: (String) -> Unit) {
        onSearchQueryChanged = listener
    }

    fun setOnSearchQuerySubmitted(listener: () -> Unit) {
        onSearchQuerySubmitted = listener
    }

    fun setOnFilterButtonClicked(listener: () -> Unit) {
        onFilterButtonClicked = listener
    }

    private fun refreshControls(visible: Boolean) {
        binding.searchPanel.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        binding.titlePanel.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    private fun refreshSpaceForMessage(visible: Boolean) {
        binding.offset.isVisible = visible
    }
}
