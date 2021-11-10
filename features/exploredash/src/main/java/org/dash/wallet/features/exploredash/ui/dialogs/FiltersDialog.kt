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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.ListDividerDecorator
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.databinding.DialogFiltersBinding
import org.dash.wallet.features.exploredash.ui.adapters.RadioGroupAdapter

data class FilterOptionSet(
    var selectedTerritory: String,
    var selectedRadiusOption: Int,
    var dashPaymentOn: Boolean,
    var giftCardPaymentOn: Boolean
)

class FiltersDialog(
    private val territories: List<String>,
    private val showPaymentOptions: Boolean,
    private val isMetric: Boolean,
    private var currentOptions: FilterOptionSet,
    private val filtersAppliedListener: (FilterOptionSet, DialogFragment) -> Unit
) : OffsetDialogFragment(R.drawable.gray_background_rounded) {

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
        setupRadiusOptions()

        if (showPaymentOptions) {
            setupPaymentMethods()
        } else {
            binding.paymentMethods.isVisible = false
            binding.paymentMethodsLabel.isVisible = false
        }

        binding.locationBtn.setOnClickListener {
            TerritoryFilterDialog(territories, currentOptions.selectedTerritory) { name, dialog ->
                dialog.dismiss()
                currentOptions.selectedTerritory = name
                setLocationName()
            }.show(parentFragmentManager, "territory_filter")
        }

        binding.applyButton.setOnClickListener {
            filtersAppliedListener.invoke(currentOptions, this)
        }

        binding.resetFiltersBtn.setOnClickListener {
            it.isEnabled = false
        }
    }

    private fun setupPaymentMethods() {
        binding.dashOption.isChecked = currentOptions.dashPaymentOn
        binding.dashOption.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !binding.giftCardOption.isChecked) {
                // shouldn't allow to uncheck both options
                binding.giftCardOption.isChecked = true
            }

            currentOptions.dashPaymentOn = isChecked
        }

        binding.giftCardOption.isChecked = currentOptions.giftCardPaymentOn
        binding.giftCardOption.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !binding.dashOption.isChecked) {
                binding.dashOption.isChecked = true
            }

            currentOptions.giftCardPaymentOn = isChecked
        }
    }

    private fun setupRadiusOptions() {
        val options = listOf(1, 5, 20, 50)
        val optionNames = binding.root.resources.getStringArray(
            if (isMetric) R.array.radius_filter_options_kilometers else R.array.radius_filter_options_miles
        ).toList()
        val radiusOption = currentOptions.selectedRadiusOption
        val adapter = RadioGroupAdapter(options.indexOf(radiusOption)) { _, optionIndex ->
            currentOptions.selectedRadiusOption = options[optionIndex]
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
    }
        

     private fun setLocationName() {
        binding.locationName.text = if (currentOptions.selectedTerritory.isEmpty()) {
            getString(R.string.explore_all_states)
        } else {
            currentOptions.selectedTerritory
        }
    }

    override fun dismiss() {
        lifecycleScope.launch {
            delay(300)
            super.dismiss()
        }
    }
}