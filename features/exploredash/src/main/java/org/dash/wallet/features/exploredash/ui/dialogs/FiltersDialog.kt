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

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.ui.ListDividerDecorator
import org.dash.wallet.common.ui.OffsetDialogFragment
import org.dash.wallet.common.ui.radio_group.OptionPickerDialog
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.PaymentMethod
import org.dash.wallet.features.exploredash.databinding.DialogFiltersBinding
import org.dash.wallet.features.exploredash.ui.ExploreTopic
import org.dash.wallet.features.exploredash.ui.ExploreViewModel
import org.dash.wallet.common.ui.radio_group.IconifiedViewItem
import org.dash.wallet.common.ui.radio_group.RadioGroupAdapter
import org.dash.wallet.features.exploredash.ui.FilterMode
import org.dash.wallet.features.exploredash.ui.extensions.*
import org.dash.wallet.features.exploredash.ui.ExploreViewModel.Companion.DEFAULT_RADIUS_OPTION
import javax.inject.Inject

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class FiltersDialog: OffsetDialogFragment<ConstraintLayout>() {
    override val background: Int
        get() = R.drawable.gray_background_rounded

    private val radiusOptions = listOf(1, 5, 20, 50)
    private var selectedRadiusOption: Int = DEFAULT_RADIUS_OPTION
    private var selectedTerritory: String = ""
    private var dashPaymentOn: Boolean = true
    private var giftCardPaymentOn: Boolean = true

    private val binding by viewBinding(DialogFiltersBinding::bind)
    private val viewModel: ExploreViewModel by activityViewModels()
    private var territoriesJob: Deferred<List<String>>? = null

    @Inject
    lateinit var configuration: Configuration

    private val permissionRequestLauncher = registerPermissionLauncher {
        viewModel.monitorUserLocation()
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

        if (viewModel.filterMode.value != FilterMode.Online) {
            setupTerritoryFilter()
            setupRadiusOptions()
            setupLocationPermission()
        } else {
            binding.locationLabel.isVisible = false
            binding.locationBtn.isVisible = false
            binding.radiusLabel.isVisible = false
            binding.radiusCard.isVisible = false
            binding.locationSettingsLabel.isVisible = false
            binding.locationSettingsBtn.isVisible = false
        }

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

    private fun setupRadiusOptions() {
        binding.radiusLabel.isVisible = true
        binding.radiusCard.isVisible = true

        selectedRadiusOption = viewModel.selectedRadiusOption

        if (viewModel.isLocationEnabled.value == true) {
            binding.radiusFilter.isVisible = true
            binding.managePermissionsBtn.isVisible = false
            binding.locationRequestTxt.isVisible = false
            binding.locationExplainerTxt.isVisible = false

            val optionNames = binding.root.resources.getStringArray(
                if (viewModel.isMetric) R.array.radius_filter_options_kilometers else R.array.radius_filter_options_miles
            ).map { IconifiedViewItem(it, null) }

            val radiusOption = viewModel.selectedRadiusOption
            val adapter = RadioGroupAdapter(radiusOptions.indexOf(radiusOption)) { _, optionIndex ->
                selectedRadiusOption = radiusOptions[optionIndex]
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
                val allTerritories = listOf(firstOption) + territories.map { IconifiedViewItem(it) }

                val index = if (selectedTerritory.isEmpty()) {
                    0
                } else {
                    territories.indexOf(selectedTerritory) + 1
                }

                val dialogTitle = getString(R.string.explore_location)
                OptionPickerDialog(dialogTitle, allTerritories, index) { item, _, dialog ->
                    dialog.dismiss()
                    setTerritoryName(item.name)
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

    private fun checkResetButton() {
        var isEnabled = false

        if (!dashPaymentOn || !giftCardPaymentOn) {
            isEnabled = true
        }

        binding.resetFiltersBtn.isEnabled = isEnabled
    }

    private fun resetFilters() {
        dashPaymentOn = true
        binding.dashOption.isChecked = true
        giftCardPaymentOn = true
        binding.giftCardOption.isChecked = true

        checkResetButton()
    }

    private fun runLocationFlow() {
        if (isLocationPermissionGranted) {
            openAppSettings()
        } else {
            if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                openAppSettings()
            } else {
                requestLocationPermission(
                    viewModel.exploreTopic,
                    configuration,
                    permissionRequestLauncher
                )
            }
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