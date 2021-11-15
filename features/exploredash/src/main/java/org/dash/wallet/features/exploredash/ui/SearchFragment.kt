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

package org.dash.wallet.features.exploredash.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
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
import javax.inject.Inject

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class SearchFragment : Fragment(R.layout.fragment_search) {
    companion object {
        private const val SCROLL_OFFSET_FOR_UP = 700
    }

    @Inject
    lateinit var configuration: Configuration

    private val binding by viewBinding(FragmentSearchBinding::bind)
    private val viewModel: ExploreViewModel by activityViewModels()
    private val args by navArgs<SearchFragmentArgs>()

    private var bottomSheetWasExpanded: Boolean = false
    private var isKeyboardShowing: Boolean = false
    private var hasLocationBeenRequested: Boolean = false

    private val permissionRequestLauncher = registerPermissionLauncher { isGranted ->
        if (isGranted) {
            viewModel.monitorUserLocation()
        }
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

        setupBackNavigation()

        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_info) {
                safeNavigate(SearchFragmentDirections.exploreToInfo())
            }
            true
        }

        val binding = binding // Avoids IllegalStateException in onStateChanged callback
        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.halfExpandedRatio = ResourcesCompat.getFloat(resources,
            if (args.type == ExploreTopic.Merchants) R.dimen.merchant_half_expanded_ratio else R.dimen.atm_half_expanded_ratio
        )

        val header = SearchHeaderAdapter(args.type)
        setupFilters(header, bottomSheet, args.type)
        setupSearchInput(header, bottomSheet)
        setupSearchResults(header)
        setupItemDetails()

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

        viewModel.appliedFilters.observe(viewLifecycleOwner) { filters ->
            resolveAppliedFilters(filters)
            header.subtitle = getSearchSubtitle()
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

        header.setOnFilterOptionChosen { _, index ->
            if (topic == ExploreTopic.Merchants) {
                viewModel.setFilterMode(
                    when (index) {
                        0 -> FilterMode.Online
                        1 -> FilterMode.Physical
                        else -> FilterMode.All
                    }
                )
            } else {
                viewModel.setFilterMode(
                    when (index) {
                        1 -> FilterMode.Buy
                        2 -> FilterMode.Sell
                        3 -> FilterMode.BuySell
                        else -> FilterMode.All
                    }
                )
            }
        }

        header.setOnFilterButtonClicked {
            openFilters()
        }

        binding.filterPanel.setOnClickListener {
            openFilters()
        }

        viewModel.filterMode.observe(viewLifecycleOwner) { mode ->
            header.title = getSearchTitle()
            header.subtitle = getSearchSubtitle()

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
        val adapter = MerchantsAtmsResultAdapter { item, _ ->
            hideKeyboard()

            if (item is Merchant) {
                viewModel.openMerchantDetails(item)
            } else if (item is Atm) {
                viewModel.openAtmDetails(item)
            }
        }

        val divider = ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!
        val decorator = ListDividerDecorator(
            divider,
            showAfterLast = false,
            marginStart = resources.getDimensionPixelOffset(R.dimen.divider_margin_start)
        )
        binding.searchResults.addItemDecoration(decorator)
        binding.searchResults.adapter = ConcatAdapter(header, adapter)

        binding.searchResults.setOnScrollChangeListener { _, _, _, _, _ ->
            binding.upButton.isVisible = shouldShowUpButton()
        }

        binding.upButton.setOnClickListener {
            binding.searchResults.scrollToPosition(0)
        }

        viewModel.pagingSearchResults.observe(viewLifecycleOwner) { results ->
            adapter.submitData(viewLifecycleOwner.lifecycle, results)
        }

        viewModel.searchLocationName.observe(viewLifecycleOwner) {
            header.title = getSearchTitle()
        }

        viewModel.physicalSearchResults.observe(viewLifecycleOwner) {
            header.subtitle = getSearchSubtitle()
        }

        viewLifecycleOwner.observeOnDestroy {
            binding.searchResults.adapter = null
        }
    }

    private fun setupItemDetails() {
        viewModel.selectedItem.observe(viewLifecycleOwner) { item ->
            if (item != null) {
                binding.toolbarTitle.text = item.name

                if (item is Merchant) {
                    bindMerchantDetails(item)
                } else if (item is Atm) {
                    bindAtmDetails(item)
                }

                lifecycleScope.launch {
                    if (isKeyboardShowing) {
                        delay(100)
                    }

                    transitToDetails(viewModel.filterMode.value == FilterMode.Online ||
                            item.type == MerchantType.ONLINE)
                }
            } else {
                binding.toolbarTitle.text = getToolbarTitle()
                transitToSearchResults()
            }
        }
    }

    private fun setupBackNavigation() {
        val onBackButtonAction = {
            if (viewModel.selectedItem.value != null) {
                viewModel.openSearchResults()
                transitToSearchResults()
            } else {
                findNavController().popBackStack()
            }
        }

        binding.toolbar.setNavigationOnClickListener {
            onBackButtonAction.invoke()
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    onBackButtonAction.invoke()
                }
            })
    }

    private fun bindCommonDetails(item: SearchResult, isOnline: Boolean) {
        binding.itemDetails.apply {
            itemName.text = item.name
            itemAddress.text = item.displayAddress

            linkBtn.isVisible = !item.website.isNullOrEmpty()
            linkBtn.setOnClickListener {
                openWebsite(item.website!!)
            }

            directionBtn.isVisible = !isOnline &&
                    ((item.latitude != null && item.longitude != null) ||
                            !item.googleMaps.isNullOrBlank())
            directionBtn.setOnClickListener {
                openMaps(item)
            }

            callBtn.isVisible = !isOnline && !item.phone.isNullOrEmpty()
            callBtn.setOnClickListener {
                dialPhone(item.phone!!)
            }
        }
    }

    private fun bindMerchantDetails(merchant: Merchant) {
        binding.itemDetails.apply {
            buySellContainer.isVisible = false
            locationHint.isVisible = false

            Glide.with(requireContext())
                .load(merchant.logoLocation)
                .error(R.drawable.ic_image_placeholder)
                .transform(RoundedCorners(resources.getDimensionPixelSize(R.dimen.logo_corners_radius)))
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .into(itemImage)

            itemType.text = when (cleanMerchantTypeValue(merchant.type)) {
                MerchantType.ONLINE -> resources.getString(R.string.explore_online_merchant)
                MerchantType.PHYSICAL -> resources.getString(R.string.explore_physical_merchant)
                MerchantType.BOTH -> resources.getString(R.string.explore_both_types_merchant)
                else -> ""
            }

            val isOnline = viewModel.filterMode.value == FilterMode.Online ||
                    merchant.type == MerchantType.ONLINE
            itemAddress.isVisible = !isOnline

            val isDash = merchant.paymentMethod == PaymentMethod.DASH
            val drawable = ResourcesCompat.getDrawable(
                resources,
                if (isDash) R.drawable.ic_dash else R.drawable.ic_gift_card_white, null
            )
            payBtn.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)

            if (isDash) {
                payBtn.isVisible = true
                payBtn.text = getText(R.string.explore_pay_with_dash)
                payBtn.setOnClickListener { viewModel.sendDash() }
            } else {
                payBtn.isVisible = !merchant.deeplink.isNullOrBlank()
                payBtn.text = getText(R.string.explore_buy_gift_card)
                payBtn.setOnClickListener { openDeeplink(merchant.deeplink!!) }
            }

            if (isOnline) {
                root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    matchConstraintPercentHeight = 1f
                }
                root.updatePaddingRelative(top = resources.getDimensionPixelOffset(R.dimen.details_online_margin_top))
            } else {
                root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    matchConstraintPercentHeight =
                        ResourcesCompat.getFloat(resources, R.dimen.merchant_details_height_ratio)
                }
                root.updatePaddingRelative(top = resources.getDimensionPixelOffset(R.dimen.details_physical_margin_top))
            }

            bindCommonDetails(merchant, isOnline)
        }
    }

    private fun bindAtmDetails(atm: Atm) {
        binding.itemDetails.apply {
            payBtn.isVisible = false
            manufacturer.text = atm.manufacturer?.replaceFirstChar { it.uppercase() }
            itemType.isVisible = false

            sellBtn.setOnClickListener {
                viewModel.sendDash()
            }

            buyBtn.setOnClickListener {
                viewModel.receiveDash()
            }

            when (atm.type) {
                AtmType.BUY -> {
                    buyBtn.isVisible = true
                    sellBtn.isVisible = false
                }
                AtmType.SELL -> {
                    buyBtn.isVisible = false
                    sellBtn.isVisible = true
                }
                AtmType.BOTH -> {
                    buyBtn.isVisible = true
                    sellBtn.isVisible = true
                }
            }

            root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                matchConstraintPercentHeight =
                    ResourcesCompat.getFloat(resources, R.dimen.atm_details_height_ratio)
            }

            Glide.with(requireContext())
                .load(atm.logoLocation)
                .error(R.drawable.ic_image_placeholder)
                .transform(RoundedCorners(resources.getDimensionPixelSize(R.dimen.logo_corners_radius)))
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .into(logoImg)

            Glide.with(requireContext())
                .load(atm.coverImage)
                .placeholder(R.drawable.ic_image_placeholder)
                .error(R.drawable.ic_image_placeholder)
                .transform(RoundedCorners(resources.getDimensionPixelSize(R.dimen.logo_corners_radius)))
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .into(itemImage)

            bindCommonDetails(atm, false)
        }
    }

    private fun transitToDetails(fullHeight: Boolean) {
        binding.upButton.isVisible = false
        binding.filterPanel.isVisible = false

        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.isDraggable = false
        bottomSheetWasExpanded = bottomSheet.state == BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.state =
            if (fullHeight) BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_HALF_EXPANDED

        binding.itemDetails.root.isVisible = true

        val animResults = ObjectAnimator.ofFloat(binding.searchResults, View.ALPHA, 0f)
        val animDrag = ObjectAnimator.ofFloat(binding.dragIndicator, View.ALPHA, 0f)
        val animDetails = ObjectAnimator.ofFloat(binding.itemDetails.root, View.ALPHA, 1f)
        AnimatorSet().apply {
            playTogether(animResults, animDrag, animDetails)
            duration = 200
            doOnEnd {
                binding.searchResults.isVisible = false
                binding.dragIndicator.isVisible = false
            }
        }.start()
    }

    private fun transitToSearchResults() {
        binding.upButton.isVisible = shouldShowUpButton()
        binding.filterPanel.isVisible = shouldShowFiltersPanel()

        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.isDraggable = viewModel.isLocationEnabled.value == true &&
                viewModel.filterMode.value != FilterMode.Online

        bottomSheet.state = if (bottomSheetWasExpanded) {
            BottomSheetBehavior.STATE_EXPANDED
        } else {
            BottomSheetBehavior.STATE_HALF_EXPANDED
        }

        binding.searchResults.isVisible = true
        binding.dragIndicator.isVisible = true

        val animResults = ObjectAnimator.ofFloat(binding.searchResults, View.ALPHA, 1f)
        val animDrag = ObjectAnimator.ofFloat(binding.dragIndicator, View.ALPHA, 1f)
        val animDetails = ObjectAnimator.ofFloat(binding.itemDetails.root, View.ALPHA, 0f)
        AnimatorSet().apply {
            playTogether(animResults, animDrag, animDetails)
            duration = 200
            doOnEnd {
                binding.itemDetails.root.isVisible = false
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
        if (viewModel.isLocationEnabled.value != true ||
            (viewModel.exploreTopic == ExploreTopic.Merchants &&
                    viewModel.filterMode.value != FilterMode.Physical)) {
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

        val resultSize = viewModel.physicalSearchResults.value?.size ?: 0
        val quantityStr = if (viewModel.exploreTopic == ExploreTopic.Merchants) {
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

    private fun resolveAppliedFilters(filters: FilterOptions?) {
        val appliedFilterNames = mutableListOf<String>()

        filters?.let {
            if (filters.payment.isNotEmpty()) {
                appliedFilterNames.add(getString(
                    if (filters.payment == PaymentMethod.DASH) {
                        R.string.explore_pay_with_dash
                    } else {
                        R.string.explore_pay_gift_card
                    }
                ))
            }

            if (filters.territory.isNotEmpty()) {
                appliedFilterNames.add(filters.territory)
            }

            if (filters.radius != ExploreViewModel.DEFAULT_RADIUS_OPTION) {
                appliedFilterNames.add(resources.getQuantityString(
                    if (viewModel.isMetric) R.plurals.radius_kilometers else R.plurals.radius_miles,
                    filters.radius, filters.radius
                ))
            }
        }

        binding.filterPanel.isVisible = appliedFilterNames.any() && viewModel.selectedItem.value == null
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
        return viewModel.appliedFilters.value != null &&
                (viewModel.paymentMethodFilter.isNotEmpty() ||
                 viewModel.selectedTerritory.isNotEmpty() ||
                 viewModel.selectedRadiusOption != ExploreViewModel.DEFAULT_RADIUS_OPTION)
    }

    private fun openFilters() {
        safeNavigate(SearchFragmentDirections.searchToFilters())
    }

    private fun openMaps(item: SearchResult) {
        val uri = if (!item.googleMaps.isNullOrBlank()) {
            item.googleMaps
        } else {
            getString(R.string.explore_maps_intent_uri, item.latitude!!, item.longitude!!)
        }

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
        startActivity(intent)
    }

    private fun dialPhone(phone: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel: $phone"))
        startActivity(intent)
    }

    private fun openWebsite(website: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(website))
        startActivity(intent, null)
    }

    private fun openDeeplink(link: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        startActivity(intent, null)
    }

    private fun hideKeyboard() {
        val inputManager = requireContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        inputManager?.hideSoftInputFromWindow(requireActivity().window.decorView.windowToken, 0)
    }

    private fun cleanMerchantTypeValue(value: String?): String? {
        return value?.trim()?.lowercase()?.replace(" ", "_")
    }

    private fun shouldShowUpButton(): Boolean {
        val offset = binding.searchResults.computeVerticalScrollOffset()
        return offset > SCROLL_OFFSET_FOR_UP
    }
}
