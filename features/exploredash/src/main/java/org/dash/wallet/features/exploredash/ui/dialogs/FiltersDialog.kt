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
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.dash.wallet.common.ui.ListDividerDecorator
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.PaymentMethod
import org.dash.wallet.features.exploredash.databinding.DialogFiltersBinding
import org.dash.wallet.features.exploredash.ui.ExploreTopic
import org.dash.wallet.features.exploredash.ui.ExploreViewModel
import org.dash.wallet.features.exploredash.ui.adapters.RadioGroupAdapter
import org.dash.wallet.features.exploredash.ui.isLocationPermissionGranted
import org.dash.wallet.features.exploredash.ui.viewitems.IconifiedViewItem

@FlowPreview
@ExperimentalCoroutinesApi
class FiltersDialog: OffsetDialogFragment<LinearLayout>(
    R.drawable.gray_background_rounded
) {
    private var selectedTerritory: String = ""
    private var selectedRadiusOption: Int = 20
    private var dashPaymentOn: Boolean = true
    private var giftCardPaymentOn: Boolean = true

    private val binding by viewBinding(DialogFiltersBinding::bind)
    private val viewModel: ExploreViewModel by activityViewModels()
    private var territoriesJob: Deferred<List<String>>? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_filters, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRadiusOptions()

        if (viewModel.exploreTopic == ExploreTopic.Merchants) {
            setupPaymentMethods()
        } else {
            binding.paymentMethods.isVisible = false
            binding.paymentMethodsLabel.isVisible = false
        }

        setupTerritoryFilter()
        setupLocationPermission()

        binding.applyButton.setOnClickListener {
            viewModel.pickedTerritory = selectedTerritory
            viewModel.selectedRadiusOption = selectedRadiusOption

            if (viewModel.exploreTopic == ExploreTopic.Merchants) {
                var paymentFilter = ""

                if (!dashPaymentOn || !giftCardPaymentOn) {
                    paymentFilter = if (dashPaymentOn) {
                        PaymentMethod.DASH
                    } else {
                        PaymentMethod.GIFT_CARD
                    }
                }

                viewModel.paymentMethodFilter = paymentFilter
            }

            dismiss()
        }

        binding.resetFiltersBtn.setOnClickListener {
            it.isEnabled = false
        }
    }

    private fun setupPaymentMethods() {
        val isDashOn = viewModel.paymentMethodFilter.isEmpty() ||
                    viewModel.paymentMethodFilter == PaymentMethod.DASH
        dashPaymentOn = isDashOn
        binding.dashOption.isChecked = isDashOn
        binding.dashOption.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !binding.giftCardOption.isChecked) {
                // shouldn't allow to uncheck both options
                binding.giftCardOption.isChecked = true
            }

            dashPaymentOn = isChecked
        }

        val isGiftCardOn = viewModel.paymentMethodFilter.isEmpty() ||
                viewModel.paymentMethodFilter == PaymentMethod.GIFT_CARD
        giftCardPaymentOn = isGiftCardOn
        binding.giftCardOption.isChecked = isGiftCardOn
        binding.giftCardOption.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !binding.dashOption.isChecked) {
                binding.dashOption.isChecked = true
            }

            giftCardPaymentOn = isChecked
        }
    }

    private fun setupRadiusOptions() {
        selectedRadiusOption = viewModel.selectedRadiusOption

        val options = listOf(1, 5, 20, 50)
        val optionNames = binding.root.resources.getStringArray(
            if (viewModel.isMetric) R.array.radius_filter_options_kilometers else R.array.radius_filter_options_miles
        ).map { IconifiedViewItem(it, null) }

        val radiusOption = viewModel.selectedRadiusOption
        val adapter = RadioGroupAdapter(options.indexOf(radiusOption)) { _, optionIndex ->
            selectedRadiusOption = options[optionIndex]
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

/// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        val options1 = listOf(20, 50, 100, 500)
        val optionNames1 = options1.map { IconifiedViewItem("${it} markers") }

        val adapter1 = RadioGroupAdapter(options1.indexOf(viewModel.maxMarkers)) { _, optionIndex ->
            viewModel.maxMarkers = options1[optionIndex]
        }
        val divider1 = ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!
        val decorator1 = ListDividerDecorator(
            divider1,
            showAfterLast = false,
            marginStart = resources.getDimensionPixelOffset(R.dimen.divider_margin_start)
        )
        binding.markerAmount.addItemDecoration(decorator1)
        binding.markerAmount.adapter = adapter1
        adapter1.submitList(optionNames1)
    }

     private fun setTerritoryName(territory: String) {
        binding.locationName.text = if (territory.isEmpty()) {
            getString(R.string.explore_all_states)
        } else {
            territory
        }
         selectedTerritory = territory
    }

    private fun setupTerritoryFilter() {
        setTerritoryName(viewModel.pickedTerritory)

        lifecycleScope.launch {
            territoriesJob = async {
                viewModel.getTerritoriesWithPOIs().sorted()
            }
        }

        binding.locationBtn.setOnClickListener {
            val firstOption = if (viewModel.isLocationEnabled.value == true) {
                IconifiedViewItem(getString(R.string.explore_current_location), R.drawable.ic_current_location)
            } else {
                IconifiedViewItem(getString(R.string.explore_all_states))
            }

            lifecycleScope.launch {
                val territories = territoriesJob?.await() ?: listOf()
                val fullTerritoryList = listOf(firstOption) + territories.map { IconifiedViewItem(it) }

                val selectedIndex = if (selectedTerritory.isEmpty()) {
                    0
                } else {
                    territories.indexOf(selectedTerritory) + 1
                }
                TerritoryFilterDialog(fullTerritoryList, selectedIndex) { item, _, dialog ->
                    dialog.dismiss()
                    setTerritoryName(item.name)
                }.show(parentFragmentManager, "territory_filter")
            }
        }
    }

    private fun setupLocationPermission() {
        binding.locationStatus.text = getString(if (isLocationPermissionGranted) {
            R.string.explore_location_allowed
        } else {
            R.string.explore_location_denied
        })
    }
}