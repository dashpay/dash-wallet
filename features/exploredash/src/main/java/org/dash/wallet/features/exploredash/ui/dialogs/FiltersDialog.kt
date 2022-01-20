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

package org.dash.wallet.features.exploredash.ui.dialogs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.ui.OffsetDialogFragment
import org.dash.wallet.common.ui.radio_group.IconifiedViewItem
import org.dash.wallet.common.ui.radio_group.OptionPickerDialog
import org.dash.wallet.common.ui.radio_group.RadioGroupAdapter
import org.dash.wallet.common.ui.radio_group.setupRadioGroup
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.PaymentMethod
import org.dash.wallet.features.exploredash.databinding.DialogFiltersBinding
import org.dash.wallet.features.exploredash.ui.ExploreTopic
import org.dash.wallet.features.exploredash.ui.ExploreViewModel
import org.dash.wallet.features.exploredash.ui.FilterMode
import org.dash.wallet.features.exploredash.ui.extensions.isLocationPermissionGranted
import org.dash.wallet.features.exploredash.ui.extensions.registerPermissionLauncher
import org.dash.wallet.features.exploredash.ui.extensions.requestLocationPermission
import javax.inject.Inject

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class FiltersDialog: OffsetDialogFragment<ConstraintLayout>() {
    override val background: Int
        get() = R.drawable.gray_background_rounded

    private val radiusOptions = listOf(1, 5, 20, 50)
    private var selectedRadiusOption: Int = ExploreViewModel.DEFAULT_RADIUS_OPTION
    private var sortByDistance = ExploreViewModel.DEFAULT_SORT_BY_DISTANCE
    private var selectedTerritory: String = ""
    private var dashPaymentOn: Boolean = true
    private var giftCardPaymentOn: Boolean = true

    private val binding by viewBinding(DialogFiltersBinding::bind)
    private val viewModel: ExploreViewModel by activityViewModels()

    private var territoriesJob: Deferred<List<String>>? = null
    private var radiusOptionsAdapter: RadioGroupAdapter? = null
    private var sortByOptionsAdapter: RadioGroupAdapter? = null

    @Inject
    lateinit var configuration: Configuration

    private val permissionRequestLauncher = registerPermissionLauncher { isGranted ->
        if (isGranted) {
            viewModel.monitorUserLocation()
        } else {
            openAppSettings()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_filters, container, false)
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

        binding.resetFiltersBtn.setOnClickListener {
            resetFilters()
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
            checkResetButton()
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
            checkResetButton()
        }
    }

    private fun setupSortByOptions() {
        sortByDistance = viewModel.sortByDistance

        if (viewModel.isLocationEnabled.value == true) {
            binding.sortByLabel.isVisible = true
            binding.sortByCard.isVisible = true

            val optionNames = binding.root.resources.getStringArray(
                R.array.sort_by_options_names
            ).map { IconifiedViewItem(it) }

            val initialIndex = if (sortByDistance) 1 else 0
            val adapter = RadioGroupAdapter(initialIndex) { _, optionIndex ->
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
            binding.managePermissionsBtn.isVisible = false
            binding.locationRequestTxt.isVisible = false
            binding.locationExplainerTxt.isVisible = false

            val optionNames = binding.root.resources.getStringArray(
                if (viewModel.isMetric) R.array.radius_filter_options_kilometers else R.array.radius_filter_options_miles
            ).map { IconifiedViewItem(it) }

            val radiusOption = selectedRadiusOption
            val adapter = RadioGroupAdapter(radiusOptions.indexOf(radiusOption)) { _, optionIndex ->
                selectedRadiusOption = radiusOptions[optionIndex]
                checkResetButton()
            }
            binding.radiusFilter.setupRadioGroup(adapter)
            adapter.submitList(optionNames)
            radiusOptionsAdapter = adapter
        } else {
            binding.radiusFilter.isVisible = false
            binding.managePermissionsBtn.isVisible = true
            binding.locationRequestTxt.isVisible = true
            binding.locationExplainerTxt.isVisible = true
        }
    }

     private fun setTerritoryName(territory: String) {
         binding.locationLabel.isVisible = true
         binding.locationBtn.isVisible = true

         binding.locationName.text = if (territory.isEmpty()) {
            if (viewModel.isLocationEnabled.value == true) {
                getString(R.string.explore_current_location)
            } else {
                getString(R.string.explore_all_states)
            }
         } else {
             territory
         }

         selectedTerritory = territory
         checkResetButton()
    }

    private fun setupTerritoryFilter() {
        viewModel.selectedTerritory.value?.let { setTerritoryName(it) }

        lifecycleScope.launch {
            territoriesJob = async {
                viewModel.getTerritoriesWithPOIs().sorted()
            }
        }

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
                OptionPickerDialog(dialogTitle, allTerritories, currentIndex) { item, index, dialog ->
                    dialog.dismiss()
                    setTerritoryName(if (index == 0) "" else item.title)
                }.show(parentFragmentManager, "territory_filter")
            }
        }
    }

    private fun setupLocationPermission() {
        binding.locationSettingsLabel.isVisible = true
        binding.locationSettingsBtn.isVisible = true

        binding.locationStatus.text = getString(if (isLocationPermissionGranted) {
            R.string.explore_location_allowed
        } else {
            R.string.explore_location_denied
        })

        binding.locationSettingsBtn.setOnClickListener {
            runLocationFlow()
        }

        binding.managePermissionsBtn.setOnClickListener {
            runLocationFlow()
        }
    }

    private fun applyFilters() {
        viewModel.setSelectedTerritory(selectedTerritory)
        viewModel.setSelectedRadiusOption(selectedRadiusOption)
        viewModel.sortByDistance = sortByDistance

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
        binding.locationName.text = if (viewModel.isLocationEnabled.value == true) {
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

    private fun runLocationFlow() {
        if (isLocationPermissionGranted) {
            openAppSettings()
        } else {
            requestLocationPermission(
                viewModel.exploreTopic,
                configuration,
                permissionRequestLauncher
            )
        }
    }

    private fun openAppSettings() {
        val intent = Intent().apply {
            action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            data = Uri.fromParts("package", requireContext().packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        startActivity(intent)
    }
}