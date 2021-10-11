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

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.*
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.ListDividerDecorator
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.databinding.FragmentSearchBinding
import org.dash.wallet.features.exploredash.ui.adapters.MerchantsAtmsResultAdapter
import org.dash.wallet.features.exploredash.ui.dialogs.TerritoryFilterDialog
import androidx.core.view.ViewCompat.animate
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import org.dash.wallet.features.exploredash.data.model.MerchantType


@AndroidEntryPoint
class SearchFragment : Fragment(R.layout.fragment_search) {
    private val binding by viewBinding(FragmentSearchBinding::bind)
    private val viewModel: ExploreViewModel by activityViewModels()
    private var bottomSheetWasExpanded: Boolean = false

    // TODO: re-integrate when the permission request story is ready
//    private lateinit var fusedLocationClient: FusedLocationProviderClient
//    private val permissionRequestLauncher = registerForActivityResult(
//        ActivityResultContracts.RequestPermission()) { isGranted ->
//        if (isGranted) {
//            Log.i("LOCATION", "permission granted")
//        } else {
//            Log.i("LOCATION", "permission NOT granted")
//        }
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//
//        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity())
//
//        if (ActivityCompat.checkSelfPermission(
//                requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
//            ) != PackageManager.PERMISSION_GRANTED
//        ) {
//            permissionRequestLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
//        }
//    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupBackNavigation()
        binding.toolbarTitle.text = getString(R.string.explore_where_to_spend)
        binding.searchTitle.text = "United States" // TODO: use location to resolve

        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.state = BottomSheetBehavior.STATE_HALF_EXPANDED

        setupFilters(bottomSheet)
        setupSearchInput(bottomSheet)
        setupSearchResults()

        viewModel.selectedMerchant.observe(viewLifecycleOwner) { merchant ->
            if (merchant != null) {
                binding.toolbarTitle.text = merchant.name
                transitToDetails(merchant.type == MerchantType.ONLINE)
                bindMerchantDetails(merchant)
            } else {
                binding.toolbarTitle.text = getString(R.string.explore_where_to_spend)
                transitToSearchResults()
            }
        }

