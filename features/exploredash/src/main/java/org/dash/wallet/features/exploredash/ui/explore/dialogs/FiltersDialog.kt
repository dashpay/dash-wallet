/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui.explore.dialogs

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.dialogs.OffsetDialogFragment
import org.dash.wallet.common.ui.radio_group.IconifiedViewItem
import org.dash.wallet.common.ui.radio_group.OptionPickerDialog
import org.dash.wallet.common.ui.radio_group.RadioGroupAdapter
import org.dash.wallet.common.ui.radio_group.setupRadioGroup
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.explore.model.PaymentMethod
import org.dash.wallet.features.exploredash.data.explore.model.SortOption
import org.dash.wallet.features.exploredash.databinding.DialogFiltersBinding
import org.dash.wallet.features.exploredash.ui.explore.DenomOption
import org.dash.wallet.features.exploredash.ui.explore.ExploreTopic
import org.dash.wallet.features.exploredash.ui.explore.ExploreViewModel
import org.dash.wallet.features.exploredash.ui.explore.FilterMode
import org.dash.wallet.features.exploredash.ui.extensions.isLocationPermissionGranted
import org.dash.wallet.features.exploredash.ui.extensions.openAppSettings
import org.dash.wallet.features.exploredash.ui.extensions.registerPermissionLauncher
import org.dash.wallet.features.exploredash.ui.extensions.runLocationFlow
import org.dash.wallet.features.exploredash.utils.exploreViewModels

@AndroidEntryPoint
class FiltersDialog : OffsetDialogFragment(R.layout.dialog_filters) {
    override val backgroundStyle = R.style.PrimaryBackground

    private val radiusOptions = listOf(1, 5, 20, 50)
    private var selectedRadiusOption: Int = ExploreViewModel.DEFAULT_RADIUS_OPTION
    private var sortOption = ExploreViewModel.DEFAULT_SORT_OPTION
    private var selectedTerritory: String = ""
    private var dashPaymentOn: Boolean = true
    private var giftCardPaymentOn: Boolean = true
    private var sortOptions = listOf<SortOption>()

    private val binding by viewBinding(DialogFiltersBinding::bind)
    private val viewModel by exploreViewModels<ExploreViewModel>()

    private var territoriesJob: Deferred<List<String>>? = null
    private var radiusOptionsAdapter: RadioGroupAdapter? = null
    private var sortByOptionsAdapter: RadioGroupAdapter? = null

    private val permissionRequestLauncher = registerPermissionLauncher { isGranted ->
        if (isGranted) {
            viewModel.monitorUserLocation()
        } else {
            openAppSettings()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (viewModel.exploreTopic == ExploreTopic.Merchants) {
            setupPaymentMethods()
        } else {
            binding.paymentMethods.isVisible = false
            binding.paymentMethodsLabel.isVisible = false
        }

        viewModel.isLocationEnabled.observe(viewLifecycleOwner) {
            if (viewModel.filterMode.value != FilterMode.Online) {
                setupRadiusOptions()
                setupTerritoryFilter()
                setupLocationPermission()
            } else {
                binding.locationLabel.isVisible = false
                binding.locationBtn.isVisible = false
                binding.radiusLabel.isVisible = false
                binding.radiusCard.isVisible = false
                binding.locationSettingsLabel.isVisible = false
                binding.locationSettingsBtn.isVisible = false
            }
        }

        setupSortByOptions()

        binding.applyButton.setOnClickListener {
            applyFilters()
            dismiss()
        }

        checkResetButton()

        binding.resetFiltersBtn.setOnClickListener { resetFilters() }
        binding.collapseButton.setOnClickListener {
            viewModel.isDialogDismissedOnCancel = true
            dismiss()
        }
    }

    private fun setupPaymentMethods() {
        val isDashOn = viewModel.appliedFilters.value.payment.isEmpty() ||
            viewModel.appliedFilters.value.payment == PaymentMethod.DASH
        dashPaymentOn = isDashOn
        binding.dashOption.isChecked = isDashOn
        binding.dashOption.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !binding.giftCardOption.isChecked) {
                // shouldn't allow to uncheck both options
                binding.giftCardOption.isChecked = true
            }

            dashPaymentOn = isChecked
            checkResetButton()
        }

