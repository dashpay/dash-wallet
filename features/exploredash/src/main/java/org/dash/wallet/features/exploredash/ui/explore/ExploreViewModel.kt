/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui.explore

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.*
import androidx.paging.*
import com.google.firebase.FirebaseNetworkException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.data.Status
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.data.explore.ExploreDataSource
import org.dash.wallet.features.exploredash.data.explore.model.*
import org.dash.wallet.features.exploredash.repository.DataSyncStatusService
import org.dash.wallet.features.exploredash.services.UserLocation
import org.dash.wallet.features.exploredash.services.UserLocationStateInt
import org.dash.wallet.features.exploredash.ui.explore.ExploreViewModel.Companion.DEFAULT_RADIUS_OPTION
import org.dash.wallet.features.exploredash.ui.extensions.Const
import org.dash.wallet.features.exploredash.ui.extensions.isMetric
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import java.util.*
import javax.inject.Inject
import kotlin.String
import kotlin.math.max
import kotlin.math.min

enum class ExploreTopic {
    Merchants, ATMs, Faucet
}

enum class FilterMode {
    All,
    Online,
    Nearby,
    Buy,
    Sell,
    BuySell
}

enum class ScreenState {
    SearchResults,
    MerchantLocations,
    DetailsGrouped,
    Details
}

enum class DenomOption {
    Fixed,
    Flexible,
    Both
}

