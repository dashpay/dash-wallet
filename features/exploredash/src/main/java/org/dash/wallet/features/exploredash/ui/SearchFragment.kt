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
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.os.bundleOf
import androidx.core.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.navigation.navGraphViewModels
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.firebase.FirebaseNetworkException
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.data.Status
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.decorators.ListDividerDecorator
import org.dash.wallet.common.ui.observeOnDestroy
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.common.util.*
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.*
import org.dash.wallet.features.exploredash.databinding.FragmentSearchBinding
import org.dash.wallet.features.exploredash.ui.adapters.MerchantLocationsHeaderAdapter
import org.dash.wallet.features.exploredash.ui.adapters.MerchantsAtmsResultAdapter
import org.dash.wallet.features.exploredash.ui.adapters.MerchantsLocationsAdapter
import org.dash.wallet.features.exploredash.ui.adapters.SearchHeaderAdapter
import org.dash.wallet.features.exploredash.ui.dialogs.DashDirectLoginInfoDialog
import org.dash.wallet.features.exploredash.ui.extensions.*
import org.dash.wallet.features.exploredash.utils.DashDirectConstants

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class SearchFragment : Fragment(R.layout.fragment_search) {
    companion object {
        private const val SCROLL_OFFSET_FOR_UP = 700
    }

    @Inject lateinit var configuration: Configuration
    @Inject lateinit var analyticsService: AnalyticsService
    private val binding by viewBinding(FragmentSearchBinding::bind)
    private val viewModel: ExploreViewModel by navGraphViewModels(R.id.explore_dash) { defaultViewModelProviderFactory }
    private val args by navArgs<SearchFragmentArgs>()

    private var bottomSheetWasExpanded: Boolean = false
    private var isKeyboardShowing: Boolean = false
    private var hasLocationBeenRequested: Boolean = false
    private var previousScreenState: ScreenState = ScreenState.SearchResults

    private val isPhysicalSearch: Boolean
        get() = viewModel.exploreTopic == ExploreTopic.ATMs || viewModel.filterMode.value == FilterMode.Nearby

    private val isMerchantTopic
        get() = viewModel.exploreTopic == ExploreTopic.Merchants

    private val permissionRequestLauncher = registerPermissionLauncher { isGranted ->
        if (isGranted) {
            viewModel.monitorUserLocation()
        }
    }

    private val permissionRequestSettings = registerPermissionLauncher { isGranted ->
        if (isGranted) {
            viewModel.monitorUserLocation()
        } else {
            openAppSettings()
        }
    }

    private lateinit var searchHeaderAdapter: SearchHeaderAdapter

    private val searchResultsAdapter = MerchantsAtmsResultAdapter { item, _ ->
        hideKeyboard()

        if (item is Merchant) {
            analyticsService.logEvent(AnalyticsConstants.Explore.SELECT_MERCHANT_LOCATION, bundleOf())
            viewModel.openMerchantDetails(item, true)
        } else if (item is Atm) {
            analyticsService.logEvent(AnalyticsConstants.Explore.SELECT_ATM_LOCATION, bundleOf())
            viewModel.openAtmDetails(item)
        }
    }

    private val merchantLocationsAdapter = MerchantsLocationsAdapter { merchant, _ ->
        viewModel.openMerchantDetails(merchant)
    }

    private val searchResultsDecorator: ListDividerDecorator by lazy {
        val divider = ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!
        ListDividerDecorator(
            divider,
            showAfterLast = false,
            marginStart = resources.getDimensionPixelOffset(R.dimen.divider_margin_horizontal)
        )
    }

    private var savedSearchScrollPosition: Int = -1
    private var savedLocationsScrollPosition: Int = -1

    private var lastSyncProgress: Resource<Double> = Resource.success(100.0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!configuration.hasExploreDashInfoScreenBeenShown() && args.type == ExploreTopic.Merchants) {
            safeNavigate(SearchFragmentDirections.exploreToInfo())
            configuration.setHasExploreDashInfoScreenBeenShown(true)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.menu.findItem(R.id.menu_info).apply { this.isVisible = args.type == ExploreTopic.Merchants }

        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_info) {
                if (args.type == ExploreTopic.Merchants) {
                    analyticsService.logEvent(AnalyticsConstants.Explore.INFO_EXPLORE_MERCHANT, bundleOf())
                }
                safeNavigate(SearchFragmentDirections.exploreToInfo())
            }
            true
        }

        val binding = binding // Avoids IllegalStateException in onStateChanged callback
        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.halfExpandedRatio =
            ResourcesCompat.getFloat(
                resources,
                if (args.type == ExploreTopic.Merchants) R.dimen.merchant_half_expanded_ratio
                else R.dimen.atm_half_expanded_ratio
            )

        searchHeaderAdapter = SearchHeaderAdapter(args.type)
        setupBackNavigation()
        setupFilters(bottomSheet, args.type)
        setupSearchInput(bottomSheet)
        setupSearchResults()
        setupItemDetails()
        setupScreenTransitions()

        viewModel.init(args.type)

        binding.toolbarTitle.text = getToolbarTitle()
        binding.recenterMapBtn.setOnClickListener { viewModel.recenterMapCallback.call() }

        binding.manageGpsView.managePermissionsBtn.setOnClickListener {
            runLocationFlow(viewModel.exploreTopic, configuration, permissionRequestSettings)
        }

        viewModel.isLocationEnabled.observe(viewLifecycleOwner) {
            val item = viewModel.selectedItem.value
            val isOnline = item?.type == MerchantType.ONLINE
            bottomSheet.isDraggable = isBottomSheetDraggable()
            bottomSheet.state = setBottomSheetState(isOnline)
            refreshManageGpsView()
        }

        viewModel.allMerchantLocations.observe(viewLifecycleOwner) { merchantLocations ->
            merchantLocationsAdapter.submitList(merchantLocations)
        }

        lifecycleScope.launch { viewModel.insertTestMerchent() }

        viewModel.syncStatus.observe(viewLifecycleOwner) { syncProgress ->
            lastSyncProgress = syncProgress
            when (syncProgress.status) {
                Status.LOADING -> {
                    binding.apply {
                        syncStatus.isVisible = true
                        progress.isVisible = true
                        progress.progress = syncProgress.data?.toInt() ?: 0
                        syncStatus.setBackgroundResource(R.color.dash_blue)
                        syncStatus.alpha = 0.90f
                        syncMessage.text = getString(R.string.sync_in_progress_not_complete)
                        searchHeaderAdapter.allowSpaceForMessage = true
                        recenterMapBtnSpacer.isVisible = true
                    }
                }
                Status.SUCCESS -> {
                    clearSyncStatus(binding)
                }
                Status.ERROR -> {
                    showError(binding)
                }
                Status.CANCELED -> {
                    // this is not currently used
                    binding.apply {
                        syncStatus.isVisible = true
                        searchHeaderAdapter.allowSpaceForMessage = true
                        recenterMapBtnSpacer.isVisible = true
                        syncMessage.text = getString(R.string.sync_in_progress_canceled)
                    }
                }
            }
        }
    }

    private fun showError(binding: FragmentSearchBinding) {
        binding.apply {
            syncStatus.isVisible = true
            syncStatus.setBackgroundResource(R.color.dash_red)
            syncStatus.alpha = 1.0f
            progress.isVisible = false
            searchHeaderAdapter.allowSpaceForMessage = true
            recenterMapBtnSpacer.isVisible = true
            when (lastSyncProgress.exception) {
                is FirebaseNetworkException -> {
                    // if the network is unreachable, show the error for 15 seconds
                    syncMessage.text = getString(R.string.sync_in_progress_network_error)
                    Handler(Looper.getMainLooper())
                        .postDelayed(
                            {
                                clearSyncStatus(binding)
                                viewModel.setObservedLastError()
                            },
                            15000
                        )
                }
                else -> syncMessage.text = getString(R.string.sync_in_progress_error)
            }
        }
    }

    // passing binding avoids IllegalStateException in onStateChanged callback
    private fun clearSyncStatus(binding: FragmentSearchBinding) {
        binding.apply {
            syncStatus.isVisible = false
            searchHeaderAdapter.allowSpaceForMessage = false
            recenterMapBtnSpacer.isVisible = false
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

    private fun refreshManageGpsView() {
        val isLocationEnabled = viewModel.isLocationEnabled.value ?: false
        val isNearby = viewModel.filterMode.value == FilterMode.Nearby
        val isTabDisabled = !isLocationEnabled && isNearby
        searchHeaderAdapter.controlsVisible = !isTabDisabled

        if (isTabDisabled) {
            binding.manageGpsView.root.isVisible = true
        }

        binding.manageGpsView.root
            .animate()
            .alpha(if (isTabDisabled) 1f else 0f)
            .setDuration(300)
            .withEndAction { binding.manageGpsView.root.isVisible = isTabDisabled }
            .start()
    }

    private fun setupFilters(bottomSheet: BottomSheetBehavior<ConstraintLayout>, topic: ExploreTopic) {
        val defaultMode =
            when {
                topic == ExploreTopic.ATMs -> FilterMode.All
                isLocationPermissionGranted -> FilterMode.Nearby
                else -> FilterMode.Online
            }

        viewModel.setFilterMode(defaultMode)

        searchHeaderAdapter.setOnFilterOptionChosen { mode ->
            if (topic == ExploreTopic.Merchants) {
                when (mode) {
                    FilterMode.Online ->
                        analyticsService.logEvent(AnalyticsConstants.Explore.ONLINE_MERCHANTS, bundleOf())
                    FilterMode.Nearby ->
                        analyticsService.logEvent(AnalyticsConstants.Explore.NEARBY_MERCHANTS, bundleOf())
                    else -> analyticsService.logEvent(AnalyticsConstants.Explore.ALL_MERCHANTS, bundleOf())
                }
            } else {
                when (mode) {
                    FilterMode.Buy -> analyticsService.logEvent(AnalyticsConstants.Explore.BUY_ATM, bundleOf())
                    FilterMode.Sell -> analyticsService.logEvent(AnalyticsConstants.Explore.SELL_ATM, bundleOf())
                    FilterMode.BuySell -> analyticsService.logEvent(AnalyticsConstants.Explore.BUY_SELL_ATM, bundleOf())
                    else -> analyticsService.logEvent(AnalyticsConstants.Explore.ALL_ATM, bundleOf())
                }
            }
            viewModel.setFilterMode(mode)
        }

        searchHeaderAdapter.setOnFilterButtonClicked {
            if (topic == ExploreTopic.Merchants) {
                analyticsService.logEvent(AnalyticsConstants.Explore.FILTER_MERCHANTS_TOP, bundleOf())
            } else {
                analyticsService.logEvent(AnalyticsConstants.Explore.FILTER_ATM_TOP, bundleOf())
            }
            openFilters()
        }

        binding.filterPanel.setOnClickListener {
            if (topic == ExploreTopic.Merchants) {
                analyticsService.logEvent(AnalyticsConstants.Explore.FILTER_MERCHANTS_BOTTOM, bundleOf())
            } else {
                analyticsService.logEvent(AnalyticsConstants.Explore.FILTER_ATM_BOTTOM, bundleOf())
            }
            openFilters()
        }

        viewModel.filterMode.observe(viewLifecycleOwner) { mode ->
            binding.noResultsPanel.isVisible = false
            searchHeaderAdapter.title = getSearchTitle()
            searchHeaderAdapter.subtitle = getSearchSubtitle()
            searchHeaderAdapter.setFilterMode(mode)
            binding.filterPanel.isVisible = shouldShowFiltersPanel()
            refreshManageGpsView()

            if (mode == FilterMode.Online) {
                bottomSheet.isDraggable = false

                if (viewModel.selectedItem.value == null) {
                    bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
                    bottomSheetWasExpanded = true
                }
            } else {
                if (isLocationPermissionGranted) {
                    bottomSheet.state = setBottomSheetState()
                    bottomSheet.isDraggable = isBottomSheetDraggable()
                    bottomSheetWasExpanded = false
                } else if (!hasLocationBeenRequested) {
                    requestLocationPermission(viewModel.exploreTopic, configuration, permissionRequestLauncher)
                    // Shouldn't show location request on filter option switch more than once per
                    // session
                    hasLocationBeenRequested = true
                }
            }
        }
    }

    private fun showLoginDialog() {
        DashDirectLoginInfoDialog.custom(
                R.drawable.ic_dashdirect_logo,
                getString(R.string.buy_a_gift_card_with_your_dash_direct_account),
                getString(R.string.create_an_account_at_dash_direct_or_log_in_to_the_existing_one),
                getString(R.string.login),
                getString(R.string.create_new_account),
                "dashdirect.org"
            )
            .show(
                requireActivity(),
                onResult = {
                    if (it == true) {
                        safeNavigate(
                            SearchFragmentDirections.searchToDashDirectUserAuthFragment(
                                DashDirectUserAuthFragment.DashDirectUserAuthType.CREATE_ACCOUNT
                            )
                        )
                    } else {
                        safeNavigate(
                            SearchFragmentDirections.searchToDashDirectUserAuthFragment(
                                DashDirectUserAuthFragment.DashDirectUserAuthType.SIGN_IN
                            )
                        )
                    }
                },
                onExtraMessageAction = {
                    requireContext()
                        .startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DashDirectConstants.DASH_DIRECT_URL)))
                }
            )
    }

    private fun openPurchaseGiftCardFragment() {
        safeNavigate(SearchFragmentDirections.searchToPurchaseGiftCardFragment())
        viewModel.logEvent(AnalyticsConstants.Explore.MERCHANT_DETAILS_BUY_GIFT_CARD)
    }

    private fun setupSearchInput(bottomSheet: BottomSheetBehavior<ConstraintLayout>) {
        searchHeaderAdapter.setOnSearchQueryChanged {
            binding.noResultsPanel.isVisible = false
            viewModel.submitSearchQuery(it)
        }

        searchHeaderAdapter.setOnSearchQuerySubmitted { hideKeyboard() }

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

    private fun setupSearchResults() {
        binding.searchResults.setOnScrollChangeListener { _, _, _, _, _ ->
            binding.upButton.isVisible = shouldShowUpButton()
        }

        binding.upButton.setOnClickListener {
            binding.searchResults.scrollToPosition(0)
            if (isMerchantTopic) {
                viewModel.logEvent(AnalyticsConstants.Explore.MERCHANT_DETAILS_SCROLL_UP)
            }
        }

        binding.resetFiltersBtn.setOnClickListener {
            viewModel.clearFilters()
            searchHeaderAdapter.clearSearchQuery()
            binding.resetFiltersBtn.isEnabled = false
        }

        viewModel.pagingSearchResults.observe(viewLifecycleOwner) { results ->
            searchResultsAdapter.submitData(viewLifecycleOwner.lifecycle, results)
        }

        viewModel.pagingSearchResultsCount.observe(viewLifecycleOwner) {
            searchHeaderAdapter.subtitle = getSearchSubtitle()
            binding.noResultsPanel.isVisible = it <= 0
        }

        viewModel.searchLocationName.observe(viewLifecycleOwner) { searchHeaderAdapter.title = getSearchTitle() }

        viewModel.appliedFilters.observe(viewLifecycleOwner) { filters ->
            resolveAppliedFilters(filters)
            searchHeaderAdapter.subtitle = getSearchSubtitle()
            binding.resetFiltersBtn.isEnabled =
                filters.query.isNotEmpty() ||
                    filters.radius != ExploreViewModel.DEFAULT_RADIUS_OPTION ||
                    filters.payment.isNotEmpty() ||
                    filters.territory.isNotEmpty()
        }

        viewModel.selectedTerritory.observe(viewLifecycleOwner) {
            val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
            bottomSheet.isDraggable = isBottomSheetDraggable()
            bottomSheet.state = setBottomSheetState()
        }

        viewLifecycleOwner.observeOnDestroy { binding.searchResults.adapter = null }
    }

    private fun setupItemDetails() {
        binding.itemDetails.setOnSendDashClicked { isPayingWithDash ->
            if (isPayingWithDash) {
                viewModel.logEvent(AnalyticsConstants.Explore.MERCHANT_DETAILS_PAY_WITH_DASH)
            }

            deepLinkNavigate(DeepLinkDestination.SendDash)
        }
        binding.itemDetails.setOnReceiveDashClicked { deepLinkNavigate(DeepLinkDestination.ReceiveDash) }
        binding.itemDetails.setOnBackButtonClicked { viewModel.backFromMerchantLocation() }
        binding.itemDetails.setOnShowAllLocationsClicked {
            viewModel.selectedItem.value?.let { merchant ->
                if (merchant is Merchant && merchant.merchantId != null && !merchant.source.isNullOrEmpty()) {
                    viewModel.openAllMerchantLocations(merchant.merchantId!!, merchant.source!!)
                }
            }
        }

        viewModel.selectedItem.observe(viewLifecycleOwner) { item ->
            if (item != null) {
                binding.itemDetails.bindItem(item)
                binding.toolbarTitle.text = item.name
            } else {
                binding.toolbarTitle.text = getToolbarTitle()
            }
        }
        binding.itemDetails.setOnNavigationButtonClicked {
            if (isMerchantTopic) {
                viewModel.logEvent(AnalyticsConstants.Explore.MERCHANT_DETAILS_NAVIGATION)
            }
        }
        binding.itemDetails.setOnDialPhoneButtonClicked {
            if (isMerchantTopic) {
                viewModel.logEvent(AnalyticsConstants.Explore.MERCHANT_DETAILS_DIAL_PHONE_CALL)
            }
        }
        binding.itemDetails.setOnOpenWebsiteButtonClicked {
            if (isMerchantTopic) {
                viewModel.logEvent(AnalyticsConstants.Explore.MERCHANT_DETAILS_OPEN_WEBSITE)
            }
        }

        binding.itemDetails.setOnBuyGiftCardButtonClicked {
            if (!viewModel.isUserSignInDashDirect()) {
                showLoginDialog()
            } else {
                openPurchaseGiftCardFragment()
            }
        }

        binding.itemDetails.setOnDashDirectLogOutClicked {
            if (viewModel.isUserSignInDashDirect()) {
                lifecycleScope.launch { viewModel.logout() }
            }
        }
    }

    private fun setupScreenTransitions() {
        viewModel.screenState.observe(viewLifecycleOwner) { state ->
            lifecycleScope.launch {
                if (isKeyboardShowing) {
                    delay(100)
                }

                when (state) {
                    ScreenState.SearchResults -> {
                        transitToSearchResults()

                        if (savedSearchScrollPosition > 0) {
                            binding.searchResults.scrollToPosition(savedSearchScrollPosition)
                            savedSearchScrollPosition = -1
                        }
                    }
                    ScreenState.Details,
                    ScreenState.DetailsGrouped -> {
                        val layoutManager = binding.searchResults.layoutManager as LinearLayoutManager
                        val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()

                        if (previousScreenState == ScreenState.SearchResults) {
                            savedSearchScrollPosition = firstVisiblePosition
                        } else if (
                            state == ScreenState.Details && previousScreenState == ScreenState.MerchantLocations
                        ) {
                            savedLocationsScrollPosition = firstVisiblePosition
                        }

                        transitToDetails()
                    }
                    ScreenState.MerchantLocations -> {
                        if (viewModel.exploreTopic == ExploreTopic.Merchants) {
                            transitToAllMerchantLocations(savedLocationsScrollPosition > 0)

                            if (savedLocationsScrollPosition > 0) {
                                binding.searchResults.scrollToPosition(savedLocationsScrollPosition)
                                savedLocationsScrollPosition = -1
                            }
                        }
                    }
                    else -> {}
                }

                previousScreenState = state
            }
        }
    }

    private fun setupBackNavigation() {
        binding.backToNearestBtn.setOnClickListener { viewModel.backFromAllMerchantLocations() }

        val hardBackAction = {
            if (viewModel.selectedItem.value != null) {
                viewModel.openSearchResults()
            } else {
                findNavController().popBackStack()
            }
        }

        binding.toolbar.setNavigationOnClickListener {
            hardBackAction.invoke()
            if (isMerchantTopic) {
                viewModel.logEvent(AnalyticsConstants.Explore.MERCHANT_DETAILS_BACK_TOP)
            }
        }

        requireActivity()
            .onBackPressedDispatcher
            .addCallback(
                viewLifecycleOwner,
                object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() {
                        hardBackAction.invoke()
                        if (isMerchantTopic) {
                            viewModel.logEvent(AnalyticsConstants.Explore.MERCHANT_DETAILS_BACK_BOTTOM)
                        }
                    }
                }
            )
    }

    private fun transitToDetails() {
        val item = viewModel.selectedItem.value ?: return
        val isOnline = item.type == MerchantType.ONLINE

        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheetWasExpanded = bottomSheet.state == BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.isDraggable = isBottomSheetDraggable()
        bottomSheet.state = setBottomSheetState(isOnline)

        binding.itemDetails.isVisible = true
        binding.upButton.isVisible = false
        binding.filterPanel.isVisible = false

        val animResults = ObjectAnimator.ofFloat(binding.searchResults, View.ALPHA, 0f)
        val animBackButton = ObjectAnimator.ofFloat(binding.backToNearestBtn, View.ALPHA, 0f)
        val animDrag = ObjectAnimator.ofFloat(binding.dragIndicator, View.ALPHA, 0f)
        val animDetails = ObjectAnimator.ofFloat(binding.itemDetails, View.ALPHA, 1f)
        AnimatorSet()
            .apply {
                playTogether(animResults, animBackButton, animDrag, animDetails)
                duration = 200
                doOnEnd {
                    binding.searchResults.isVisible = false
                    binding.dragIndicator.isVisible = false
                    binding.backToNearestBtn.isVisible = false
                }
            }
            .start()
    }

    private fun transitToSearchResults() {
        binding.upButton.isVisible = shouldShowUpButton()
        binding.backToNearestBtn.isVisible = false
        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.isDraggable = isBottomSheetDraggable()
        bottomSheet.expandedOffset = resources.getDimensionPixelOffset(R.dimen.default_expanded_offset)
        bottomSheet.state = setBottomSheetState(bottomSheetWasExpanded)

        if (binding.searchResults.itemDecorationCount < 1) {
            binding.searchResults.addItemDecoration(searchResultsDecorator)
        }

        binding.searchResults.adapter = ConcatAdapter(searchHeaderAdapter, searchResultsAdapter)
        searchHeaderAdapter.searchText = viewModel.searchQuery

        val layoutParams = binding.searchResults.layoutParams as ConstraintLayout.LayoutParams
        layoutParams.topMargin = resources.getDimensionPixelOffset(R.dimen.search_results_margin_top)
        binding.searchResults.isVisible = true
        binding.dragIndicator.isVisible = true

        val animResults = ObjectAnimator.ofFloat(binding.searchResults, View.ALPHA, 1f)
        val animDrag = ObjectAnimator.ofFloat(binding.dragIndicator, View.ALPHA, 1f)
        val animDetails = ObjectAnimator.ofFloat(binding.itemDetails, View.ALPHA, 0f)
        AnimatorSet()
            .apply {
                playTogether(animResults, animDrag, animDetails)
                duration = 200
                doOnEnd {
                    binding.itemDetails.isVisible = false
                    binding.filterPanel.isVisible = shouldShowFiltersPanel()
                }
            }
            .start()
    }

    private fun transitToAllMerchantLocations(expand: Boolean) {
        binding.upButton.isVisible = shouldShowUpButton()
        binding.filterPanel.isVisible = false

        val canShowNearest = viewModel.canShowNearestLocation()
        binding.backToNearestBtn.isVisible = canShowNearest

        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.isDraggable = isBottomSheetDraggable()
        bottomSheet.expandedOffset = resources.getDimensionPixelOffset(R.dimen.all_locations_expanded_offset)
        bottomSheet.state = setBottomSheetState(expand)

        viewModel.selectedItem.value?.let { item ->
            val header =
                MerchantLocationsHeaderAdapter(
                    item.name ?: "",
                    binding.itemDetails.getMerchantType(item.type),
                    item.logoLocation ?: ""
                )

            if (binding.searchResults.itemDecorationCount > 0) {
                binding.searchResults.removeItemDecorationAt(0)
            }

            binding.searchResults.adapter = ConcatAdapter(header, merchantLocationsAdapter)
            val layoutParams = binding.searchResults.layoutParams as ConstraintLayout.LayoutParams
            layoutParams.topMargin = resources.getDimensionPixelOffset(R.dimen.all_locations_margin_top)
            binding.searchResults.isVisible = true
        }
        binding.dragIndicator.isVisible = false

        val animResults = ObjectAnimator.ofFloat(binding.searchResults, View.ALPHA, 1f)
        val animBackButton = ObjectAnimator.ofFloat(binding.backToNearestBtn, View.ALPHA, 1f)
        val animDetails = ObjectAnimator.ofFloat(binding.itemDetails, View.ALPHA, 0f)
        AnimatorSet()
            .apply {
                playTogether(animResults, animBackButton, animDetails)
                duration = 200
                doOnEnd { binding.itemDetails.isVisible = false }
            }
            .start()
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

        val searchLocation =
            if (viewModel.selectedTerritory.value?.isNotEmpty() == true) {
                viewModel.selectedTerritory.value
            } else {
                val radiusOption = viewModel.selectedRadiusOption.value ?: ExploreViewModel.DEFAULT_RADIUS_OPTION
                resources.getQuantityString(
                    if (viewModel.isMetric) R.plurals.radius_kilometers else R.plurals.radius_miles,
                    radiusOption,
                    radiusOption
                )
            }

        val resultSize = viewModel.pagingSearchResultsCount.value ?: 0
        val quantityStr =
            if (viewModel.exploreTopic == ExploreTopic.Merchants) {
                if (resultSize == 0) {
                    getString(R.string.explore_no_merchants)
                } else {
                    resources.getQuantityString(R.plurals.explore_merchant_amount, resultSize, resultSize)
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
                    filters.radius,
                    filters.radius
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
                viewModel.selectedTerritory.value?.isNotEmpty() == true)
    }

    private fun openFilters() {
        safeNavigate(SearchFragmentDirections.searchToFilters())
    }

    private fun hideKeyboard() {
        val inputManager = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        inputManager?.hideSoftInputFromWindow(requireActivity().window.decorView.windowToken, 0)
    }

    private fun shouldShowUpButton(): Boolean {
        val offset = binding.searchResults.computeVerticalScrollOffset()
        return offset > SCROLL_OFFSET_FOR_UP
    }

    private fun isBottomSheetDraggable(): Boolean {
        val screenState = viewModel.screenState.value
        val isDetails = screenState == ScreenState.DetailsGrouped || screenState == ScreenState.Details
        val nearbySearch =
            viewModel.selectedTerritory.value.isNullOrEmpty() && viewModel.isLocationEnabled.value == true

        return !isDetails && nearbySearch
    }

    @BottomSheetBehavior.State
    private fun setBottomSheetState(forceExpand: Boolean = false): Int {
        val screenState = viewModel.screenState.value
        val isDetails = screenState == ScreenState.DetailsGrouped || screenState == ScreenState.Details
        val nearbySearch =
            viewModel.selectedTerritory.value.isNullOrEmpty() && viewModel.isLocationEnabled.value == true

        return when {
            forceExpand -> BottomSheetBehavior.STATE_EXPANDED
            isDetails -> BottomSheetBehavior.STATE_HALF_EXPANDED
            nearbySearch -> BottomSheetBehavior.STATE_HALF_EXPANDED
            else -> BottomSheetBehavior.STATE_EXPANDED
        }
    }
}
