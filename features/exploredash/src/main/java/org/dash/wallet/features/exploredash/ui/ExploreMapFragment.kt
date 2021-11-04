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

import android.Manifest
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.app.ActivityCompat
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
import com.google.maps.android.SphericalUtil
import com.google.maps.android.collections.CircleManager
import com.google.maps.android.collections.MarkerManager
import com.google.maps.android.ktx.awaitMap
import kotlinx.coroutines.*
import org.dash.wallet.common.ui.DialogBuilder
import org.dash.wallet.features.exploredash.R
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.SearchResult

@FlowPreview
@ExperimentalCoroutinesApi
class ExploreMapFragment: SupportMapFragment() {
    companion object {
        private const val CURRENT_POSITION_MARKER_TAG = 0
    }

    data class MerchantMarkerUI(
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        markersGlideRequestManager = Glide.with(this)

        lifecycleScope.launchWhenStarted {
            googleMap = awaitMap()
            checkPermissionOrShowMap()
            setUserLocationMarkerMovementState()
        }

        viewModel.searchResults.observe(viewLifecycleOwner) { results ->
            resetMap()
            // TODO: For the 1st iteration of this feature, we shall limit the number of markers to be displayed
            if (results.isNotEmpty()){
                if (results.size < 20){
                    setMarkers(results)
                } else setMarkers(results.shuffled().subList(0, 20))
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

    private fun isGooglePlayServicesAvailable(): Boolean {
        val googleApiAvailability: GoogleApiAvailability = GoogleApiAvailability.getInstance()
        val status: Int = googleApiAvailability.isGooglePlayServicesAvailable(requireActivity())
        if (ConnectionResult.SUCCESS == status) return true else {
            if (googleApiAvailability.isUserResolvableError(status))
                Toast.makeText(requireActivity(), R.string.common_google_play_services_install_title, Toast.LENGTH_LONG).show()
        }
        return false
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

    private fun handleClickOnMerchantMarker(results: List<SearchResult>) {
        markerCollection?.setOnMarkerClickListener { marker ->
            if (marker.tag == CURRENT_POSITION_MARKER_TAG) {
                false
            } else {
                // TODO (ahikhmin): this can be moved to the viewModel.
                // Also, it might be better to set Id or Tag of the marker to the Id
                // of the merchant/atm and use it for search instead of comparing lat/lng
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

    private fun showLocationOnMap(userLocation: UserLocation) {
//        binding.searchTitle.text = userLocation.name TODO
        mCurrentUserLocation = LatLng(userLocation.latitude, userLocation.longitude)
        currentAccuracy = userLocation.accuracy
        Log.e(this::class.java.simpleName, "Lat: ${mCurrentUserLocation.latitude}, Lng: ${mCurrentUserLocation.longitude}")
        if (currentLocationCircle != null) circleCollection?.remove(currentLocationCircle)
        if (currentLocationMarker != null) markerCollection?.remove(currentLocationMarker)

        addMarkerOnCurrentPosition()
        addCircleAroundCurrentPosition()
        setMapDefaultViewLevel()
    }

    private fun setMapDefaultViewLevel() {
        val heightInPixel = this.requireView().measuredHeight
        val latLngBounds = calculateBounds(mCurrentUserLocation, milesToMeters(50.0))
        val mapPadding = resources.getDimensionPixelOffset(R.dimen.map_padding)
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, heightInPixel, heightInPixel, mapPadding))
    }

    private fun calculateBounds(center: LatLng, radius: Double): LatLngBounds {
        return LatLngBounds.builder()
            .include(SphericalUtil.computeOffset(center, radius, 0.0))
            .include(SphericalUtil.computeOffset(center, radius, 90.0))
            .include(SphericalUtil.computeOffset(center, radius, 180.0))
            .include(SphericalUtil.computeOffset(center, radius, 270.0)).build()
    }

    private fun milesToMeters(miles: Double): Double {
        return miles * 1609.344
    }

    private suspend fun loadMerchantMarkers(merchants: List<Merchant>) {
        val markerSize = resources.getDimensionPixelSize(R.dimen.explore_marker_size)
        val merchantMarkerSize = resources.getDimensionPixelSize(R.dimen.merchant_marker_size)

        futureTarget = merchants.map {
            markersGlideRequestManager
                .asBitmap()
                .load(it.logoLocation)
                .placeholder(R.drawable.ic_merchant)
                .error(R.drawable.ic_merchant)  // TODO (ashikhmin): do we need this here given that placeholder is set below? It's a bit confusing
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

        val merchantMarkers = merchants.zip(futureTarget).map { pair ->
            pair.first.latitude?.let {
                pair.first.longitude?.let { it1 ->
                    MerchantMarkerUI(
                        it, it1,
                        try {
                            pair.second.get()
                        } catch (e: Exception) {
                            markersGlideRequestManager.asBitmap().load(R.drawable.ic_merchant)
                                .submit(merchantMarkerSize, merchantMarkerSize).get()
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

    private suspend fun renderMerchantsOnMap(results: List<SearchResult>) {
        withContext(Dispatchers.IO){
            val merchants = results.filterIsInstance<Merchant>()
            Log.e(this@ExploreMapFragment::class.java.simpleName, "Merchant size: ${merchants.size}")
            val chunkResult = merchants.chunked(10)
            Log.e(this@ExploreMapFragment::class.java.simpleName, "Chunk size: ${chunkResult.size}")

            chunkResult.forEach {
                loadMerchantMarkers(it)
            }
        }
    }

    private fun isForegroundLocationPermissionGranted() = ActivityCompat.checkSelfPermission(requireActivity(),
        Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun setMarkers(items: List<SearchResult>) {
        viewLifecycleOwner.lifecycleScope.launch {
            renderMerchantsOnMap(items)
        }
        handleClickOnMerchantMarker(items)
    }

    private fun getBitmapFromDrawable(drawableId: Int): Bitmap? {
        val drawable = AppCompatResources.getDrawable(requireActivity(), drawableId)
        val bitmap = drawable?.let { Bitmap.createBitmap(it.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888) }
        val canvas = bitmap?.let { Canvas(it) }
        canvas?.width?.let { drawable.setBounds(0, 0, it, canvas.height) }
        canvas?.let { drawable.draw(it) }
        return bitmap
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

    private fun createAppSettingsIntent() = Intent().apply {
        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        data = Uri.fromParts("package", requireContext().packageName, null)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
}