data class FilterOptions(
    val query: String,
    val territory: String,
    val payment: String,
    val denominationType: DenomOption,
    val sortOption: SortOption,
    val radius: Int // Can be miles or kilometers, see isMetric
) {
    companion object {
        val DEFAULT = FilterOptions("", "", "", DenomOption.Both, SortOption.Name, DEFAULT_RADIUS_OPTION)
    }
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val exploreData: ExploreDataSource,
    private val locationProvider: UserLocationStateInt,
    private val syncStatusService: DataSyncStatusService,
    private val networkState: NetworkStateInt,
    val exploreConfig: ExploreConfig,
    private val analyticsService: AnalyticsService
) : ViewModel() {
    companion object {
        const val QUERY_DEBOUNCE_VALUE = 300L
        const val PAGE_SIZE = 100
        const val MAX_ITEMS_IN_MEMORY = 300
        const val MIN_ZOOM_LEVEL = 8f
        const val DEFAULT_RADIUS_OPTION = 20
        const val MAX_MARKERS = 100
        val DEFAULT_SORT_OPTION = SortOption.Name
    }

    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)
    var isDialogDismissedOnCancel = false
    val recenterMapCallback = SingleLiveEvent<Unit>()
    private var boundedFilterJob: Job? = null
    private var pagingFilterJob: Job? = null
    private var allMerchantLocationsJob: Job? = null
    val isMetric = Locale.getDefault().isMetric

    var exploreTopic = ExploreTopic.Merchants
        private set
    var previousCameraGeoBounds = GeoBounds.noBounds
    var previousZoomLevel: Float = -1.0f
    private var lastResolvedAddress: GeoBounds? = null
    private var _currentUserLocation: MutableStateFlow<UserLocation?> = MutableStateFlow(null)
    val currentUserLocation = _currentUserLocation.asLiveData()

    // In meters
    val radius: Double
        get() = if (isMetric) {
            _appliedFilters.value.radius * Const.METERS_IN_KILOMETER
        } else {
            _appliedFilters.value.radius * Const.METERS_IN_MILE
        }

    // Bounded only by selected radius
    private var radiusBounds: GeoBounds? = null

    // Bounded by min(screen edges, selected radius)
    private val _searchBounds = MutableStateFlow<GeoBounds?>(null)
    var searchBounds: GeoBounds?
        get() = _searchBounds.value
        set(value) {
            value?.let {
                if (_isLocationEnabled.value != true || value.zoomLevel > MIN_ZOOM_LEVEL) {
                    _searchBounds.value = ceilByRadius(value, radius)
                }
            }
        }

    private val _filterMode = MutableStateFlow(FilterMode.Online)

    // Need a mediator here because flow.asLiveData() doesn't play well with liveData.value
    var filterMode: LiveData<FilterMode> =
        MediatorLiveData<FilterMode>().apply { addSource(_filterMode.asLiveData(), this::setValue) }

    private var nearestLocation: SearchResult? = null

    private val _allMerchantLocations = MutableLiveData<List<Merchant>>()
    val allMerchantLocations: LiveData<List<Merchant>>
        get() = _allMerchantLocations

    private val _physicalSearchResults = MutableLiveData<List<SearchResult>>()
    val physicalSearchResults: LiveData<List<SearchResult>>
        get() = _physicalSearchResults

    private val _pagingSearchResults = MutableLiveData<PagingData<SearchResult>>()
    val pagingSearchResults: LiveData<PagingData<SearchResult>>
        get() = _pagingSearchResults

    private val _pagingSearchResultsCount = MutableLiveData<Int>()
    val pagingSearchResultsCount: LiveData<Int>
        get() = _pagingSearchResultsCount

    private val _searchLocationName = MutableLiveData<String>()
    val searchLocationName: LiveData<String>
        get() = _searchLocationName

    private val _selectedItem = MutableLiveData<SearchResult?>()
    val selectedItem: LiveData<SearchResult?>
        get() = _selectedItem

    private val _isLocationEnabled = MutableLiveData(false)
    val isLocationEnabled: LiveData<Boolean>
        get() = _isLocationEnabled

    private val _appliedFilters = MutableStateFlow(FilterOptions.DEFAULT)
    val appliedFilters: StateFlow<FilterOptions>
        get() = _appliedFilters

    private val _screenState = MutableLiveData(ScreenState.SearchResults)
    val screenState: LiveData<ScreenState>
        get() = _screenState

    var syncStatus: LiveData<Resource<Double>> =
        MediatorLiveData<Resource<Double>>().apply {
            // combine connectivity, sync status and if the last error was observed
            val connectivityLiveData = networkState.isConnected.asLiveData()
            val syncStatusLiveData = syncStatusService.getSyncProgressFlow().asLiveData()
            val observedLastErrorLiveData = syncStatusService.hasObservedLastError().asLiveData()

            fun setSyncStatus(isOnline: Boolean, progress: Resource<Double>, observedLastError: Boolean) {
                value = when {
                    progress.exception != null -> {
                        if (!observedLastError) {
                            progress
                        } else {
                            Resource.success(100.0) // hide errors if already observed
                        }
                    }
                    progress.status == Status.LOADING && !isOnline -> {
                        if (!observedLastError) {
                            Resource.error(FirebaseNetworkException("network is offline"))
                        } else {
                            Resource.success(100.0) // hide errors if already observed
                        }
                    }
                    else -> progress
                }
            }
            addSource(observedLastErrorLiveData) { hasObservedLastError ->
                if (connectivityLiveData.value != null && syncStatusLiveData.value != null) {
                    setSyncStatus(connectivityLiveData.value!!, syncStatusLiveData.value!!, hasObservedLastError)
                }
            }
            addSource(syncStatusLiveData) { progress ->
                if (connectivityLiveData.value != null && observedLastErrorLiveData.value != null) {
                    setSyncStatus(connectivityLiveData.value!!, progress, observedLastErrorLiveData.value!!)
                }
            }
            addSource(connectivityLiveData) { isOnline ->
                if (syncStatusLiveData.value != null && observedLastErrorLiveData.value != null) {
                    setSyncStatus(isOnline, syncStatusLiveData.value!!, observedLastErrorLiveData.value!!)
                }
            }
        }

    // Used for the list of search results
    private val pagingSearchFlow: Flow<PagingData<SearchResult>> =
        _appliedFilters.debounce(QUERY_DEBOUNCE_VALUE).flatMapLatest { filters ->
            _filterMode
                .filter { screenState.value == ScreenState.SearchResults }
                .flatMapLatest { mode ->
                    clearSearchResults()
                    _searchBounds
                        .filter { mode != FilterMode.Nearby || _isLocationEnabled.value == true }
                        .map { transformBounds(it, mode) }
                        .flatMapLatest { bounds ->
                            getPagingFlow(filters, mode, bounds)
                                .cachedIn(viewModelScope)
                        }
                }
        }

    // Used for the map
    val boundedSearchFlow: Flow<List<SearchResult>> =
        _appliedFilters.debounce(QUERY_DEBOUNCE_VALUE).flatMapLatest { filters ->
            _searchBounds
                .filterNotNull()
                .filter { screenState.value == ScreenState.SearchResults }
                .flatMapLatest { bounds ->
                    _filterMode
                        .filterNot { it == FilterMode.Online }
                        .flatMapLatest { mode -> getBoundedFlow(filters, mode, bounds) }
                }
        }

    fun init(exploreTopic: ExploreTopic) {
        if (this.exploreTopic != exploreTopic) {
            clearSearchResults()
        }

        this.exploreTopic = exploreTopic

        this.pagingFilterJob?.cancel(CancellationException())
        this.pagingFilterJob = pagingSearchFlow
            .distinctUntilChanged()
            .onEach(_pagingSearchResults::postValue)
            .onEach { countPagedResults() }
            .launchIn(viewModelWorkerScope)

        _isLocationEnabled.observeForever { locationEnabled ->
            this.boundedFilterJob?.cancel(CancellationException())

            if (locationEnabled) {
                this.boundedFilterJob = boundedSearchFlow
                    .distinctUntilChanged()
                    .onEach { list ->
                        list.forEach {
                            if (it is Merchant) {
                                it.physicalAmount = 1
                            }

                            val userLat = _currentUserLocation.value?.latitude
                            val userLng = _currentUserLocation.value?.longitude
                            it.distance = calculateDistance(it, userLat, userLng)
                        }
                        _physicalSearchResults.postValue(list)
                    }
                    .launchIn(viewModelWorkerScope)
            }
            // Right now we don't show the map at all while location is disabled
        }

        _searchBounds
            .filterNotNull()
            .filter {
                val lastResolved = lastResolvedAddress
                isLocationEnabled.value == true &&
                    it.zoomLevel > MIN_ZOOM_LEVEL && (
                    _appliedFilters.value.territory.isEmpty() == true ||
                        lastResolved == null ||
                        locationProvider.distanceBetweenCenters(lastResolved, it) > radius / 2
                    )
            }
            .onEach(::resolveAddress)
            .launchIn(viewModelWorkerScope)

        _appliedFilters
            .distinctUntilChangedBy { it.territory }
            .onEach { filter ->
                when {
                    filter.territory.isNotEmpty() -> _searchLocationName.postValue(filter.territory)
                    lastResolvedAddress != null -> resolveAddress(lastResolvedAddress!!)
                    else -> _searchLocationName.postValue("")
                }
            }
            .launchIn(viewModelWorkerScope)
    }

    fun onExitSearch() {
        this.boundedFilterJob?.cancel(CancellationException())
        this.pagingFilterJob?.cancel(CancellationException())
        clearFilters()
        resetFilterMode()
        _searchLocationName.value = ""
    }

    fun setFilterMode(mode: FilterMode) {
        logFilterChange(mode)

        if (_filterMode.value != mode) {
            _filterMode.value = mode
        }
    }

    fun submitSearchQuery(query: String) {
        _appliedFilters.update { current -> current.copy(query = query) }
    }

    fun setFilters(
        paymentFilter: String,
        selectedTerritory: String,
        selectedRadiusOption: Int,
        sortOption: SortOption,
        denomOption: DenomOption
    ) {
        _appliedFilters.update { current ->
            current.copy(
                territory = selectedTerritory,
                payment = paymentFilter,
                denominationType = denomOption,
                sortOption = sortOption,
                radius = selectedRadiusOption
            )
        }
    }

    suspend fun getTerritoriesWithPOIs(): List<String> {
        return if (exploreTopic == ExploreTopic.Merchants) {
            exploreData.getMerchantTerritories().filter { it.isNotEmpty() }
        } else {
            exploreData.getAtmTerritories().filter { it.isNotEmpty() }
        }
    }

    fun openMerchantDetails(merchant: Merchant, isGrouped: Boolean = false) {
        _selectedItem.postValue(merchant)

        if (isGrouped) {
            if (canShowNearestLocation(merchant)) {
                // Opening details screen
                nearestLocation = merchant
                _screenState.postValue(ScreenState.DetailsGrouped)
            } else {
                // Opening all merchant locations screen
                openAllMerchantLocations(merchant.merchantId!!, merchant.source!!)
            }
        } else {
            _screenState.postValue(ScreenState.Details)
        }
    }

    fun openAtmDetails(atm: Atm) {
        analyticsService.logEvent(AnalyticsConstants.Explore.SELECT_ATM_LOCATION, mapOf())
        _selectedItem.postValue(atm)
        _screenState.postValue(ScreenState.Details)
    }

    fun openSearchResults() {
        nearestLocation = null
        _selectedItem.postValue(null)
        _screenState.postValue(ScreenState.SearchResults)
        _allMerchantLocations.postValue(listOf())
        this.allMerchantLocationsJob?.cancel()
    }

    fun onMapMarkerSelected(id: Int) {
        val item = _allMerchantLocations.value?.firstOrNull { it.id == id }
            ?: _physicalSearchResults.value?.firstOrNull { it.id == id }
        item?.let {
            if (item is Merchant) {
                openMerchantDetails(item)
            } else {
                openAtmDetails(item as Atm)
            }
        }
    }

    fun openAllMerchantLocations(merchantId: String?, source: String) {
        _screenState.postValue(ScreenState.MerchantLocations)
        this.allMerchantLocationsJob?.cancel()
        this.allMerchantLocationsJob = _searchBounds
            .filterNotNull()
            .flatMapLatest { bounds ->
                val radiusBounds = if (_isLocationEnabled.value == true) {
                    locationProvider.getRadiusBounds(bounds.centerLat, bounds.centerLng, radius)
                } else {
                    GeoBounds.noBounds
                }
                val limitResults = _isLocationEnabled.value != true ||
                    _appliedFilters.value.territory.isNotEmpty() == true
                val limit = if (limitResults) 100 else -1
                exploreData.observeMerchantLocations(
                    merchantId!!,
                    source,
                    _appliedFilters.value.territory,
                    "",
                    DenomOption.Both,
                    radiusBounds,
                    limit
                )
            }
            .onEach { locations ->
                val location = currentUserLocation.value
                val sorted = if (isLocationEnabled.value == true && location != null) {
                    locations.sortedBy {
                        it.distance = calculateDistance(it, location.latitude, location.longitude)
                        it.distance
                    }
                } else {
                    locations.sortedBy { it.getDisplayAddress(", ") }
                }
                _allMerchantLocations.postValue(sorted)
            }
            .launchIn(viewModelWorkerScope)
    }

    fun canShowNearestLocation(item: SearchResult? = null): Boolean {
        val nearest = item ?: nearestLocation
        // Cannot show nearest location if there are more than 1 in group and location is disabled
        return (nearest is Merchant && nearest.physicalAmount <= 1) ||
            (isLocationEnabled.value == true && _appliedFilters.value.territory.isEmpty() == true)
    }

    fun backFromMerchantLocation() {
        if (screenState.value == ScreenState.Details) {
            _screenState.postValue(ScreenState.MerchantLocations)
        }
    }

    fun backFromAllMerchantLocations() {
        val openedLocation = nearestLocation

        if (screenState.value == ScreenState.MerchantLocations && openedLocation is Merchant) {
            openMerchantDetails(openedLocation, true)
        }
    }

    fun monitorUserLocation() {
        _isLocationEnabled.value = true

        viewModelScope.launch { locationProvider.observeUpdates().collect { _currentUserLocation.value = it } }
    }

    fun clearFilters() {
        _appliedFilters.value = FilterOptions.DEFAULT
    }

    suspend fun isInfoShown(): Boolean =
        exploreConfig.get(ExploreConfig.HAS_INFO_SCREEN_BEEN_SHOWN) ?: false

    suspend fun setIsInfoShown(isShown: Boolean) {
        exploreConfig.set(ExploreConfig.HAS_INFO_SCREEN_BEEN_SHOWN, isShown)
    }

    private fun clearSearchResults() {
        _pagingSearchResults.postValue(PagingData.from(listOf()))
        _physicalSearchResults.postValue(listOf())
    }

    private fun getBoundedFlow(
        filters: FilterOptions,
        filterMode: FilterMode,
        bounds: GeoBounds
    ): Flow<List<SearchResult>> {
        return if (exploreTopic == ExploreTopic.Merchants) {
            exploreData.observePhysicalMerchants(
                filters.query,
                filters.territory,
                filters.payment,
                filters.denominationType,
                bounds
            )
        } else {
            val types = getAtmTypes(filterMode)
            exploreData.observePhysicalAtms(filters.query, filters.territory, types, bounds)
        }
    }

    private fun getPagingFlow(
        filters: FilterOptions,
        filterMode: FilterMode,
        bounds: GeoBounds
    ): Flow<PagingData<SearchResult>> {
        val userLat = currentUserLocation.value?.latitude
        val userLng = currentUserLocation.value?.longitude
        val canSortByDistance = _filterMode.value != FilterMode.Online &&
            _isLocationEnabled.value == true &&
            userLat != null &&
            userLng != null
        val sortOption = if (filters.sortOption != SortOption.Distance) {
            filters.sortOption
        } else if (canSortByDistance) {
            SortOption.Distance
        } else {
            SortOption.Name
        }
        val onlineFirst = _isLocationEnabled.value != true

        val pagerConfig = PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false, maxSize = MAX_ITEMS_IN_MEMORY)

        @Suppress("UNCHECKED_CAST")
        return if (exploreTopic == ExploreTopic.Merchants) {
            Pager(pagerConfig) {
                val type = getMerchantType(filterMode)
                exploreData.observeMerchantsPaging(
                    filters.query,
                    filters.territory,
                    type,
                    filters.payment,
                    filters.denominationType,
                    bounds,
                    sortOption,
                    userLat ?: 0.0,
                    userLng ?: 0.0,
                    onlineFirst
                )
            }.flow.map { data ->
                data.filter { it.merchant != null }
                    .map {
                        it.merchant!!.apply {
                            this.physicalAmount = it.physicalAmount ?: 0
                            this.distance = calculateDistance(this, userLat, userLng)
                        }
                    }
            }
        } else {
            Pager(pagerConfig) {
                val types = getAtmTypes(filterMode)
                exploreData.observeAtmsPaging(
                    filters.query,
                    filters.territory,
                    types,
                    bounds,
                    canSortByDistance && filters.sortOption == SortOption.Distance,
                    userLat ?: 0.0,
                    userLng ?: 0.0
                )
            }.flow.map { data -> data.map { it.apply { distance = calculateDistance(it, userLat, userLng) } } }
        } as Flow<PagingData<SearchResult>>
    }

    private fun countPagedResults() {
        val bounds = radiusBounds

        viewModelWorkerScope.launch {
            val radiusBounds = bounds?.let {
                locationProvider.getRadiusBounds(
                    bounds.centerLat,
                    bounds.centerLng,
                    radius
                )
            }
            val result = if (exploreTopic == ExploreTopic.Merchants) {
                val type = getMerchantType(filterMode.value ?: FilterMode.Online)
                exploreData.getMerchantsResultCount(
                    _appliedFilters.value.query,
                    _appliedFilters.value.territory,
                    type,
                    _appliedFilters.value.payment,
                    _appliedFilters.value.denominationType,
                    radiusBounds ?: GeoBounds.noBounds
                )
            } else {
                val types = getAtmTypes(filterMode.value ?: FilterMode.All)
                exploreData.getAtmsResultsCount(
                    _appliedFilters.value.query,
                    types,
                    _appliedFilters.value.territory,
                    radiusBounds ?: GeoBounds.noBounds
                )
            }
            _pagingSearchResultsCount.postValue(result)
        }
    }

    private fun resetFilterMode() {
        val defaultMode = if (exploreTopic == ExploreTopic.Merchants) {
            FilterMode.Online
        } else {
            FilterMode.All
        }

        if (defaultMode != _filterMode.value) {
            clearSearchResults()
        }

        _filterMode.value = defaultMode
    }

    private fun getMerchantType(filterMode: FilterMode): String {
        return when (filterMode) {
            FilterMode.Online -> MerchantType.ONLINE
            FilterMode.Nearby -> MerchantType.PHYSICAL
            else -> MerchantType.BOTH
        }
    }

    private fun getAtmTypes(filterMode: FilterMode): List<String> {
        return when (filterMode) {
            FilterMode.Buy -> listOf(AtmType.BUY, AtmType.BOTH, "")
            FilterMode.Sell -> listOf(AtmType.SELL, AtmType.BOTH)
            FilterMode.BuySell -> listOf(AtmType.BOTH)
            else -> listOf(AtmType.BUY, AtmType.SELL, AtmType.BOTH, "")
        }
    }

    private fun ceilByRadius(original: GeoBounds, radius: Double): GeoBounds {
        val inRadius = locationProvider.getRadiusBounds(original.centerLat, original.centerLng, radius)

        return GeoBounds(
            min(original.northLat, inRadius.northLat),
            min(original.eastLng, inRadius.eastLng),
            max(original.southLat, inRadius.southLat),
            max(original.westLng, inRadius.westLng),
            original.centerLat,
            original.centerLng,
            original.zoomLevel
        )
    }

    private fun resolveAddress(bounds: GeoBounds) {
        lastResolvedAddress = bounds
        val address = locationProvider.getCurrentLocationAddress(bounds.centerLat, bounds.centerLng)
        address?.let {
            val name = "${address.country}, ${address.city}"
            _searchLocationName.postValue(name)
        }
    }

    private fun calculateDistance(item: SearchResult, userLat: Double?, userLng: Double?): Double {
        return if (
            item.type != MerchantType.ONLINE && _isLocationEnabled.value == true && userLat != null && userLng != null
        ) {
            locationProvider.distanceBetween(userLat, userLng, item.latitude ?: 0.0, item.longitude ?: 0.0)
        } else {
            Double.NaN
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun setPhysicalResults(results: List<SearchResult>) {
        _physicalSearchResults.value = results
    }

    fun setObservedLastError() {
        viewModelScope.launch { syncStatusService.setObservedLastError() }
    }

    private fun hasZoomLevelChanged(currentZoomLevel: Float): Boolean = previousZoomLevel != currentZoomLevel

    private fun hasCameraCenterChanged(currentCenterPosition: GeoBounds): Boolean =
        locationProvider.distanceBetweenCenters(previousCameraGeoBounds, currentCenterPosition) != 0.0

    private fun transformBounds(bounds: GeoBounds?, mode: FilterMode): GeoBounds {
        return if (
            bounds != null &&
            isLocationEnabled.value == true &&
            (exploreTopic == ExploreTopic.ATMs || mode == FilterMode.Nearby)
        ) {
            val radiusBounds =
                locationProvider.getRadiusBounds(
                    bounds.centerLat,
                    bounds.centerLng,
                    radius
                )
            this.radiusBounds = radiusBounds
            radiusBounds
        } else {
            radiusBounds = null
            GeoBounds.noBounds
        }
    }

    fun trackFilterEvents(dashPaymentOn: Boolean, giftCardPaymentOn: Boolean) {
        if (exploreTopic == ExploreTopic.Merchants) {
            if (dashPaymentOn) {
                logEvent(AnalyticsConstants.Explore.FILTER_MERCHANT_SELECT_DASH)
            }

            if (giftCardPaymentOn) {
                logEvent(AnalyticsConstants.Explore.FILTER_MERCHANT_SELECT_GIFT_CARD)
            }
        }

        logEvent(
            if (exploreTopic == ExploreTopic.Merchants) {
                when (_appliedFilters.value.sortOption) {
                    SortOption.Name -> AnalyticsConstants.Explore.FILTER_MERCHANT_SORT_BY_NAME
                    SortOption.Distance -> AnalyticsConstants.Explore.FILTER_MERCHANT_SORT_BY_DISTANCE
                    SortOption.Discount -> AnalyticsConstants.Explore.FILTER_MERCHANT_SORT_BY_DISTANCE
                }
            } else {
                when (_appliedFilters.value.sortOption) {
                    SortOption.Name -> AnalyticsConstants.Explore.FILTER_ATM_SORT_BY_NAME
                    SortOption.Distance -> AnalyticsConstants.Explore.FILTER_ATM_SORT_BY_DISTANCE
                    SortOption.Discount -> ""
                }
            }
        )

        if (_appliedFilters.value.territory.isEmpty()) {
            if (exploreTopic == ExploreTopic.Merchants) {
                logEvent(AnalyticsConstants.Explore.FILTER_MERCHANT_CURRENT_LOCATION)
            } else {
                logEvent(AnalyticsConstants.Explore.FILTER_ATM_CURRENT_LOCATION)
            }
        } else {
            if (exploreTopic == ExploreTopic.Merchants) {
                logEvent(AnalyticsConstants.Explore.FILTER_MERCHANT_SELECTED_LOCATION)
            } else {
                logEvent(AnalyticsConstants.Explore.FILTER_ATM_SELECTED_LOCATION)
            }
        }

        logEvent(
            when (_appliedFilters.value.radius) {
                1 -> {
                    if (exploreTopic == ExploreTopic.Merchants) {
                        AnalyticsConstants.Explore.FILTER_MERCHANT_ONE_MILE
                    } else {
                        AnalyticsConstants.Explore.FILTER_ATM_ONE_MILE
                    }
                }
                5 -> {
                    if (exploreTopic == ExploreTopic.Merchants) {
                        AnalyticsConstants.Explore.FILTER_MERCHANT_FIVE_MILE
                    } else {
                        AnalyticsConstants.Explore.FILTER_ATM_FIVE_MILE
                    }
                }
                50 -> {
                    if (exploreTopic == ExploreTopic.Merchants) {
                        AnalyticsConstants.Explore.FILTER_MERCHANT_FIFTY_MILE
                    } else {
                        AnalyticsConstants.Explore.FILTER_ATM_FIFTY_MILE
                    }
                }
                else -> {
                    if (exploreTopic == ExploreTopic.Merchants) {
                        AnalyticsConstants.Explore.FILTER_MERCHANT_TWENTY_MILE
                    } else {
                        AnalyticsConstants.Explore.FILTER_ATM_TWENTY_MILE
                    }
                }
            }
        )

        if (_isLocationEnabled.value == true) {
            if (exploreTopic == ExploreTopic.Merchants) {
                logEvent(AnalyticsConstants.Explore.FILTER_MERCHANT_LOCATION_ALLOWED)
            } else {
                logEvent(AnalyticsConstants.Explore.FILTER_ATM_LOCATION_ALLOWED)
            }
        } else {
            if (exploreTopic == ExploreTopic.Merchants) {
                logEvent(AnalyticsConstants.Explore.FILTER_MERCHANT_LOCATION_DENIED)
            } else {
                logEvent(AnalyticsConstants.Explore.FILTER_ATM_LOCATION_DENIED)
            }
        }

        if (exploreTopic == ExploreTopic.Merchants) {
            logEvent(AnalyticsConstants.Explore.FILTER_MERCHANT_APPLY_ACTION)
        } else {
            logEvent(AnalyticsConstants.Explore.FILTER_ATM_APPLY_ACTION)
        }
    }

    private fun logFilterChange(mode: FilterMode) {
        if (exploreTopic == ExploreTopic.Merchants) {
            when (mode) {
                FilterMode.Online ->
                    analyticsService.logEvent(AnalyticsConstants.Explore.ONLINE_MERCHANTS, mapOf())
                FilterMode.Nearby ->
                    analyticsService.logEvent(AnalyticsConstants.Explore.NEARBY_MERCHANTS, mapOf())
                else -> analyticsService.logEvent(AnalyticsConstants.Explore.ALL_MERCHANTS, mapOf())
            }
        } else {
            when (mode) {
                FilterMode.Buy -> analyticsService.logEvent(AnalyticsConstants.Explore.BUY_ATM, mapOf())
                FilterMode.Sell -> analyticsService.logEvent(AnalyticsConstants.Explore.SELL_ATM, mapOf())
                FilterMode.BuySell -> analyticsService.logEvent(AnalyticsConstants.Explore.BUY_SELL_ATM, mapOf())
                else -> analyticsService.logEvent(AnalyticsConstants.Explore.ALL_ATM, mapOf())
            }
        }
    }

    fun logFiltersOpened(fromTop: Boolean) {
        if (fromTop) {
            if (exploreTopic == ExploreTopic.Merchants) {
                analyticsService.logEvent(AnalyticsConstants.Explore.FILTER_MERCHANTS_TOP, mapOf())
            } else {
                analyticsService.logEvent(AnalyticsConstants.Explore.FILTER_ATM_TOP, mapOf())
            }
        }
    }

    fun logEvent(event: String) {
        analyticsService.logEvent(event, mapOf())
    }

    suspend fun getRandomCTXMerchant(): String? = withContext(Dispatchers.IO) {
        exploreData.getRandomMerchant("CTXSpend")
    }
}