        viewModel.init()
    }

    private fun setupFilters(bottomSheet: BottomSheetBehavior<ConstraintLayout>) {
        binding.allOption.setOnClickListener {
            viewModel.setFilterMode(ExploreViewModel.FilterMode.All)
        }

        binding.physicalOption.setOnClickListener {
            viewModel.setFilterMode(ExploreViewModel.FilterMode.Physical)
        }

        binding.onlineOption.setOnClickListener {
            viewModel.setFilterMode(ExploreViewModel.FilterMode.Online)
        }

        binding.filterBtn.setOnClickListener {
            lifecycleScope.launch {
                val territories = viewModel.getTerritoriesWithMerchants()
                TerritoryFilterDialog(territories, viewModel.pickedTerritory) { name, dialog ->
                    lifecycleScope.launch {
                        delay(300)
                        dialog.dismiss()
                        viewModel.pickedTerritory = name
                    }
                }.show(parentFragmentManager, "territory_filter")
            }
        }

        viewModel.filterMode.observe(viewLifecycleOwner) {
            binding.allOption.isChecked = it == ExploreViewModel.FilterMode.All
            binding.allOption.isEnabled = it != ExploreViewModel.FilterMode.All
            binding.physicalOption.isChecked = it == ExploreViewModel.FilterMode.Physical
            binding.physicalOption.isEnabled = it != ExploreViewModel.FilterMode.Physical
            binding.onlineOption.isChecked = it == ExploreViewModel.FilterMode.Online
            binding.onlineOption.isEnabled = it != ExploreViewModel.FilterMode.Online

            if (viewModel.selectedMerchant.value == null && it == ExploreViewModel.FilterMode.Online) {
                bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
    }

    private fun setupSearchInput(bottomSheet: BottomSheetBehavior<ConstraintLayout>) {
        binding.search.doOnTextChanged { text, _, _, _ ->
            binding.clearBtn.isVisible = !text.isNullOrEmpty()
            viewModel.submitSearchQuery(text.toString())
        }

        binding.search.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val inputManager = requireContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                inputManager.toggleSoftInput(0, 0)
            }

            true
        }

        binding.clearBtn.setOnClickListener {
            binding.search.text.clear()
        }

        requireActivity().window?.decorView?.let { decor ->
            ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
                val showingKeyboard = insets.isVisible(WindowInsetsCompat.Type.ime())

                if (showingKeyboard) {
                    bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
                }

                insets
            }
        }
    }

    private fun setupSearchResults() {
        val adapter = MerchantsAtmsResultAdapter { item, _ ->
            if (item is Merchant) {
                viewModel.openMerchantDetails(item)
            }
        }

        val divider = ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!
        val decorator = ListDividerDecorator(divider, false, R.layout.group_header)
        binding.searchResultsList.addItemDecoration(decorator)
        binding.searchResultsList.adapter = adapter

        viewModel.searchResults.observe(viewLifecycleOwner) { results ->
            binding.noResultsText.isVisible = results.isEmpty()
            adapter.submitList(results)
        }
    }

    private fun setupBackNavigation() {
        val onBackButtonAction = {
            if (viewModel.selectedMerchant.value != null) {
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

    private fun bindMerchantDetails(merchant: Merchant) {
        binding.merchantDetails.apply {
            Glide.with(requireContext())
                .load(merchant.logoLocation)
                .error(R.drawable.ic_merchant_placeholder)
                .transform(RoundedCorners(resources.getDimensionPixelSize(R.dimen.logo_corners_radius)))
                .transition(DrawableTransitionOptions.withCrossFade(200))
                .into(merchantLogo)

            merchantName.text = merchant.name
            merchantType.text = when (cleanValue(merchant.type)) {
                MerchantType.ONLINE -> resources.getString(R.string.explore_online_merchant)
                MerchantType.PHYSICAL -> resources.getString(R.string.explore_physical_merchant)
                MerchantType.BOTH -> resources.getString(R.string.explore_both_types_merchant)
                else -> ""
            }
            merchantAddress.text = getString(
                R.string.explore_merchant_address,
                merchant.address1,
                merchant.address2,
                merchant.address4)

            val isOnline = merchant.type == MerchantType.ONLINE
            val drawable = ResourcesCompat.getDrawable(resources,
                if (isOnline) R.drawable.ic_gift_card_white else R.drawable.ic_dash, null)

            payBtn.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)
            merchantAddress.isVisible = !isOnline

            if (isOnline) {
                payBtn.text = getText(R.string.explore_buy_gift_card)
                root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    matchConstraintPercentHeight = 1f
                }
                root.updatePaddingRelative(top = resources.getDimensionPixelOffset(R.dimen.details_online_margin_top))
            } else {
                payBtn.text = getText(R.string.explore_pay_with_dash)
                payBtn.setOnClickListener { viewModel.sendDash() }

                root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    matchConstraintPercentHeight = 0.44f
                }
                root.updatePaddingRelative(top = resources.getDimensionPixelOffset(R.dimen.details_physical_margin_top))
            }

            directionBtn.isVisible = !isOnline && merchant.latitude != null && merchant.longitude != null
            directionBtn.setOnClickListener {
                openMaps(merchant.latitude!!, merchant.longitude!!)
            }

            callBtn.isVisible = !isOnline && !merchant.phone.isNullOrEmpty()
            callBtn.setOnClickListener {
                dialPhone(merchant.phone!!)
            }

            linkBtn.isVisible = !merchant.website.isNullOrEmpty()
            linkBtn.setOnClickListener {
                openWebsite(merchant.website!!)
            }
        }
    }

    private fun transitToDetails(fullHeight: Boolean) {
        binding.toolbarTitle.text = getString(R.string.explore_where_to_spend)

        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.isDraggable = false
        bottomSheetWasExpanded = bottomSheet.state == BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.state = if (fullHeight) BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_HALF_EXPANDED

        animate(binding.searchResults).apply {
            duration = 200
            alpha(0f)
        }.withEndAction {
            binding.searchResults.isVisible = false
        }.start()

        binding.merchantDetails.root.alpha = 0f
        binding.merchantDetails.root.isVisible = true
        animate(binding.merchantDetails.root).apply {
            duration = 200
            startDelay = 200
            alpha(1f)
        }.start()
    }

    private fun transitToSearchResults() {
        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.isDraggable = true

        bottomSheet.state = if (bottomSheetWasExpanded) {
            BottomSheetBehavior.STATE_EXPANDED
        } else  {
            BottomSheetBehavior.STATE_HALF_EXPANDED
        }

        animate(binding.merchantDetails.root).apply {
            duration = 200
            alpha(0f)
        }.withEndAction {
            binding.merchantDetails.root.isVisible = false
        }.start()

        binding.searchResults.isVisible = true
        binding.searchResults.alpha = 0f
        animate(binding.searchResults).apply {
            duration = 200
            startDelay = 200
            alpha(1f)
        }.start()
    }

    private fun openMaps(latitude: Double, longitude: Double) {
        val uri = getString(R.string.explore_maps_intent_uri, latitude, longitude)
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

    private fun cleanValue(value: String?): String? {
        return value?.trim()?.lowercase()?.replace(" ", "_")
    }
}
