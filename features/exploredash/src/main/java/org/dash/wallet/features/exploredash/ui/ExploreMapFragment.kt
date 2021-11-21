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
import com.google.maps.android.collections.MarkerManager
import com.google.maps.android.ktx.awaitMap
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.MerchantType
import org.dash.wallet.features.exploredash.data.model.SearchResult
import org.dash.wallet.features.exploredash.data.model.GeoBounds
import org.dash.wallet.features.exploredash.services.UserLocationStateInt
import javax.inject.Inject

@FlowPreview
@ExperimentalCoroutinesApi
@AndroidEntryPoint
class ExploreMapFragment: SupportMapFragment() {
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

    private var currentMapItems: List<SearchResult> = listOf()
    private var markerCollection: MarkerManager.Collection? = null

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

        viewModel.physicalSearchResults.observe(viewLifecycleOwner) { results ->
            googleMap?.let { map ->
                if (results.isEmpty()) {
                    futureTarget.forEach { markersGlideRequestManager.clear(it) }
                    markerCollection?.clear()
                } else {
                    val center = map.projection.visibleRegion.latLngBounds.center
                    val sortedMax = results.sortedBy {
                        userLocationState.distanceBetween(
                                center.latitude, center.longitude,
                                it.latitude ?: 0.0, it.longitude ?: 0.0
                        )
                    }.take(ExploreViewModel.MAX_MARKERS)
                    setMarkers(sortedMax)
                }
            }
        }

        viewModel.selectedItem.observe(viewLifecycleOwner) { item ->
            if (viewModel.filterMode.value != FilterMode.Online &&
                item?.type != MerchantType.ONLINE &&
                item?.latitude != null && item.longitude != null) {
                // TODO: might be good to move back to the previous bounds on back navigation
                val position = CameraPosition(LatLng(item.latitude!!, item.longitude!!), 16f, 0f, 0f)
                googleMap?.animateCamera(CameraUpdateFactory.newCameraPosition(position))
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
                Toast.makeText(requireActivity(), R.string.common_google_play_services_install_title, Toast.LENGTH_LONG).show()
        }
        return false
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
        Log.e(this::class.java.simpleName, "Lat: ${mCurrentUserLocation.latitude}, Lng: ${mCurrentUserLocation.longitude}")

        if (currentLocationCircle == null) {
            addCircleAroundCurrentPosition()
        }

        if (currentLocationMarker == null) {
            addMarkerOnCurrentPosition()
        }

        currentLocationMarker?.position = mCurrentUserLocation
        currentLocationCircle?.center = mCurrentUserLocation
        currentLocationCircle?.radius = currentAccuracy

        setMapDefaultViewLevel(viewModel.radius)
    }

    private fun setMapDefaultViewLevel(radius: Double) {
        val heightInPixel = this.requireView().measuredHeight
        val latLngBounds = userLocationState.calculateBounds(mCurrentUserLocation, radius)
        val mapPadding = resources.getDimensionPixelOffset(R.dimen.map_padding)
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, heightInPixel, heightInPixel, mapPadding))
    }

    private suspend fun loadMarkers(items: Collection<SearchResult>): List<ExploreMarkerItemUI> {
        return withContext(Dispatchers.IO) {
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
                                Log.i("GlideException","${e?.message}")
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
            exploreMarkers.filterNotNull()
        }
    }

    private fun addMarkerItemToMap(latitude: Double, longitude: Double, bitmap: Bitmap, itemId: Int) {
        markerCollection?.addMarker(MarkerOptions().apply {
            position(LatLng(latitude, longitude))
            anchor(0.5f, 0.5f)
            icon(BitmapDescriptorFactory.fromBitmap(bitmap))
            draggable(false)
            snippet(itemId.toString())
        })?.apply {
            tag = itemId
            showInfoWindow()
        }
    }

    private fun setMarkers(newItems: List<SearchResult>) {
        val currentIds = currentMapItems.map { it.id }.toSet()
        val toAdd = newItems.filterNot { it.id in currentIds }

        viewLifecycleOwner.lifecycleScope.launch {
            val exploreMarkers = loadMarkers(toAdd)
            exploreMarkers.forEach {
                addMarkerItemToMap(it.latitude, it.longitude, it.logoUrl, it.id)
            }
        }

        val allMarkerIds = newItems.map { it.id }.toSet()
        markerCollection?.markers?.let { markers ->
            markers.toTypedArray().forEach { marker ->
                if (marker.tag !in allMarkerIds) {
                    markerCollection?.remove(marker)
                }
            }
        }

        currentMapItems = newItems
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