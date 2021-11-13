/*
 *
 *  * Copyright 2021 Dash Core Group
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package org.dash.wallet.features.exploredash.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
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
import com.google.maps.android.collections.CircleManager
import com.google.maps.android.collections.MarkerManager
import com.google.maps.android.ktx.awaitMap
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.Atm
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.SearchResult
import org.dash.wallet.features.exploredash.services.GeoBounds
import org.dash.wallet.features.exploredash.services.UserLocationStateInt
import javax.inject.Inject

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class ExploreMapFragment: SupportMapFragment() {
    companion object {
        private const val CURRENT_POSITION_MARKER_TAG = 0
    }

    data class ExploreMarkerItemUI(
        val id: Int,
        val latitude: Double,
        val longitude: Double,
        val logoUrl: Bitmap
    )

    private val viewModel: ExploreViewModel by activityViewModels()

    private var googleMap: GoogleMap? = null
    private lateinit var mCurrentUserLocation: LatLng
    private var currentAccuracy = 0.0
    private var currentLocationMarker: Marker? = null
    private var currentLocationCircle: Circle? = null

    private var markerManager: MarkerManager? = null
    private var markerCollection: MarkerManager.Collection? = null
    private var circleManager: CircleManager? = null
    private var circleCollection: CircleManager.Collection? = null
    private var futureTarget =  mutableListOf<FutureTarget<Bitmap>>()
    private lateinit var markersGlideRequestManager: RequestManager
    @Inject lateinit var userLocationState: UserLocationStateInt

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        markersGlideRequestManager = Glide.with(this)

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

        viewModel.searchResults.observe(viewLifecycleOwner) { results ->
            // TODO: we probably shouldn't reset all markers but calculate the difference instead,
            // otherwise when user pans the map, everything blinks
            resetMap()

            if (results.isNotEmpty()) {
                Log.i("EXPLOREDASH", "markers: " + ExploreViewModel.MAX_MARKERS)
                if (results.size < ExploreViewModel.MAX_MARKERS) {
                    setMarkers(results)
                } else setMarkers(results.shuffled().take(ExploreViewModel.MAX_MARKERS))
            }
        }

        viewModel.selectedItem.observe(viewLifecycleOwner) { item ->
            if (item?.latitude != null && item.longitude != null) {
                // TODO: might be good to move back to the previous bounds on back navigation
                val position = CameraPosition(LatLng(item.latitude!!, item.longitude!!), 16f, 0f, 0f)
                googleMap?.animateCamera(CameraUpdateFactory.newCameraPosition(position))
            }
        }
    }

    private fun showMap() {
        if (isGooglePlayServicesAvailable()) {
            markerManager = MarkerManager(googleMap)
            markerCollection = markerManager?.newCollection()
            circleManager = googleMap?.let { CircleManager(it) }
            circleCollection = circleManager?.newCollection()

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
                Toast.makeText(requireActivity(), R.string.common_google_play_services_install_title, Toast.LENGTH_LONG).show()
        }
        return false
    }

    private fun handleClickOnMarkerItem(results: List<SearchResult>) {
        markerCollection?.setOnMarkerClickListener { marker ->
            if (marker.tag == CURRENT_POSITION_MARKER_TAG) {
                false
            } else {
                // TODO: this can be moved to the viewModel, which will allow to write a test for it.
                val item = results.firstOrNull { it.id == marker.tag }
                if (item != null) {
                    if (results.any { it is Merchant }) {
                        viewModel.openMerchantDetails(item as Merchant)
                    } else {
                        viewModel.openAtmDetails(item as Atm)
                    }
                }
                true
            }
        }
    }

    private fun addCircleAroundCurrentPosition() {
        currentLocationCircle = circleCollection?.addCircle(CircleOptions().apply {
            center(mCurrentUserLocation)
            radius(currentAccuracy)
            fillColor(resources.getColor(R.color.bg_accuracy_circle, null))
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
            zIndex(5f)
        }).apply {
            this?.tag = CURRENT_POSITION_MARKER_TAG
        }
    }

    private fun showLocationOnMap() {
        Log.e(this::class.java.simpleName, "Lat: ${mCurrentUserLocation.latitude}, Lng: ${mCurrentUserLocation.longitude}")
        if (currentLocationCircle != null) circleCollection?.remove(currentLocationCircle)
        if (currentLocationMarker != null) markerCollection?.remove(currentLocationMarker)

        addMarkerOnCurrentPosition()
        addCircleAroundCurrentPosition()
        setMapDefaultViewLevel(viewModel.radius)
    }

    private fun setMapDefaultViewLevel(radius: Double) {
        val heightInPixel = this.requireView().measuredHeight
        val latLngBounds = userLocationState.calculateBounds(mCurrentUserLocation, radius)
        val mapPadding = resources.getDimensionPixelOffset(R.dimen.map_padding)
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, heightInPixel, heightInPixel, mapPadding))
    }

    private suspend fun loadMarkers(items: List<SearchResult>) {
        val markerSize = resources.getDimensionPixelSize(R.dimen.explore_marker_size)
        val exploreMarkerSize = resources.getDimensionPixelSize(R.dimen.merchant_marker_size)

        futureTarget = items.map {
            markersGlideRequestManager
                .asBitmap()
                .load(it.logoLocation)
                .placeholder(R.drawable.ic_merchant)
                .apply(RequestOptions().centerCrop().circleCrop())
                .listener(object : RequestListener<Bitmap> {
                    override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Bitmap>?, isFirstResource: Boolean): Boolean {
                        Log.i("GlideException" ,"${e?.message}")
                        return false
                    }

                    override fun onResourceReady(resource: Bitmap?, model: Any?, target: Target<Bitmap>?, dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                        Log.i(this@ExploreMapFragment::class.java.simpleName, "Resource loaded")
                        return false
                    }
                })
                .submit(markerSize, markerSize)
        }.toMutableList()

        val exploreMarkers = items.zip(futureTarget).map { pair ->
            pair.first.latitude?.let {
                pair.first.longitude?.let { it1 ->
                    ExploreMarkerItemUI(
                        pair.first.id,
                        it, it1,
                        try {
                            pair.second.get()
                        } catch (e: Exception) {
                            markersGlideRequestManager.asBitmap().load(R.drawable.ic_merchant)
                                .submit(exploreMarkerSize, exploreMarkerSize).get()
                        }
                    )
                }
            }
        }

        futureTarget.forEach { markersGlideRequestManager.clear(it) }

        withContext(Dispatchers.Main){
            exploreMarkers.forEach{
                it?.let { it1 -> addMarkerItemToMap(it1.latitude, it.longitude, it.logoUrl, it.id) }
            }
        }
    }

    private fun addMarkerItemToMap(latitude: Double, longitude: Double, bitmap: Bitmap, itemId: Int) {
        markerCollection?.addMarker(MarkerOptions().apply {
            position(LatLng(latitude, longitude))
            anchor(0.5f, 0.5f)
            icon(BitmapDescriptorFactory.fromBitmap(bitmap))
            draggable(false)
        }).apply {
            this?.tag = itemId
        }
    }

    private fun resetMap() {
        futureTarget.forEach { markersGlideRequestManager.clear(it) }
        markerCollection?.clear()
        circleCollection?.clear()
        googleMap?.clear()

        if (::mCurrentUserLocation.isInitialized) {
            addMarkerOnCurrentPosition()
            addCircleAroundCurrentPosition()
        }
    }

    private suspend fun renderItemsOnMap(results: List<SearchResult>) {
        withContext(Dispatchers.IO){
            loadMarkers(results)
        }
    }

    private fun setMarkers(items: List<SearchResult>) {
        viewLifecycleOwner.lifecycleScope.launch {
            renderItemsOnMap(items)
        }
        handleClickOnMarkerItem(items)
    }

    private fun getBitmapFromDrawable(drawableId: Int): Bitmap? {
        val drawable = AppCompatResources.getDrawable(requireActivity(), drawableId)
        val bitmap = drawable?.let { Bitmap.createBitmap(it.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888) }
        val canvas = bitmap?.let { Canvas(it) }
        canvas?.width?.let { drawable.setBounds(0, 0, it, canvas.height) }
        canvas?.let { drawable.draw(it) }
        return bitmap
    }
}