        val isGiftCardOn = viewModel.appliedFilters.value.payment.isEmpty() ||
            viewModel.appliedFilters.value.payment == PaymentMethod.GIFT_CARD
        giftCardPaymentOn = isGiftCardOn
        binding.giftCardOption.isChecked = isGiftCardOn
        binding.giftCardTypesLabel.isVisible = isGiftCardOn
        binding.giftCardTypes.isVisible = isGiftCardOn
        binding.giftCardOption.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !binding.dashOption.isChecked) {
                binding.dashOption.isChecked = true
            }

            giftCardPaymentOn = isChecked
            binding.giftCardTypesLabel.isVisible = isChecked
            binding.giftCardTypes.isVisible = isChecked

            val shouldHideSorting = viewModel.filterMode.value == FilterMode.Online && !isChecked
            binding.sortByCard.isVisible = !shouldHideSorting
            binding.sortByLabel.isVisible = !shouldHideSorting

            checkResetButton()
        }

        binding.fixedDenomOption.isChecked = viewModel.appliedFilters.value.denominationType != DenomOption.Flexible
        binding.flexibleAmountOption.isChecked = viewModel.appliedFilters.value.denominationType != DenomOption.Fixed

        binding.fixedDenomOption.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !binding.flexibleAmountOption.isChecked) {
                binding.flexibleAmountOption.isChecked = true
            }

            checkResetButton()
        }

        binding.flexibleAmountOption.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !binding.fixedDenomOption.isChecked) {
                binding.fixedDenomOption.isChecked = true
            }

            checkResetButton()
        }
    }

    private fun setupSortByOptions() {
        sortOption = viewModel.appliedFilters.value.sortOption

        val sortOptions = mutableListOf(
            SortOption.Name
        )

        if (viewModel.filterMode.value != FilterMode.Online && viewModel.isLocationEnabled.value == true) {
            sortOptions.add(SortOption.Distance)
        }

        if (viewModel.exploreTopic != ExploreTopic.ATMs) {
            sortOptions.add(SortOption.Discount)
        }

        if (sortOptions.size <= 1) {
            binding.sortByLabel.isVisible = false
            binding.sortByCard.isVisible = false
            return
        }

        val optionNames = sortOptions.map {
            IconifiedViewItem(
                resources.getStringArray(R.array.sort_by_options_names)[it.ordinal]
            )
        }
        this.sortOptions = sortOptions

        val shouldHideSorting = viewModel.filterMode.value == FilterMode.Online && !binding.giftCardOption.isChecked
        binding.sortByCard.isVisible = !shouldHideSorting
        binding.sortByLabel.isVisible = !shouldHideSorting

        val initialIndex = sortOptions.indexOf(sortOption)
        val adapter = RadioGroupAdapter(initialIndex) { _, optionIndex ->
            sortOption = sortOptions[optionIndex]
            checkResetButton()
        }
        binding.sortByFilter.setupRadioGroup(adapter, useDecorator = false)
        adapter.submitList(optionNames)
        sortByOptionsAdapter = adapter
    }

    private fun setupRadiusOptions() {
        binding.radiusLabel.isVisible = true
        binding.radiusCard.isVisible = true

        selectedRadiusOption = viewModel.appliedFilters.value.radius

        if (viewModel.isLocationEnabled.value == true) {
            binding.radiusFilter.isVisible = true
            binding.manageGpsView.root.isVisible = false
            binding.locationExplainerTxt.isVisible = false

            val optionNames = binding.root.resources.getStringArray(
                if (viewModel.isMetric) {
                    R.array.radius_filter_options_kilometers
                } else {
                    R.array.radius_filter_options_miles
                }
            ).map { IconifiedViewItem(it, "") }

            val radiusOption = selectedRadiusOption
            val adapter = RadioGroupAdapter(radiusOptions.indexOf(radiusOption)) { _, optionIndex ->
                selectedRadiusOption = radiusOptions[optionIndex]
                checkResetButton()
            }
            binding.radiusFilter.setupRadioGroup(adapter, useDecorator = false)
            adapter.submitList(optionNames)
            radiusOptionsAdapter = adapter
        } else {
            binding.radiusFilter.isVisible = false
            binding.manageGpsView.root.isVisible = true
            binding.locationExplainerTxt.isVisible = true
        }
    }

    private fun setTerritoryName(territory: String) {
        binding.locationLabel.isVisible = true
        binding.locationBtn.isVisible = true

        binding.locationName.text = territory.ifEmpty {
            if (viewModel.isLocationEnabled.value == true) {
                getString(R.string.explore_current_location)
            } else {
                getString(R.string.explore_all_states)
            }
        }

        selectedTerritory = territory
        checkResetButton()
    }

    private fun setupTerritoryFilter() {
        setTerritoryName(viewModel.appliedFilters.value.territory)
        lifecycleScope.launch { territoriesJob = async { viewModel.getTerritoriesWithPOIs().sorted() } }

        binding.locationBtn.setOnClickListener {
            val firstOption = if (viewModel.isLocationEnabled.value == true) {
                IconifiedViewItem(getString(R.string.explore_current_location), "", R.drawable.ic_current_location)
            } else {
                IconifiedViewItem(getString(R.string.explore_all_states))
            }

            lifecycleScope.launch {
                val territories = territoriesJob?.await() ?: listOf()
                val allTerritories = listOf(firstOption) + territories.map { IconifiedViewItem(it) }

                val currentIndex = if (selectedTerritory.isEmpty()) {
                    0
                } else {
                    territories.indexOf(selectedTerritory) + 1
                }

                val dialogTitle = getString(R.string.explore_location)
                OptionPickerDialog(
                    dialogTitle,
                    allTerritories,
                    currentIndex,
                    useCheckMark = true
                ) { item, index, dialog ->
                    dialog.dismiss()
                    setTerritoryName(if (index == 0) "" else item.title)
                }.show(requireActivity())
            }
        }
    }

    private fun setupLocationPermission() {
        binding.locationSettingsLabel.isVisible = true
        binding.locationSettingsBtn.isVisible = true

        binding.locationStatus.text = getString(
            if (isLocationPermissionGranted) {
                R.string.explore_location_allowed
            } else {
                R.string.explore_location_denied
            }
        )

        binding.locationSettingsBtn.setOnClickListener {
            lifecycleScope.launch {
                runLocationFlow(viewModel.exploreTopic, viewModel.exploreConfig, permissionRequestLauncher)
            }
        }

        binding.manageGpsView.managePermissionsBtn.setOnClickListener {
            lifecycleScope.launch {
                runLocationFlow(viewModel.exploreTopic, viewModel.exploreConfig, permissionRequestLauncher)
            }
        }
    }

    private fun applyFilters() {
        var paymentFilter = ""

        if (!dashPaymentOn || !giftCardPaymentOn) {
            paymentFilter = if (dashPaymentOn) {
                PaymentMethod.DASH
            } else {
                PaymentMethod.GIFT_CARD
            }
        }

        val denomOption = if (binding.fixedDenomOption.isChecked && binding.flexibleAmountOption.isChecked) {
            DenomOption.Both
        } else if (binding.fixedDenomOption.isChecked) {
            DenomOption.Fixed
        } else {
            DenomOption.Flexible
        }

        viewModel.setFilters(paymentFilter, selectedTerritory, selectedRadiusOption, sortOption, denomOption)
        viewModel.trackFilterEvents(dashPaymentOn, giftCardPaymentOn)
    }

    private fun checkResetButton() {
        var isEnabled = false

        if (!dashPaymentOn || !giftCardPaymentOn) {
            isEnabled = true
        }

        if (!binding.flexibleAmountOption.isChecked || !binding.fixedDenomOption.isChecked) {
            isEnabled = true
        }

        if (selectedTerritory.isNotEmpty()) {
            isEnabled = true
        }

        if (sortOption != ExploreViewModel.DEFAULT_SORT_OPTION) {
            isEnabled = true
        }

        if (selectedRadiusOption != ExploreViewModel.DEFAULT_RADIUS_OPTION) {
            isEnabled = true
        }

        binding.resetFiltersBtn.isEnabled = isEnabled
    }

    private fun resetFilters() {
        dashPaymentOn = true
        binding.dashOption.isChecked = true
        giftCardPaymentOn = true
        binding.giftCardOption.isChecked = true

        binding.flexibleAmountOption.isChecked = true
        binding.fixedDenomOption.isChecked = true

        selectedTerritory = ""
        binding.locationName.text = if (viewModel.isLocationEnabled.value == true) {
            getString(R.string.explore_current_location)
        } else {
            getString(R.string.explore_all_states)
        }

        selectedRadiusOption = ExploreViewModel.DEFAULT_RADIUS_OPTION
        radiusOptionsAdapter?.selectedIndex = radiusOptions.indexOf(ExploreViewModel.DEFAULT_RADIUS_OPTION)

        sortOption = ExploreViewModel.DEFAULT_SORT_OPTION
        sortByOptionsAdapter?.selectedIndex = sortOptions.indexOf(sortOption)

        binding.resetFiltersBtn.isEnabled = false
    }
}
