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

package org.dash.wallet.features.exploredash.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.ConcatAdapter
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.ListDividerDecorator
import org.dash.wallet.common.ui.observeOnDestroy
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.safeNavigate
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.*
import org.dash.wallet.features.exploredash.databinding.FragmentSearchBinding
import org.dash.wallet.features.exploredash.ui.adapters.MerchantsAtmsResultAdapter
import org.dash.wallet.features.exploredash.ui.adapters.SearchHeaderAdapter
import org.dash.wallet.features.exploredash.ui.extensions.*
import org.dash.wallet.common.Configuration
import org.dash.wallet.features.exploredash.ui.adapters.MerchantLocationsHeaderAdapter
import org.dash.wallet.features.exploredash.ui.adapters.MerchantsLocationsAdapter
import javax.inject.Inject

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class SearchFragment : Fragment(R.layout.fragment_search) {
    companion object {
        private const val SCROLL_OFFSET_FOR_UP = 700
    }

    enum class State {
        SearchResults,
        DetailsGrouped,
        AllLocations,
        Details
    }

    @Inject
    lateinit var configuration: Configuration

    private val binding by viewBinding(FragmentSearchBinding::bind)
    private val viewModel: ExploreViewModel by activityViewModels()
    private val args by navArgs<SearchFragmentArgs>()

    private var bottomSheetWasExpanded: Boolean = false
    private var isKeyboardShowing: Boolean = false
    private var hasLocationBeenRequested: Boolean = false
    private var screenState: State = State.SearchResults

    private val isPhysicalSearch: Boolean
        get() = viewModel.exploreTopic == ExploreTopic.ATMs ||
                viewModel.filterMode.value == FilterMode.Physical

    private val permissionRequestLauncher = registerPermissionLauncher { isGranted ->
        if (isGranted) {
            viewModel.monitorUserLocation()
        }
    }

    private val searchResultsAdapter = MerchantsAtmsResultAdapter { item, _ ->
        hideKeyboard()

        if (item is Merchant) {
            viewModel.openMerchantDetails(item)
        } else if (item is Atm) {
            viewModel.openAtmDetails(item)
        }
    }

    private val merchantLocationsAdapter = MerchantsLocationsAdapter { _, _ ->
        binding.screenshot.isVisible = true
    }

    private val searchResultsDecorator: ListDividerDecorator by lazy {
        val divider = ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!
        ListDividerDecorator(
            divider,
            showAfterLast = false,
            marginStart = resources.getDimensionPixelOffset(R.dimen.divider_margin_start)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!configuration.hasExploreDashInfoScreenBeenShown()) {
            safeNavigate(SearchFragmentDirections.exploreToInfo())
            configuration.setHasExploreDashInfoScreenBeenShown(true)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_info) {
                safeNavigate(SearchFragmentDirections.exploreToInfo())
            }
            true
        }

        val binding = binding // Avoids IllegalStateException in onStateChanged callback
        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.halfExpandedRatio = ResourcesCompat.getFloat(
            resources,
            if (args.type == ExploreTopic.Merchants) R.dimen.merchant_half_expanded_ratio else R.dimen.atm_half_expanded_ratio
        )

        val header = SearchHeaderAdapter(args.type)
        setupBackNavigation(header)
        setupFilters(header, bottomSheet, args.type)
        setupSearchInput(header, bottomSheet)
        setupSearchResults(header)
        setupItemDetails(header)

        viewModel.init(args.type)
        binding.toolbarTitle.text = getToolbarTitle()

        viewModel.isLocationEnabled.observe(viewLifecycleOwner) { isEnabled ->
            if (isEnabled && viewModel.filterMode.value != FilterMode.Online) {
                bottomSheet.isDraggable = true
                bottomSheet.state = BottomSheetBehavior.STATE_HALF_EXPANDED
            } else {
                bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
                bottomSheet.isDraggable = false
            }
        }

        viewModel.allMerchantLocations.observe(viewLifecycleOwner) { merchantLocations ->
            merchantLocationsAdapter.submitList(merchantLocations)
        }
    }

    override fun onResume() {
        super.onResume()

        if (isLocationPermissionGranted) {
            viewModel.monitorUserLocation()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.onExitSearch()
    }

    private fun setupFilters(
        header: SearchHeaderAdapter,
        bottomSheet: BottomSheetBehavior<ConstraintLayout>,
        topic: ExploreTopic
    ) {
        val defaultMode = if (topic == ExploreTopic.Merchants) FilterMode.Online else FilterMode.All
        viewModel.setFilterMode(defaultMode)

        header.setOnFilterOptionChosen { mode ->
            viewModel.setFilterMode(mode)
        }

        header.setOnFilterButtonClicked {
            openFilters()
        }

        binding.filterPanel.setOnClickListener {
            openFilters()
        }

        viewModel.filterMode.observe(viewLifecycleOwner) { mode ->
            binding.noResultsPanel.isVisible = false
            header.title = getSearchTitle()
            header.subtitle = getSearchSubtitle()
            binding.filterPanel.isVisible = shouldShowFiltersPanel()

            if (mode == FilterMode.Online) {
                bottomSheet.isDraggable = false

                if (viewModel.selectedItem.value == null) {
                    bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
                    bottomSheetWasExpanded = true
                }
            } else {
                if (isLocationPermissionGranted) {
                    bottomSheet.state = BottomSheetBehavior.STATE_HALF_EXPANDED
                    bottomSheetWasExpanded = false
                    bottomSheet.isDraggable = true
                } else if (!hasLocationBeenRequested) {
                    requestLocationPermission(
                        viewModel.exploreTopic,
                        configuration,
                        permissionRequestLauncher
                    )
                    // Shouldn't show location request on filter option switch more than once per session
                    hasLocationBeenRequested = true
                }
            }
        }
    }

    private fun setupSearchInput(
        header: SearchHeaderAdapter,
        bottomSheet: BottomSheetBehavior<ConstraintLayout>
    ) {
        header.setOnSearchQueryChanged {
            binding.noResultsPanel.isVisible = false
            viewModel.submitSearchQuery(it)
        }

        header.setOnSearchQuerySubmitted {
            hideKeyboard()
        }

        requireActivity().window?.decorView?.let { decor ->
            ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
                val showingKeyboard = insets.isVisible(WindowInsetsCompat.Type.ime())
                this.isKeyboardShowing = showingKeyboard

                if (showingKeyboard) {
                    bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
                }

                insets
            }
        }
    }

    private fun setupSearchResults(header: SearchHeaderAdapter) {
        binding.searchResults.addItemDecoration(searchResultsDecorator)
        binding.searchResults.adapter = ConcatAdapter(header, searchResultsAdapter)

        binding.searchResults.setOnScrollChangeListener { _, _, _, _, _ ->
            binding.upButton.isVisible = shouldShowUpButton()
        }

        binding.upButton.setOnClickListener {
            binding.searchResults.scrollToPosition(0)
        }

        binding.resetFiltersBtn.setOnClickListener {
            viewModel.clearFilters()
            header.clearSearchQuery()
            binding.resetFiltersBtn.isEnabled = false
        }

        viewModel.pagingSearchResults.observe(viewLifecycleOwner) { results ->
            searchResultsAdapter.submitData(viewLifecycleOwner.lifecycle, results)
        }

        viewModel.pagingSearchResultsCount.observe(viewLifecycleOwner) {
            header.subtitle = getSearchSubtitle()
            binding.noResultsPanel.isVisible = it <= 0
        }

        viewModel.searchLocationName.observe(viewLifecycleOwner) {
            header.title = getSearchTitle()
        }

        viewModel.appliedFilters.observe(viewLifecycleOwner) { filters ->
            resolveAppliedFilters(filters)
            header.subtitle = getSearchSubtitle()
            binding.resetFiltersBtn.isEnabled = filters.query.isNotEmpty() ||
                    filters.radius != ExploreViewModel.DEFAULT_RADIUS_OPTION ||
                    filters.payment.isNotEmpty() || filters.territory.isNotEmpty()
        }

        viewLifecycleOwner.observeOnDestroy {
            binding.searchResults.adapter = null
        }
    }

    private fun setupItemDetails(header: SearchHeaderAdapter) {
        binding.itemDetails.setOnSendDashClicked { viewModel.sendDash() }
        binding.itemDetails.setOnReceiveDashClicked { viewModel.receiveDash() }
        binding.itemDetails.setOnShowAllLocationsClicked {
            viewModel.selectedItem.value?.let { merchant ->
                if (merchant is Merchant && merchant.merchantId != null && !merchant.source.isNullOrEmpty()) {
                    viewModel.retrieveAllMerchantLocations(merchant.merchantId!!, merchant.source!!)
                    transitToAllMerchantLocations()
                }
            }
        }

        viewModel.selectedItem.observe(viewLifecycleOwner) { item ->
            if (item != null) {
                binding.toolbarTitle.text = item.name
                val isOnline = item.type == MerchantType.ONLINE ||
                        viewModel.filterMode.value == FilterMode.Online
                val isGrouped = true
                binding.itemDetails.bindItem(item, isOnline, isGrouped)

                lifecycleScope.launch {
                    if (isKeyboardShowing) {
                        delay(100)
                    }

                    transitToDetails(isOnline, isGrouped)
                }
            } else {
                binding.toolbarTitle.text = getToolbarTitle()
                transitToSearchResults(header)
            }
        }
    }

    private fun setupBackNavigation(header: SearchHeaderAdapter) {
        val onBackButtonAction = {
            if (viewModel.selectedItem.value != null) {
                viewModel.openSearchResults()
                transitToSearchResults(header)
            } else {
                findNavController().popBackStack()
            }
        }

        binding.toolbar.setNavigationOnClickListener {
            onBackButtonAction.invoke()
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object: OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    onBackButtonAction.invoke()
                }
            })
    }


    private fun transitToDetails(fullHeight: Boolean, isGrouped: Boolean) {
        if (isGrouped && screenState == State.DetailsGrouped) {
            return
        }

        if (!isGrouped && screenState == State.Details) {
            return
        }

        screenState = if (isGrouped) State.DetailsGrouped else State.Details
        binding.upButton.isVisible = false
        binding.filterPanel.isVisible = false

        binding.backButton.text = getString(R.string.explore_back_to_locations)
        binding.backButton.isVisible = !fullHeight && !isGrouped

        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.isDraggable = false
        bottomSheetWasExpanded = bottomSheet.state == BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.state =
            if (fullHeight) BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_HALF_EXPANDED

        binding.itemDetails.isVisible = true

        val animResults = ObjectAnimator.ofFloat(binding.searchResults, View.ALPHA, 0f)
        val animDrag = ObjectAnimator.ofFloat(binding.dragIndicator, View.ALPHA, 0f)
        val animDetails = ObjectAnimator.ofFloat(binding.itemDetails, View.ALPHA, 1f)
        AnimatorSet().apply {
            playTogether(animResults, animDrag, animDetails)
            duration = 200
            doOnEnd {
                binding.searchResults.isVisible = false
                binding.dragIndicator.isVisible = false
            }
        }.start()
    }

    private fun transitToSearchResults(header: SearchHeaderAdapter) {
        if (screenState == State.SearchResults) {
            return
        }

        screenState = State.SearchResults
        binding.upButton.isVisible = shouldShowUpButton()
        binding.filterPanel.isVisible = shouldShowFiltersPanel()
        binding.backButton.isVisible = false

        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.isDraggable = viewModel.isLocationEnabled.value == true &&
                viewModel.filterMode.value != FilterMode.Online
        bottomSheet.expandedOffset = resources.getDimensionPixelOffset(R.dimen.default_expanded_offset)

        bottomSheet.state = if (bottomSheetWasExpanded) {
            BottomSheetBehavior.STATE_EXPANDED
        } else {
            BottomSheetBehavior.STATE_HALF_EXPANDED
        }

        if (binding.searchResults.itemDecorationCount < 1) {
            binding.searchResults.addItemDecoration(searchResultsDecorator)
        }

        binding.searchResults.adapter = ConcatAdapter(header, searchResultsAdapter)
        binding.searchResults.isVisible = true
        binding.dragIndicator.isVisible = true

        val animResults = ObjectAnimator.ofFloat(binding.searchResults, View.ALPHA, 1f)
        val animDrag = ObjectAnimator.ofFloat(binding.dragIndicator, View.ALPHA, 1f)
        val animDetails = ObjectAnimator.ofFloat(binding.itemDetails, View.ALPHA, 0f)
        AnimatorSet().apply {
            playTogether(animResults, animDrag, animDetails)
            duration = 200
            doOnEnd {
                binding.itemDetails.isVisible = false
            }
        }.start()
    }

    private fun transitToAllMerchantLocations() {
        if (screenState == State.AllLocations) {
            return
        }

        screenState = State.AllLocations
        binding.upButton.isVisible = shouldShowUpButton()
        binding.filterPanel.isVisible = false

        binding.backButton.isVisible = true
        binding.backButton.text = getString(R.string.explore_back_to_nearest)

        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.isDraggable = true
        bottomSheet.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        bottomSheet.expandedOffset = resources.getDimensionPixelOffset(R.dimen.all_locations_expanded_offset)

        viewModel.selectedItem.value?.let { item ->
            val header = MerchantLocationsHeaderAdapter(
                item.name ?: "",
                binding.itemDetails.getMerchantType(item.type),
                item.logoLocation ?: ""
            )

            if (binding.searchResults.itemDecorationCount > 0) {
                binding.searchResults.removeItemDecorationAt(0)
            }

            binding.searchResults.adapter = ConcatAdapter(header, merchantLocationsAdapter)
            binding.searchResults.isVisible = true
        }
        binding.dragIndicator.isVisible = false

        val animResults = ObjectAnimator.ofFloat(binding.searchResults, View.ALPHA, 1f)
        val animDetails = ObjectAnimator.ofFloat(binding.itemDetails, View.ALPHA, 0f)
        AnimatorSet().apply {
            playTogether(animResults, animDetails)
            duration = 200
            doOnEnd {
                binding.itemDetails.isVisible = false
            }
        }.start()
    }

    private fun getToolbarTitle(): String {
        return when (viewModel.exploreTopic) {
            ExploreTopic.Merchants -> getString(R.string.explore_where_to_spend)
            else -> getString(R.string.explore_atms)
        }
    }

    private fun getSearchTitle(): String {
        if (viewModel.exploreTopic == ExploreTopic.Merchants) {
            if (viewModel.filterMode.value == FilterMode.Online) {
                return getString(R.string.explore_online_merchant)
            }

            if (viewModel.filterMode.value == FilterMode.All) {
                return getString(R.string.explore_all_merchants)
            }
        }

        val locationName = viewModel.searchLocationName.value

        return if (locationName.isNullOrEmpty()) {
            getString(R.string.explore_search_results)
        } else {
            locationName
        }
    }

    private fun getSearchSubtitle(): String {
        if (viewModel.isLocationEnabled.value != true || !isPhysicalSearch) {
            return ""
        }

        val searchLocation = if (viewModel.selectedTerritory.isNotEmpty()) {
            viewModel.selectedTerritory
        } else {
            val radiusOption = viewModel.selectedRadiusOption
            resources.getQuantityString(
                if (viewModel.isMetric) R.plurals.radius_kilometers else R.plurals.radius_miles,
                radiusOption, radiusOption
            )
        }

        val resultSize = viewModel.pagingSearchResultsCount.value ?: 0
        val quantityStr = if (viewModel.exploreTopic == ExploreTopic.Merchants) {
            if (resultSize == 0) {
                getString(R.string.explore_no_merchants)
            } else {
                resources.getQuantityString(
                    R.plurals.explore_merchant_amount,
                    resultSize,
                    resultSize
                )
            }
        } else {
            if (resultSize == 0) {
                getString(R.string.explore_no_atms)
            } else {
                resources.getQuantityString(R.plurals.explore_atm_amount, resultSize, resultSize)
            }
        }

        return getString(R.string.explore_in_radius, quantityStr, searchLocation)
    }

    private fun resolveAppliedFilters(filters: FilterOptions) {
        val appliedFilterNames = mutableListOf<String>()

        if (filters.payment.isNotEmpty()) {
            appliedFilterNames.add(
                getString(
                    if (filters.payment == PaymentMethod.DASH) {
                        R.string.explore_pay_with_dash
                    } else {
                        R.string.explore_pay_gift_card
                    }
                )
            )
        }

        if (filters.territory.isNotEmpty()) {
            appliedFilterNames.add(filters.territory)
        }

        if (isPhysicalSearch) {
            appliedFilterNames.add(
                resources.getQuantityString(
                    if (viewModel.isMetric) R.plurals.radius_kilometers else R.plurals.radius_miles,
                    filters.radius, filters.radius
                )
            )
        }

        binding.filterPanel.isVisible = shouldShowFiltersPanel()
        binding.filteredByTxt.text = appliedFilterNames.joinToString(", ")

        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        val bottomSheetPeekHeight = resources.getDimensionPixelOffset(R.dimen.search_content_peek_height)

        if (appliedFilterNames.any()) {
            bottomSheet.peekHeight = binding.filterPanel.measuredHeight + bottomSheetPeekHeight
        } else {
            bottomSheet.peekHeight = bottomSheetPeekHeight
        }
    }

    private fun shouldShowFiltersPanel(): Boolean {
        return viewModel.selectedItem.value == null &&
               viewModel.isLocationEnabled.value == true &&
               (isPhysicalSearch ||
               viewModel.paymentMethodFilter.isNotEmpty() ||
               viewModel.selectedTerritory.isNotEmpty())
    }

    private fun openFilters() {
        safeNavigate(SearchFragmentDirections.searchToFilters())
    }

    private fun hideKeyboard() {
        val inputManager = requireContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        inputManager?.hideSoftInputFromWindow(requireActivity().window.decorView.windowToken, 0)
    }

    private fun shouldShowUpButton(): Boolean {
        val offset = binding.searchResults.computeVerticalScrollOffset()
        return offset > SCROLL_OFFSET_FOR_UP
    }
}
