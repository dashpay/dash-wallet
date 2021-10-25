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

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.*
import androidx.core.view.ViewCompat.animate
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.maps.android.SphericalUtil
import com.google.maps.android.collections.CircleManager
import com.google.maps.android.collections.MarkerManager
import com.google.maps.android.ktx.awaitMap
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.dash.wallet.common.ui.DialogBuilder
import org.dash.wallet.common.ui.ListDividerDecorator
import org.dash.wallet.common.ui.observeOnDestroy
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.MerchantType
import org.dash.wallet.features.exploredash.data.model.PaymentMethod
import org.dash.wallet.features.exploredash.data.model.SearchResult
import org.dash.wallet.features.exploredash.databinding.FragmentSearchBinding
import org.dash.wallet.features.exploredash.ui.adapters.MerchantsAtmsResultAdapter
import org.dash.wallet.features.exploredash.ui.dialogs.TerritoryFilterDialog


@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class SearchFragment : Fragment(R.layout.fragment_search) {
    private val binding by viewBinding(FragmentSearchBinding::bind)
    private val viewModel: ExploreViewModel by activityViewModels()
    private var bottomSheetWasExpanded: Boolean = false
    private var isKeyboardShowing: Boolean = false
    private var googleMap: GoogleMap? = null
    private lateinit var mCurrentUserLocation: LatLng
    private var currentLocationMarker: Marker? = null
    private var currentLocationCircle: Circle? = null
    private val CURRENT_POSITION_MARKER_TAG = 0

    private var markerManager: MarkerManager? = null
    private var markerCollection: MarkerManager.Collection? = null
    private var circleManager: CircleManager? = null
    private var circleCollection: CircleManager.Collection? = null

    private val permissionRequestLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { isPermissionGranted ->
        if (isPermissionGranted){
            viewModel.monitorUserLocation()
            viewModel.observeCurrentUserLocation.observe(viewLifecycleOwner) {
                showLocationOnMap(it)
            }
        } else {
            showPermissionDeniedDialog()
        }
    }

    private fun showPermissionDeniedDialog() {
        val deniedPermissionDialog = DialogBuilder.warn(
            requireActivity(),
            R.string.permission_required_title,
            R.string.permission_required_message
        )
        deniedPermissionDialog.setPositiveButton(R.string.goto_settings) { _, _ ->
            val intent = createAppSettingsIntent()
            startActivity(intent)
        }
        deniedPermissionDialog.setNegativeButton(R.string.button_dismiss) { dialog: DialogInterface, _ ->
            dialog.dismiss()
        }
        deniedPermissionDialog.show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupBackNavigation()
        binding.toolbarTitle.text = getString(R.string.explore_where_to_spend)
        binding.searchTitle.text = "United States" // TODO: use location to resolve

        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_info) {
                Log.i("EXPLOREDASH", "info menu click")
            }
            true
        }

        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        bottomSheet.addBottomSheetCallback(object: BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) {}

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                val isExpanded = newState == BottomSheetBehavior.STATE_EXPANDED
                binding.appbarDivider.alpha = if (isExpanded) 1f else 0f
                // TODO (ashikhmin): it's better to replace this with android:translationZ="0.1dp"
                // (not supported on API 19) in the appbar to bring it
                // on top of the search results while keeping the shadow off
                animate(binding.dragIndicator).apply {
                    duration = 100
                    alpha(if (isExpanded) 0f else 1f)
                }.start()
            }
        })

        setupFilters(bottomSheet)
        setupSearchInput(bottomSheet)
        setupSearchResults()
        setupMerchantDetails()

        viewModel.init()
        val mapFragment = childFragmentManager.findFragmentById(R.id.explore_map) as SupportMapFragment
        lifecycleScope.launchWhenStarted {
            googleMap = mapFragment.awaitMap()
            checkPermissionOrShowMap()
            setUserLocationMarkerMovementState()
        }
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
                bottomSheetWasExpanded = true
                binding.appbarDivider.alpha = 1f
                binding.dragIndicator.alpha = 0f
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
                hideKeyboard()
            }

            true
        }

        binding.clearBtn.setOnClickListener {
            binding.search.text.clear()
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

    private fun setupSearchResults() {
        val adapter = MerchantsAtmsResultAdapter { item, _ ->
            hideKeyboard()

            if (item is Merchant) {
                viewModel.openMerchantDetails(item)
            }
        }

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                binding.searchResultsList.scrollToPosition(0)
            }
        })

        val divider = ContextCompat.getDrawable(requireContext(), R.drawable.list_divider)!!
        val decorator = ListDividerDecorator(divider, false, R.layout.group_header)
        binding.searchResultsList.addItemDecoration(decorator)
        binding.searchResultsList.adapter = adapter

        viewModel.searchResults.observe(viewLifecycleOwner) { results ->
            binding.noResultsText.isVisible = results.isEmpty()
            adapter.submitList(results)
            renderMerchantsOnMap(results)
            handleClickOnMerchantMarker(results)
        }

        viewLifecycleOwner.observeOnDestroy {
            binding.searchResultsList.adapter = null
        }
    }

    private fun setupMerchantDetails() {
        viewModel.selectedMerchant.observe(viewLifecycleOwner) { merchant ->
            if (merchant != null) {
                binding.toolbarTitle.text = merchant.name
                bindMerchantDetails(merchant)

                lifecycleScope.launch {
                    if (isKeyboardShowing) {
                        delay(100)
                    }

                    transitToDetails(merchant.type == MerchantType.ONLINE)
                }
            } else {
                binding.toolbarTitle.text = getString(R.string.explore_where_to_spend)
                transitToSearchResults()
            }
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

            val addressBuilder = StringBuilder()
            addressBuilder.append(merchant.address1)

            if (!merchant.address2.isNullOrBlank()) {
                addressBuilder.append("\n${merchant.address2}")
            }

            if (!merchant.address3.isNullOrBlank()) {
                addressBuilder.append("\n${merchant.address3}")
            }

            if (!merchant.address4.isNullOrBlank()) {
                addressBuilder.append("\n${merchant.address4}")
            }

            merchantAddress.text = addressBuilder.toString()

            val isOnline = merchant.type == MerchantType.ONLINE
            merchantAddress.isVisible = !isOnline

            val isGiftCard = merchant.paymentMethod == PaymentMethod.GIFT_CARD
            val drawable = ResourcesCompat.getDrawable(resources,
                if (isGiftCard) R.drawable.ic_gift_card_white else R.drawable.ic_dash, null)
            payBtn.setCompoundDrawablesWithIntrinsicBounds(drawable, null, null, null)

            if (isGiftCard) {
                payBtn.isVisible = !merchant.deeplink.isNullOrBlank()
                payBtn.text = getText(R.string.explore_buy_gift_card)
                payBtn.setOnClickListener { openDeeplink(merchant.deeplink!!) }
            } else {
                payBtn.text = getText(R.string.explore_pay_with_dash)
                payBtn.setOnClickListener { viewModel.sendDash() }
            }

            if (isOnline) {
                root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    matchConstraintPercentHeight = 1f
                }
                root.updatePaddingRelative(top = resources.getDimensionPixelOffset(R.dimen.details_online_margin_top))
            } else {
                root.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    matchConstraintPercentHeight = ResourcesCompat.getFloat(resources, R.dimen.merchant_details_height_ratio)
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
        } else {
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

    private fun openDeeplink(link: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
        startActivity(intent, null)
    }

    private fun hideKeyboard() {
        val inputManager = requireContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager?
        inputManager?.hideSoftInputFromWindow(requireActivity().window.decorView.windowToken, 0)
    }

    private fun cleanValue(value: String?): String? {
        return value?.trim()?.lowercase()?.replace(" ", "_")
    }

    private fun setUserLocationMarkerMovementState() {
        markerCollection?.setOnMarkerDragListener(object : GoogleMap.OnMarkerDragListener {
            override fun onMarkerDragStart(p0: Marker) {
            }

            override fun onMarkerDrag(p0: Marker) {
            }

            override fun onMarkerDragEnd(marker: Marker) {
                mCurrentUserLocation = marker.position
                currentLocationMarker?.position = marker.position
                currentLocationCircle?.center = marker.position
                animateToMeters()
            }
        })

    }

    private fun renderMerchantsOnMap(results: List<SearchResult>) {
        markerCollection?.markers?.forEach { if (it.tag != CURRENT_POSITION_MARKER_TAG) it.remove() }
        results.forEach {
            if (it is Merchant) {
                markerCollection?.addMarker(MarkerOptions().apply {
                    position(LatLng(it.latitude!!, it.longitude!!))
                    anchor(0.5f, 0.5f)
                    val bitmap = getBitmapFromDrawable(R.drawable.merchant_marker)
                    icon(BitmapDescriptorFactory.fromBitmap(bitmap))
                    draggable(false)
                })
            }
        }
    }

    private fun handleClickOnMerchantMarker(results: List<SearchResult>) {
        markerCollection?.setOnMarkerClickListener { marker ->
            if (marker.tag == CURRENT_POSITION_MARKER_TAG) {
                false
            } else {
                val atmItemCoordinates = marker.position
                results.forEach {
                    if (it is Merchant) {
                        if ((it.latitude == atmItemCoordinates.latitude) && (it.longitude == atmItemCoordinates.longitude)) {
                            viewModel.openMerchantDetails(it)
                        }
                    }
                }
                true
            }
        }
    }

    private fun checkPermissionOrShowMap() {
        if (isGooglePlayServicesAvailable()) {
            markerManager = MarkerManager(googleMap)
            markerCollection = markerManager?.newCollection()
            circleManager = googleMap?.let { CircleManager(it) }
            circleCollection = circleManager?.newCollection()
            if (isForegroundLocationPermissionGranted()) {
                viewModel.monitorUserLocation()
                viewModel.observeCurrentUserLocation.observe(viewLifecycleOwner) {
                    showLocationOnMap(it)
                }
            } else {
                permissionRequestLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun addCircleAroundCurrentPosition() {
        currentLocationCircle = circleCollection?.addCircle(CircleOptions().apply {
            center(mCurrentUserLocation)
            radius(1500.0)
            fillColor(Color.parseColor("#26008DE4"))
            strokeColor(Color.TRANSPARENT)
        })
    }

    private fun addMarkerOnCurrentPosition() {
        val bitmap = getBitmapFromDrawable(R.drawable.user_location_map_marker)
        currentLocationMarker = markerCollection?.addMarker(MarkerOptions().apply {
            position(mCurrentUserLocation)
            anchor(0.5f, 0.5f)
            icon(BitmapDescriptorFactory.fromBitmap(bitmap))
            draggable(true)
        })
        currentLocationMarker?.tag = CURRENT_POSITION_MARKER_TAG
    }

    private fun showLocationOnMap(userLocation: UserLocation) {
        mCurrentUserLocation = LatLng(userLocation.latitude, userLocation.longitude)
        if (currentLocationCircle != null) circleCollection?.remove(currentLocationCircle)
        if (currentLocationMarker != null) markerCollection?.remove(currentLocationMarker)

        addMarkerOnCurrentPosition()
        addCircleAroundCurrentPosition()
        animateToMeters()
    }

    private fun isForegroundLocationPermissionGranted() = ActivityCompat.checkSelfPermission(requireActivity(),
        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isGooglePlayServicesAvailable(): Boolean {
        val googleApiAvailability: GoogleApiAvailability = GoogleApiAvailability.getInstance()
        val status: Int = googleApiAvailability.isGooglePlayServicesAvailable(requireActivity())
        if (ConnectionResult.SUCCESS === status) return true else {
            if (googleApiAvailability.isUserResolvableError(status))
                Toast.makeText(requireActivity(), R.string.common_google_play_services_install_title, Toast.LENGTH_LONG).show()
        }
        return false
    }

    private fun createAppSettingsIntent() = Intent().apply {
        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        data = Uri.fromParts("package", requireContext().packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }

    private fun getBitmapFromDrawable(drawableId: Int): Bitmap {
        var drawable = AppCompatResources.getDrawable(requireActivity(), drawableId)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            drawable = (DrawableCompat.wrap(drawable!!)).mutate()
        }
        val bitmap = Bitmap.createBitmap(drawable!!.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun milesToMeters(miles: Double): Double {
        return miles * 1609.344
    }

    private fun calculateBounds(center: LatLng, radius: Double): LatLngBounds {
        return LatLngBounds.builder()
            .include(SphericalUtil.computeOffset(center, radius, 0.0))
            .include(SphericalUtil.computeOffset(center, radius, 90.0))
            .include(SphericalUtil.computeOffset(center, radius, 180.0))
            .include(SphericalUtil.computeOffset(center, radius, 270.0)).build()
    }

    private fun animateToMeters() {
        val heightInPixel = binding.exploreMap.height
        val latLngBounds = calculateBounds(mCurrentUserLocation, milesToMeters(50.0))
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(
                latLngBounds,
                heightInPixel,
                heightInPixel,
                dpToPx(5)
            )
        )
    }

    private fun dpToPx(dp: Int): Int {
        val displayMetrics = resources.displayMetrics
        return dp * (displayMetrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT)
    }
}
