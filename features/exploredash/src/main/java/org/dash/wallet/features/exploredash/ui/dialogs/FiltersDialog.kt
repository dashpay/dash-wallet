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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.ListDividerDecorator
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.DialogFiltersBinding
import org.dash.wallet.features.exploredash.ui.adapters.RadioGroupAdapter


class FiltersDialog(
    private val territories: List<String>,
    private var selectedRadius: Int = 1,
    private var selectedTerritory: String = "",
    private val territoryPickedListener: (String, DialogFragment) -> Unit,
    private val radiusPickedListener: (Int, DialogFragment) -> Unit
) : OffsetDialogFragment() {

    private val binding by viewBinding(DialogFiltersBinding::bind)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_filters, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setLocationName()

        val options = listOf(1, 5, 20, 50)
        val optionNames = binding.root.resources.getStringArray(R.array.radius_filter_options).toList() // TODO metric
        val adapter = RadioGroupAdapter(options.indexOf(selectedRadius)) { _, optionIndex ->
            radiusPickedListener.invoke(options[optionIndex], this)
        }
        val divider = ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!
        val decorator = ListDividerDecorator(
            divider,
            showAfterLast = false,
            marginStart = resources.getDimensionPixelOffset(R.dimen.divider_margin_start)
        )
        binding.radiusFilter.addItemDecoration(decorator)
        binding.radiusFilter.adapter = adapter
        adapter.submitList(optionNames)

        binding.locationBtn.setOnClickListener {
            TerritoryFilterDialog(territories, selectedTerritory) { name, dialog ->
                dialog.dismiss()
                selectedTerritory = name
                setLocationName()
                territoryPickedListener.invoke(name, this@FiltersDialog)
            }.show(parentFragmentManager, "territory_filter")
        }
    }

    private fun setLocationName() {
        binding.locationName.text = if (selectedTerritory.isEmpty()) territories.first() else selectedTerritory
    }

    override fun dismiss() {
        lifecycleScope.launch {
            delay(300)
            super.dismiss()
        }
    }
}