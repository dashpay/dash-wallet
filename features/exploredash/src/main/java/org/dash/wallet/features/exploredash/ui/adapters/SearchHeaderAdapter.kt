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

    override fun getItemCount() = 1

    override fun getItemViewType(position: Int) = R.layout.search_header_view

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        binding = SearchHeaderViewBinding.inflate(inflater, parent, false)

        return object : RecyclerView.ViewHolder(binding.root) { }
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
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

        binding.searchTitle.text = title
        binding.searchSubtitle.text = subtitle
        binding.searchSubtitle.isVisible = subtitle.isNotEmpty()
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