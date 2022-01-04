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

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.FutureTarget
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.maps.*
import com.google.android.gms.maps.model.*
import com.google.maps.android.collections.MarkerManager
import com.google.maps.android.ktx.awaitMap
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.MerchantType
import org.dash.wallet.features.exploredash.data.model.SearchResult
import org.dash.wallet.features.exploredash.data.model.GeoBounds
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.services.UserLocationStateInt
import javax.inject.Inject

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class ExploreMapFragment : SupportMapFragment() {
    companion object {
        const val DETAILS_ZOOM_LEVEL = 14f
    }

    private val viewModel: ExploreViewModel by activityViewModels()
    private var savedSearchResultsBounds: LatLngBounds? = null
    private var savedMerchantLocationsBounds: LatLngBounds? = null
    private var prevScreenState: ScreenState = ScreenState.SearchResults

    private var googleMap: GoogleMap? = null
    private lateinit var mCurrentUserLocation: LatLng
    private var currentAccuracy = 0.0
    private var lastFocusedUserLocation: LatLng? = null

    private var currentLocationMarker: Marker? = null
    private var currentLocationCircle: Circle? = null

    private var currentMapItems: List<SearchResult> = listOf()
    private var markerCollection: MarkerManager.Collection? = null
    private var selectedMarker: Marker? = null

    private var allIconsRequests = listOf<FutureTarget<Bitmap>>()
    private var selectedIconRequest: FutureTarget<Bitmap>? = null
    private lateinit var markersGlideRequestManager: RequestManager
    private var markerDrawable: Bitmap? = null

    @Inject
    lateinit var userLocationState: UserLocationStateInt

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        markersGlideRequestManager = Glide.with(this)
        markerDrawable = getBitmapFromDrawable(R.drawable.ic_merchant)

        lifecycleScope.launchWhenStarted {
            googleMap = awaitMap()
            showMap()
            googleMap?.let { map ->
                map.setOnCameraIdleListener {
                    val bounds = map.projection.visibleRegion.latLngBounds
                    viewModel.searchBounds = GeoBounds(
                        bounds.northeast.latitude,
                        bounds.northeast.longitude,
                        bounds.southwest.latitude,
                        bounds.southwest.longitude,
                        bounds.center.latitude,
                        bounds.center.longitude,
                        map.cameraPosition.zoom
                    )
                }
            }
        }

        viewModel.currentUserLocation.observe(viewLifecycleOwner) { location ->
            location?.let {
                mCurrentUserLocation = LatLng(location.latitude, location.longitude)
                currentAccuracy = location.accuracy
                showLocationOnMap()
            }
        }

        viewModel.physicalSearchResults.observe(viewLifecycleOwner) { results ->
            if (viewModel.screenState.value == ScreenState.SearchResults) {
                setResults(results)
            }
        }

        viewModel.allMerchantLocations.observe(viewLifecycleOwner) { locations ->
            if (locations.isNotEmpty() && viewModel.screenState.value == ScreenState.MerchantLocations) {
                setResults(locations)
            }
        }

        viewModel.screenState.observe(viewLifecycleOwner) { state ->
            selectedMarker?.remove()
            markersGlideRequestManager.clear(selectedIconRequest)

            if (state == ScreenState.Details || state == ScreenState.DetailsGrouped) {
                showSelectedMarker(state)
            } else if (viewModel.isLocationEnabled.value == true &&
                (state == ScreenState.MerchantLocations || viewModel.filterMode.value != FilterMode.Online)
            ) {
                showMarkerSet(state)
            }

            prevScreenState = state
        }

        viewModel.recenterMapCallback.observe(viewLifecycleOwner) {
            if (::mCurrentUserLocation.isInitialized) {
                setMapDefaultViewLevel(viewModel.radius)
            }
        }

        viewModel.selectedRadiusOption.observe(viewLifecycleOwner) {
            googleMap?.let { map ->
                val mapCenter = map.projection.visibleRegion.latLngBounds.center
                val radiusBounds = getRadiusBounds(mapCenter, viewModel.radius)
                map.animateCamera(radiusBounds)
            }
        }
    }

    private fun showSelectedMarker(state: ScreenState) {
        googleMap?.let { map ->
            val item = viewModel.selectedItem.value

            if (item != null && canFocusOnItem(item)) {
                val boundsToSave = map.projection.visibleRegion.latLngBounds

                if (prevScreenState == ScreenState.SearchResults) {
                    savedSearchResultsBounds = boundsToSave
                } else if (state == ScreenState.Details && prevScreenState == ScreenState.MerchantLocations) {
                    savedMerchantLocationsBounds = boundsToSave
                }

                if (item is Merchant && item.physicalAmount > 0) {
                    setResults(viewModel.physicalSearchResults.value, savedSearchResultsBounds)
                }

                if (markerCollection?.markers?.firstOrNull { it.tag == item.id } == null) {
                    val markerSize = resources.getDimensionPixelSize(R.dimen.explore_marker_size)
                    selectedIconRequest = buildMarkerRequest(item, true).submit(markerSize, markerSize)
                }

                val itemLatLng = LatLng(item.latitude!!, item.longitude!!)
                val zoom = if (map.cameraPosition.zoom > DETAILS_ZOOM_LEVEL) map.cameraPosition.zoom else DETAILS_ZOOM_LEVEL
                val position = CameraPosition(itemLatLng, zoom, 0f, 0f)
                map.animateCamera(CameraUpdateFactory.newCameraPosition(position))
            }
        }
    }

    private fun showMarkerSet(state: ScreenState) {
        googleMap?.let { map ->
            if (state == ScreenState.MerchantLocations) {
                val prevBounds = savedMerchantLocationsBounds

                if (prevScreenState == ScreenState.Details && prevBounds != null) {
                    savedMerchantLocationsBounds = null
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(prevBounds, 0))
                } else if (prevScreenState == ScreenState.DetailsGrouped ||
                           prevScreenState == ScreenState.SearchResults
                ) {
                    val mapCenter = map.projection.visibleRegion.latLngBounds.center
                    val radiusBounds = getRadiusBounds(mapCenter, viewModel.radius)
                    map.animateCamera(radiusBounds)
                }
            } else if (state == ScreenState.SearchResults) {
                val prevBounds = savedSearchResultsBounds

                if (prevBounds != null) {
                    savedSearchResultsBounds = null
                    savedMerchantLocationsBounds = null
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(prevBounds, 0))
                } else {
                    val physicalPoints = viewModel.physicalSearchResults.value

                    if (physicalPoints != null) {
                        setResults(physicalPoints)
                    }
                }
            }
        }
    }

    private fun showMap() {
        if (isGooglePlayServicesAvailable()) {
            markerCollection = MarkerManager(googleMap).newCollection()
            markerCollection?.setOnMarkerClickListener { marker ->
                viewModel.onMapMarkerSelected(marker.tag as Int)
                true
            }

            if (::mCurrentUserLocation.isInitialized) {
                showLocationOnMap()
            }
        }
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        val googleApiAvailability: GoogleApiAvailability = GoogleApiAvailability.getInstance()
        val status: Int = googleApiAvailability.isGooglePlayServicesAvailable(requireActivity())
        if (ConnectionResult.SUCCESS == status) return true else {
            if (googleApiAvailability.isUserResolvableError(status))
                Toast.makeText(
                    requireActivity(),
                    R.string.common_google_play_services_install_title,
                    Toast.LENGTH_LONG
                ).show()
        }
        return false
    }

    private fun setResults(
        results: List<SearchResult>?,
        inBounds: LatLngBounds? = null
    ) {
        googleMap?.let { map ->
            if (results.isNullOrEmpty()) {
                allIconsRequests.forEach { markersGlideRequestManager.clear(it) }
                markerCollection?.clear()
                currentMapItems = listOf()
            } else {
                val bounds = inBounds ?: map.projection.visibleRegion.latLngBounds
                val sortedMax = results.sortedBy {
                    userLocationState.distanceBetween(
                        bounds.center.latitude, bounds.center.longitude,
                        it.latitude ?: 0.0, it.longitude ?: 0.0
                    )
                }.take(ExploreViewModel.MAX_MARKERS)
                setMarkers(sortedMax)
                checkCameraFocus(sortedMax)
            }
        }
    }

    private fun checkCameraFocus(items: List<SearchResult>?) {
        if (items.isNullOrEmpty() ||
            viewModel.filterMode.value == FilterMode.Online ||
            viewModel.isLocationEnabled.value != true
        ) {
            return
        }

        googleMap?.let { map ->
            val mapBounds = map.projection.visibleRegion.latLngBounds
            val markers = items
                .filterNot { it.latitude == null || it.longitude == null }
                .map { LatLng(it.latitude!!, it.longitude!!) }

            if (map.cameraPosition.zoom < ExploreViewModel.MIN_ZOOM_LEVEL ||
                markers.all { !mapBounds.contains(it) }) {
                // Focus on results if camera is too far or nothing on the screen
                focusCamera(markers)
            }
        }
    }

    private fun addCircleAroundCurrentPosition() {
        currentLocationCircle = googleMap?.addCircle(CircleOptions().apply {
            center(mCurrentUserLocation)
            radius(currentAccuracy)
            fillColor(resources.getColor(R.color.bg_accuracy_circle, null))
            strokeColor(Color.TRANSPARENT)
        })
    }

    private fun addMarkerOnCurrentPosition() {
        val bitmap = getBitmapFromDrawable(R.drawable.user_location_map_marker)
        currentLocationMarker = googleMap?.addMarker(MarkerOptions().apply {
            position(mCurrentUserLocation)
            anchor(0.5f, 0.5f)
            if (bitmap != null) {
                icon(BitmapDescriptorFactory.fromBitmap(bitmap))
            }
            draggable(true)
            zIndex(5f)
        })
    }

    private fun showLocationOnMap() {
        if (currentLocationCircle == null) {
            addCircleAroundCurrentPosition()
        }

        if (currentLocationMarker == null) {
            addMarkerOnCurrentPosition()
        }

        currentLocationMarker?.position = mCurrentUserLocation
        currentLocationCircle?.center = mCurrentUserLocation
        currentLocationCircle?.radius = currentAccuracy

        val userLat = mCurrentUserLocation.latitude
        val userLng = mCurrentUserLocation.longitude
        val lastLat = lastFocusedUserLocation?.latitude
        val lastLng = lastFocusedUserLocation?.longitude
        val radius = viewModel.radius

        if (lastFocusedUserLocation == null ||
            userLocationState.distanceBetween(
                userLat, userLng, lastLat ?: 0.0, lastLng ?: 0.0
            ) > radius / 2
        ) {
            setMapDefaultViewLevel(radius)
        }
    }

    private fun setMapDefaultViewLevel(radius: Double) {
        googleMap?.let { map ->
            val radiusBounds = getRadiusBounds(mCurrentUserLocation, radius)
            map.moveCamera(radiusBounds)
            lastFocusedUserLocation = mCurrentUserLocation
        }
    }

    private fun getRadiusBounds(center: LatLng, radius: Double): CameraUpdate {
        val heightInPixel = this.requireView().measuredHeight
        val latLngBounds = userLocationState.calculateBounds(center, radius)
        val mapPadding = resources.getDimensionPixelOffset(R.dimen.map_padding)
        return CameraUpdateFactory.newLatLngBounds(
            latLngBounds,
            heightInPixel,
            heightInPixel,
            mapPadding
        )
    }

    private fun loadMarkers(items: List<SearchResult>) {
        val markerSize = resources.getDimensionPixelSize(R.dimen.explore_marker_size)
        allIconsRequests.forEach { markersGlideRequestManager.clear(it) }
        allIconsRequests = items.map { item ->
            buildMarkerRequest(item, false).submit(markerSize, markerSize)
        }
    }

    private fun buildMarkerRequest(item: SearchResult, isSelected: Boolean): RequestBuilder<Bitmap> {
        return markersGlideRequestManager
            .asBitmap()
            .load(item.logoLocation)
            .error(R.drawable.ic_merchant)
            .apply(RequestOptions().centerCrop().circleCrop())
            .listener(object : RequestListener<Bitmap> {
                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>?, isFirstResource: Boolean): Boolean {
                    if (item.latitude != null && item.longitude != null && markerDrawable != null) {
                        lifecycleScope.launch {
                            addMarkerItemToMap(isSelected, item.latitude!!, item.longitude!!, markerDrawable!!, item.id)
                        }
                    }
                    return false
                }

                override fun onResourceReady(resource: Bitmap?, model: Any?, target: Target<Bitmap>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                    if (item.latitude != null && item.longitude != null && resource != null) {
                        lifecycleScope.launch {
                            addMarkerItemToMap(isSelected, item.latitude!!, item.longitude!!, resource, item.id)
                        }
                    }
                    return false
                }
            })
    }

    private fun removeOldMarkers(newItems: List<SearchResult>) {
        val newMarkerIds = newItems.map { it.id }.toSet()
        markerCollection?.markers?.toTypedArray()?.forEach { marker ->
            if (marker.tag !in newMarkerIds) {
                markerCollection?.remove(marker)
            }
        }
    }

    private fun addMarkerItemToMap(
        isSelected: Boolean,
        latitude: Double,
        longitude: Double,
        bitmap: Bitmap,
        itemId: Int
    ) {
        val options = MarkerOptions().apply {
            position(LatLng(latitude, longitude))
            anchor(0.5f, 0.5f)
            icon(BitmapDescriptorFactory.fromBitmap(bitmap))
            draggable(false)
        }

        if (isSelected) {
            googleMap?.run {
                selectedMarker = addMarker(options)?.apply { tag = itemId }
            }
        } else {
            markerCollection?.addMarker(options)?.apply {
                tag = itemId
            }
        }
    }

    private fun setMarkers(newItems: List<SearchResult>) {
        val currentIds = currentMapItems.map { it.id }.toSet()
        val toAdd = newItems.filterNot { it.id in currentIds }
        loadMarkers(toAdd)
        removeOldMarkers(newItems)
        currentMapItems = newItems
    }

    private fun focusCamera(markers: List<LatLng>) {
        val padding = resources.getDimensionPixelOffset(R.dimen.markers_offset)
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels *
                (1 - ResourcesCompat.getFloat(resources, R.dimen.merchant_half_expanded_ratio))

        val cameraUpdate = if (markers.size == 1) {
            val marker = markers.first()
            val position = CameraPosition(LatLng(marker.latitude, marker.longitude), 16f, 0f, 0f)
            CameraUpdateFactory.newCameraPosition(position)
        } else {
            val boundsBuilder = LatLngBounds.builder()
            markers.forEach { boundsBuilder.include(LatLng(it.latitude, it.longitude)) }
            CameraUpdateFactory.newLatLngBounds(
                boundsBuilder.build(),
                width,
                height.toInt(),
                padding
            )
        }

        googleMap?.animateCamera(cameraUpdate)
    }

    private fun canFocusOnItem(item: SearchResult): Boolean =
        item.type != MerchantType.ONLINE &&
                item.latitude != null && item.longitude != null

    private fun getBitmapFromDrawable(drawableId: Int): Bitmap? {
        val drawable = AppCompatResources.getDrawable(requireActivity(), drawableId)
        val bitmap = drawable?.let {
            Bitmap.createBitmap(
                it.intrinsicWidth,
                drawable.intrinsicHeight,
                Bitmap.Config.ARGB_8888
            )
        }
        val canvas = bitmap?.let { Canvas(it) }
        canvas?.width?.let { drawable.setBounds(0, 0, it, canvas.height) }
        canvas?.let { drawable.draw(it) }
        return bitmap
    }
}