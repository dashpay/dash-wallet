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
import org.dash.wallet.features.exploredash.databinding.DialogFiltersBinding
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
    private var sortByDistance = ExploreViewModel.DEFAULT_SORT_BY_DISTANCE
    private var selectedTerritory: String = ""
    private var dashPaymentOn: Boolean = true
    private var giftCardPaymentOn: Boolean = true

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

        binding.paymentMethods.isVisible = false
        binding.paymentMethodsLabel.isVisible = false

        viewModel.isLocationEnabled.observe(viewLifecycleOwner) {
            if (viewModel.filterMode.value != FilterMode.Online) {
                setupSortByOptions()
                setupRadiusOptions()
                setupTerritoryFilter()
                setupLocationPermission()
            } else {
                binding.sortByLabel.isVisible = false
                binding.sortByCard.isVisible = false
                binding.locationLabel.isVisible = false
                binding.locationBtn.isVisible = false
                binding.radiusLabel.isVisible = false
                binding.radiusCard.isVisible = false
                binding.locationSettingsLabel.isVisible = false
                binding.locationSettingsBtn.isVisible = false
            }
        }

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
        val isDashOn = viewModel.paymentMethodFilter.isEmpty() || viewModel.paymentMethodFilter == PaymentMethod.DASH
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

        val isGiftCardOn =
            viewModel.paymentMethodFilter.isEmpty() || viewModel.paymentMethodFilter == PaymentMethod.GIFT_CARD
        giftCardPaymentOn = isGiftCardOn
        binding.giftCardOption.isChecked = isGiftCardOn
        binding.giftCardOption.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked && !binding.dashOption.isChecked) {
                binding.dashOption.isChecked = true
            }

            giftCardPaymentOn = isChecked
            checkResetButton()
        }
    }

    private fun setupSortByOptions() {
        sortByDistance = viewModel.sortByDistance

        if (viewModel.isLocationEnabled.value == true) {
            binding.sortByLabel.isVisible = true
            binding.sortByCard.isVisible = true

            val optionNames =
                binding.root.resources.getStringArray(R.array.sort_by_options_names).map { IconifiedViewItem(it) }

            val initialIndex = if (sortByDistance) 1 else 0
            val adapter =
                RadioGroupAdapter(initialIndex, isCheckMark = true) { _, optionIndex ->
                    sortByDistance = optionIndex == 1
                    checkResetButton()
                }
            binding.sortByFilter.setupRadioGroup(adapter)
            adapter.submitList(optionNames)
            sortByOptionsAdapter = adapter
        } else {
            binding.sortByLabel.isVisible = false
            binding.sortByCard.isVisible = false
        }
    }

    private fun setupRadiusOptions() {
        binding.radiusLabel.isVisible = true
        binding.radiusCard.isVisible = true

        selectedRadiusOption = viewModel.selectedRadiusOption.value ?: ExploreViewModel.DEFAULT_RADIUS_OPTION

        if (viewModel.isLocationEnabled.value == true) {
            binding.radiusFilter.isVisible = true
            binding.manageGpsView.root.isVisible = false
            binding.locationExplainerTxt.isVisible = false

            val optionNames =
                binding.root.resources
                    .getStringArray(
                        if (viewModel.isMetric) {
                            R.array.radius_filter_options_kilometers
                        } else {
                            R.array.radius_filter_options_miles
                        }
                    )
                    .map { IconifiedViewItem(it, "") }

            val radiusOption = selectedRadiusOption
            val adapter =
                RadioGroupAdapter(radiusOptions.indexOf(radiusOption), isCheckMark = true) { _, optionIndex ->
                    selectedRadiusOption = radiusOptions[optionIndex]
                    checkResetButton()
                }
            binding.radiusFilter.setupRadioGroup(adapter)
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

        binding.locationName.text =
            territory.ifEmpty {
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
        viewModel.selectedTerritory.value?.let { setTerritoryName(it) }

        lifecycleScope.launch { territoriesJob = async { viewModel.getTerritoriesWithPOIs().sorted() } }

        binding.locationBtn.setOnClickListener {
            val firstOption =
                if (viewModel.isLocationEnabled.value == true) {
                    IconifiedViewItem(getString(R.string.explore_current_location), "", R.drawable.ic_current_location)
                } else {
                    IconifiedViewItem(getString(R.string.explore_all_states))
                }

            lifecycleScope.launch {
                val territories = territoriesJob?.await() ?: listOf()
                val allTerritories = listOf(firstOption) + territories.map { IconifiedViewItem(it) }

                val currentIndex =
                    if (selectedTerritory.isEmpty()) {
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

        binding.locationStatus.text =
            getString(
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
        viewModel.setSelectedTerritory(selectedTerritory)
        viewModel.setSelectedRadiusOption(selectedRadiusOption)
        viewModel.sortByDistance = sortByDistance

        if (viewModel.exploreTopic == ExploreTopic.Merchants) {
            var paymentFilter = ""

            if (!dashPaymentOn || !giftCardPaymentOn) {
                paymentFilter =
                    if (dashPaymentOn) {
                        PaymentMethod.DASH
                    } else {
                        PaymentMethod.GIFT_CARD
                    }
            }

            viewModel.paymentMethodFilter = paymentFilter
        }
        viewModel.trackFilterEvents(dashPaymentOn, giftCardPaymentOn)
    }

    private fun checkResetButton() {
        var isEnabled = false

        if (!dashPaymentOn || !giftCardPaymentOn) {
            isEnabled = true
        }

        if (selectedTerritory.isNotEmpty()) {
            isEnabled = true
        }

        if (sortByDistance != ExploreViewModel.DEFAULT_SORT_BY_DISTANCE) {
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

        selectedTerritory = ""
        binding.locationName.text =
            if (viewModel.isLocationEnabled.value == true) {
                getString(R.string.explore_current_location)
            } else {
                getString(R.string.explore_all_states)
            }

        selectedRadiusOption = ExploreViewModel.DEFAULT_RADIUS_OPTION
        radiusOptionsAdapter?.selectedIndex = radiusOptions.indexOf(ExploreViewModel.DEFAULT_RADIUS_OPTION)

        sortByDistance = ExploreViewModel.DEFAULT_SORT_BY_DISTANCE
        sortByOptionsAdapter?.selectedIndex = if (sortByDistance) 1 else 0

        binding.resetFiltersBtn.isEnabled = false
    }
}
