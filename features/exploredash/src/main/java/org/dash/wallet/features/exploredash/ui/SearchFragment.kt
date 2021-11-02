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
import android.animation.LayoutTransition
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
import android.transition.TransitionManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
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
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.FutureTarget
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
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
import com.google.maps.android.ktx.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.dash.wallet.common.ui.DialogBuilder
import org.dash.wallet.common.ui.ListDividerDecorator
import org.dash.wallet.common.ui.observeOnDestroy
import org.dash.wallet.common.ui.viewBinding
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.*
import org.dash.wallet.features.exploredash.databinding.FragmentSearchBinding
import org.dash.wallet.features.exploredash.ui.adapters.MerchantsAtmsResultAdapter
import org.dash.wallet.features.exploredash.ui.adapters.SearchHeaderAdapter
import org.dash.wallet.features.exploredash.ui.dialogs.TerritoryFilterDialog

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class SearchFragment : Fragment(R.layout.fragment_search) {
    companion object {
        private const val SCROLL_OFFSET_FOR_UP = 700
    }

    private val binding by viewBinding(FragmentSearchBinding::bind)
    private val viewModel: ExploreViewModel by activityViewModels()
    private val args by navArgs<SearchFragmentArgs>()

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
    private var futureTarget =  mutableListOf<FutureTarget<Bitmap>>()
    private lateinit var markersGlideRequestManager: RequestManager

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
        binding.toolbarTitle.text = getToolbarTitle()

        binding.toolbar.setOnMenuItemClickListener {
            if (it.itemId == R.id.menu_info) {
                Log.i("EXPLOREDASH", "info menu click")
            }
            true
        }

        val binding = binding // Avoids IllegalStateException in onStateChanged callback
        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.state = BottomSheetBehavior.STATE_HALF_EXPANDED
        bottomSheet.halfExpandedRatio =
            ResourcesCompat.getFloat(
                resources, if (args.type == ExploreTopic.Merchants) {
                    R.dimen.merchant_half_expanded_ratio
                } else {
                    R.dimen.atm_half_expanded_ratio
                }
            )

        val header = SearchHeaderAdapter(args.type)
        setupFilters(header, bottomSheet, args.type)
        setupSearchInput(header, bottomSheet)
        setupSearchResults(header)
        setupItemDetails()

        viewModel.init(args.type)
        markersGlideRequestManager = Glide.with(this)
        val mapFragment = childFragmentManager.findFragmentById(R.id.explore_map) as SupportMapFragment
        lifecycleScope.launchWhenStarted {
            googleMap = mapFragment.awaitMap()
            checkPermissionOrShowMap()
            setUserLocationMarkerMovementState()
        }
    }

    private fun setupFilters(
        header: SearchHeaderAdapter,
        bottomSheet: BottomSheetBehavior<ConstraintLayout>,
        topic: ExploreTopic
    ) {
        header.setOnFilterOptionChosen { _, index ->
            if (topic == ExploreTopic.Merchants) {
                viewModel.setFilterMode(
                    when (index) {
                        0 -> ExploreViewModel.FilterMode.Online
                        1 -> ExploreViewModel.FilterMode.Physical
                        else -> ExploreViewModel.FilterMode.All
                    }
                )
            } else {
                viewModel.setFilterMode(
                    when (index) {
                        1 -> ExploreViewModel.FilterMode.Buy
                        2 -> ExploreViewModel.FilterMode.Sell
                        3 -> ExploreViewModel.FilterMode.BuySell
                        else -> ExploreViewModel.FilterMode.All
                    }
                )
            }
        }

        header.setOnFilterButtonClicked {
            lifecycleScope.launch {
                val territories = viewModel.getTerritoriesWithPOIs()
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
            if (viewModel.selectedItem.value == null && it == ExploreViewModel.FilterMode.Online) {
                bottomSheet.state = BottomSheetBehavior.STATE_EXPANDED
                bottomSheetWasExpanded = true
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

        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                if (positionStart == 0 && itemCount != ExploreViewModel.PAGE_SIZE) {
                    // Scrolling on top if user changed the filter option
                    binding.searchResults.scrollToPosition(0)
                }
            }
        })

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

        viewModel.searchResults.observe(viewLifecycleOwner){ results ->
            resetMap()
            // For the 1st iteration of this feature, we shall limit the number of markers to be displayed
            if (results.isNotEmpty()){
                if (results.size < 20){
                    setMarkers(results)
                } else setMarkers(results.shuffled().subList(0, 20))
            }
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

                    transitToDetails(item.type == MerchantType.ONLINE)
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

            val addressBuilder = StringBuilder()
            addressBuilder.append(item.address1)

            if (!item.address2.isNullOrBlank()) {
                addressBuilder.append("\n${item.address2}")
            }

            if (!item.address3.isNullOrBlank()) {
                addressBuilder.append("\n${item.address3}")
            }

            if (!item.address4.isNullOrBlank()) {
                addressBuilder.append("\n${item.address4}")
            }

            itemAddress.text = addressBuilder.toString()

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

            itemType.text = when (cleanValue(merchant.type)) {
                MerchantType.ONLINE -> resources.getString(R.string.explore_online_merchant)
                MerchantType.PHYSICAL -> resources.getString(R.string.explore_physical_merchant)
                MerchantType.BOTH -> resources.getString(R.string.explore_both_types_merchant)
                else -> ""
            }

            val isOnline = merchant.type == MerchantType.ONLINE
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
        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.isDraggable = false
        bottomSheetWasExpanded = bottomSheet.state == BottomSheetBehavior.STATE_EXPANDED
        bottomSheet.state =
            if (fullHeight) BottomSheetBehavior.STATE_EXPANDED else BottomSheetBehavior.STATE_HALF_EXPANDED

        animate(binding.searchResults).apply {
            duration = 200
            alpha(0f)
        }.withEndAction {
            binding.searchResults.isVisible = false
        }.start()

        animate(binding.dragIndicator).apply {
            duration = 200
            alpha(0f)
        }.withEndAction {
            binding.dragIndicator.isVisible = false
        }.start()

        binding.itemDetails.root.alpha = 0f
        binding.itemDetails.root.isVisible = true
        animate(binding.itemDetails.root).apply {
            duration = 200
            startDelay = 200
            alpha(1f)
        }.start()
    }

    private fun transitToSearchResults() {
        binding.upButton.isVisible = shouldShowUpButton()
        val bottomSheet = BottomSheetBehavior.from(binding.contentPanel)
        bottomSheet.isDraggable = true

        bottomSheet.state = if (bottomSheetWasExpanded) {
            BottomSheetBehavior.STATE_EXPANDED
        } else {
            BottomSheetBehavior.STATE_HALF_EXPANDED
        }

        animate(binding.itemDetails.root).apply {
            duration = 200
            alpha(0f)
        }.withEndAction {
            binding.itemDetails.root.isVisible = false
        }.start()

        binding.searchResults.isVisible = true
        binding.searchResults.alpha = 0f
        animate(binding.searchResults).apply {
            duration = 200
            startDelay = 200
            alpha(1f)
        }.start()

        binding.dragIndicator.isVisible = true
        binding.dragIndicator.alpha = 0f
        animate(binding.dragIndicator).apply {
            duration = 200
            startDelay = 200
            alpha(1f)
        }.start()
    }

    private fun getToolbarTitle(): String {
        return when (viewModel.exploreTopic) {
            ExploreTopic.Merchants -> getString(R.string.explore_where_to_spend)
            else -> getString(R.string.explore_atms)
        }
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
                if (marker.tag == CURRENT_POSITION_MARKER_TAG){
                    mCurrentUserLocation = marker.position
                    currentLocationMarker?.position = marker.position
                    currentLocationCircle?.center = marker.position
                    googleMap?.moveCamera(CameraUpdateFactory.newLatLng(marker.position))
                }
            }
        })

    }

    private suspend fun renderMerchantsOnMap(results: List<SearchResult>) {
        withContext(Dispatchers.IO){
            val merchants = results.filterIsInstance<Merchant>()
            Log.e(this@SearchFragment::class.java.simpleName, "Merchant size: ${merchants.size}")
            val chunkResult = merchants.chunked(10)
            Log.e(this@SearchFragment::class.java.simpleName, "Chunk size: ${chunkResult.size}")

            chunkResult.forEach {
                loadMerchantMarkers(it)
            }
        }
    }

    private fun handleClickOnMerchantMarker(results: List<SearchResult>) {
        markerCollection?.setOnMarkerClickListener { marker ->
            if (marker.tag == CURRENT_POSITION_MARKER_TAG) {
                false
            } else {
                val atmItemCoordinates = marker.position
                val merchants = results.filterIsInstance<Merchant>()
                merchants.forEach {
                    if ((it.latitude == atmItemCoordinates.latitude) && (it.longitude == atmItemCoordinates.longitude)) {
                        viewModel.openMerchantDetails(it)
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
            if (bitmap != null) {
                icon(BitmapDescriptorFactory.fromBitmap(bitmap))
            }
            draggable(true)
        }).apply {
            this?.tag = CURRENT_POSITION_MARKER_TAG
        }
    }

    private fun showLocationOnMap(userLocation: UserLocation) {
//        binding.searchTitle.text = userLocation.name TODO
        mCurrentUserLocation = LatLng(userLocation.latitude, userLocation.longitude)
        Log.e(this::class.java.simpleName, "Lat: ${mCurrentUserLocation.latitude}, Lng: ${mCurrentUserLocation.longitude}")
        if (currentLocationCircle != null) circleCollection?.remove(currentLocationCircle)
        if (currentLocationMarker != null) markerCollection?.remove(currentLocationMarker)

        addMarkerOnCurrentPosition()
        addCircleAroundCurrentPosition()
        setMapDefaultViewLevel()
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

    private fun getBitmapFromDrawable(drawableId: Int): Bitmap? {
        var drawable = AppCompatResources.getDrawable(requireActivity(), drawableId)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            drawable = (drawable?.let { DrawableCompat.wrap(it) })?.mutate()
        }
        val bitmap = drawable?.let { Bitmap.createBitmap(it.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888) }
        val canvas = bitmap?.let { Canvas(it) }
        canvas?.width?.let { drawable?.setBounds(0, 0, it, canvas.height) }
        canvas?.let { drawable?.draw(it) }
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

    private fun setMapDefaultViewLevel() {
        val heightInPixel = binding.exploreMap.height
        val latLngBounds = calculateBounds(mCurrentUserLocation, milesToMeters(50.0))
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, heightInPixel, heightInPixel, dpToPx(5)))
    }

    private fun dpToPx(dp: Int): Int {
        val displayMetrics = resources.displayMetrics
        return dp * (displayMetrics.densityDpi / DisplayMetrics.DENSITY_DEFAULT)
    }

    private suspend fun loadMerchantMarkers(merchants: List<Merchant>) {
        futureTarget = merchants.map {
            markersGlideRequestManager
                .asBitmap()
                .load(it.logoLocation)
                .placeholder(R.drawable.merchant_marker)
                .error(R.drawable.merchant_marker)
                .apply(RequestOptions().centerCrop().circleCrop())
                .listener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>?, isFirstResource: Boolean): Boolean {
                        Log.i("GlideException" ,"${e?.message}")
                        return false
                    }

                    override fun onResourceReady(resource: Bitmap?, model: Any?, target: Target<Bitmap>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                        Log.i(this@SearchFragment::class.java.simpleName, "Resource loaded")
                        return false
                    }
                })
                .submit(72, 72)
        }.toMutableList()

        val merchantMarkers = merchants.zip(futureTarget).map { pair ->
            pair.first.latitude?.let {
                pair.first.longitude?.let { it1 ->
                    MerchantMarkerUI(it, it1,
                        try {
                            pair.second.get()
                        } catch (e: Exception){
                            markersGlideRequestManager.asBitmap().load(R.drawable.merchant_marker).submit(72, 72).get()
                        }
                    )
                }
            }
        }

        futureTarget.forEach { markersGlideRequestManager.clear(it) }

        withContext(Dispatchers.Main){
            merchantMarkers.forEach{
                it?.let { it1 -> addMerchantMarkerToMap(it1.latitude, it.longitude, it.logoUrl) }
            }
        }

    }

    private fun addMerchantMarkerToMap(latitude: Double, longitude: Double, bitmap: Bitmap) {
        markerCollection?.addMarker(MarkerOptions().apply {
                position(LatLng(latitude, longitude))
                anchor(0.5f, 0.5f)
                icon(BitmapDescriptorFactory.fromBitmap(bitmap))
                draggable(false)
            }).apply {
                this?.tag = null
        }
    }

    private fun resetMap() {
        futureTarget.forEach { markersGlideRequestManager.clear(it) }
        markerCollection?.clear()
        circleCollection?.clear()
        googleMap?.clear()
        addMarkerOnCurrentPosition()
        addCircleAroundCurrentPosition()
    }

    data class MerchantMarkerUI(
        val latitude: Double,
        val longitude: Double,
        val logoUrl: Bitmap
    )

    private fun setMarkers(items: List<SearchResult>) {
        viewLifecycleOwner.lifecycleScope.launch {
            renderMerchantsOnMap(items)
        }
        handleClickOnMerchantMarker(items)
    }

    private fun shouldShowUpButton(): Boolean {
        val offset = binding.searchResults.computeVerticalScrollOffset()
        return offset > SCROLL_OFFSET_FOR_UP
    }
}
