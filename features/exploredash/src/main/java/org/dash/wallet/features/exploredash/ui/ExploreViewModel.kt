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

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.*
import androidx.paging.*
import androidx.paging.PagingData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.features.exploredash.data.ExploreDataSource
import org.dash.wallet.features.exploredash.data.model.*
import org.dash.wallet.features.exploredash.data.model.GeoBounds
import org.dash.wallet.features.exploredash.services.UserLocation
import org.dash.wallet.features.exploredash.services.UserLocationStateInt
import org.dash.wallet.features.exploredash.ui.extensions.Const
import org.dash.wallet.features.exploredash.ui.extensions.isMetric
import java.util.*
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

enum class ExploreTopic {
    Merchants, ATMs
}

enum class NavigationRequest {
    SendDash, ReceiveDash, None
}

enum class FilterMode {
    All, Online, Physical, Buy, Sell, BuySell
}

enum class ScreenState {
    SearchResults,
    MerchantLocations,
    DetailsGrouped,
    Details
}

data class FilterOptions(
    val query: String,
    val territory: String,
    val payment: String,
    val radius: Int
)

@ExperimentalCoroutinesApi
@FlowPreview
@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val exploreData: ExploreDataSource,
    private val locationProvider: UserLocationStateInt
) : ViewModel() {
    companion object {
        const val QUERY_DEBOUNCE_VALUE = 300L
        const val PAGE_SIZE = 100
        const val MAX_ITEMS_IN_MEMORY = 300
        const val MIN_ZOOM_LEVEL = 8f
        const val DEFAULT_RADIUS_OPTION = 20
        const val MAX_MARKERS = 100
        const val DEFAULT_SORT_BY_DISTANCE = true
    }

    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    val navigationCallback = SingleLiveEvent<NavigationRequest>()
    val recenterMapCallback = SingleLiveEvent<Unit>()
    private var boundedFilterJob: Job? = null
    private var pagingFilterJob: Job? = null
    private var allMerchantLocationsJob: Job? = null

    val isMetric = Locale.getDefault().isMetric

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: String
        get() = _searchQuery.value

    var exploreTopic = ExploreTopic.Merchants
        private set

    private var lastResolvedAddress: GeoBounds? = null
    private var _currentUserLocation: MutableStateFlow<UserLocation?> = MutableStateFlow(null)
    val currentUserLocation = _currentUserLocation.asLiveData()

    private val _selectedTerritory = MutableStateFlow("")
    val selectedTerritory = _selectedTerritory.asLiveData()
    fun setSelectedTerritory(territory: String){
        _selectedTerritory.value = territory
    }

    // Can be miles or kilometers, see isMetric
    private val _selectedRadiusOption = MutableStateFlow(DEFAULT_RADIUS_OPTION)
    val selectedRadiusOption = _selectedRadiusOption.asLiveData()
    fun setSelectedRadiusOption(selectedRadius: Int){
        _selectedRadiusOption.value = selectedRadius
    }
    // In meters
    val radius: Double
        get() = if (isMetric) (selectedRadiusOption.value ?: DEFAULT_RADIUS_OPTION) * Const.METERS_IN_KILOMETER
        else (selectedRadiusOption.value ?: DEFAULT_RADIUS_OPTION) * Const.METERS_IN_MILE

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

    private val _paymentMethodFilter = MutableStateFlow("")
    var paymentMethodFilter: String
        get() = _paymentMethodFilter.value
        set(value) {
            _paymentMethodFilter.value = value
        }

    private val _sortByDistance = MutableStateFlow(true)
    var sortByDistance: Boolean
        get() = _sortByDistance.value
        set(value) {
            _sortByDistance.value = value
        }

    private val _filterMode = MutableStateFlow(FilterMode.Online)

    // Need a mediator here because flow.asLiveData() doesn't play well with liveData.value
    var filterMode: LiveData<FilterMode> = MediatorLiveData<FilterMode>().apply {
        addSource(_filterMode.asLiveData(), this::setValue)
    }

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

    private val _appliedFilters = MutableLiveData(FilterOptions("", "", "", DEFAULT_RADIUS_OPTION))
    val appliedFilters: LiveData<FilterOptions>
        get() = _appliedFilters

    private val _screenState = MutableLiveData(ScreenState.SearchResults)
    val screenState: LiveData<ScreenState>
        get() = _screenState

    // Used for the list of search results
    private val pagingSearchFlow: Flow<PagingData<SearchResult>> = _searchQuery
        .debounce(QUERY_DEBOUNCE_VALUE)
        .flatMapLatest { query ->
            _paymentMethodFilter.flatMapLatest { payment ->
                _sortByDistance.flatMapLatest { sortByDistance ->
                    _selectedRadiusOption.flatMapLatest { selectedRadius ->
                        _selectedTerritory.flatMapLatest { territory ->
                            _filterMode.flatMapLatest { mode ->
                                clearSearchResults()
                                _searchBounds
                                    .filterNotNull()
                                    .filter { screenState.value == ScreenState.SearchResults }
                                    .map { bounds ->
                                        if (isLocationEnabled.value == true &&
                                           (exploreTopic == ExploreTopic.ATMs ||
                                            mode == FilterMode.Physical)
                                        ) {
                                            val radiusBounds = locationProvider.getRadiusBounds(
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
                                    .flatMapLatest { bounds ->
                                        _appliedFilters.postValue(
                                            FilterOptions(query, territory, payment, selectedRadius)
                                        )
                                        getPagingFlow(query, territory, payment, mode, bounds, sortByDistance)
                                            .cachedIn(viewModelScope)
                                    }
                            }
                        }
                    }
                }
            }
        }

    // Used for the map
    val boundedSearchFlow = _searchQuery
        .debounce(QUERY_DEBOUNCE_VALUE)
        .flatMapLatest { query ->
            _paymentMethodFilter.flatMapLatest { payment ->
                _selectedRadiusOption.flatMapLatest { _ ->
                    _selectedTerritory.flatMapLatest { territory ->
                        _searchBounds
                            .filterNotNull()
                            .filter { screenState.value == ScreenState.SearchResults }
                            .flatMapLatest { bounds ->
                                _filterMode
                                    .filterNot { it == FilterMode.Online }
                                    .flatMapLatest { mode ->
                                        getBoundedFlow(query, territory, payment, mode, bounds)
                                    }
                            }
                    }
                }
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
                it.zoomLevel > MIN_ZOOM_LEVEL &&
                (selectedTerritory.value?.isEmpty() == true || lastResolved == null ||
                locationProvider.distanceBetweenCenters(lastResolved, it) > radius / 2)
            }
            .onEach(::resolveAddress)
            .launchIn(viewModelWorkerScope)

        _selectedTerritory
            .onEach { territory ->
                when {
                    territory.isNotEmpty() -> {
                        _searchLocationName.postValue(territory)
                    }
                    lastResolvedAddress != null -> {
                        resolveAddress(lastResolvedAddress!!)
                    }
                    else -> {
                        _searchLocationName.postValue("")
                    }
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
        if (_filterMode.value != mode) {
            _filterMode.value = mode
        }
    }

    fun submitSearchQuery(query: String) {
        _searchQuery.value = query
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
        val item = _allMerchantLocations.value?.firstOrNull { it.id == id } ?:
                   _physicalSearchResults.value?.firstOrNull { it.id == id }
        item?.let {
            if (item is Merchant) {
                openMerchantDetails(item)
            } else {
                openAtmDetails(item as Atm)
            }
        }
    }

    fun openAllMerchantLocations(merchantId: Long, source: String) {
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
                val limitResults = _isLocationEnabled.value != true || selectedTerritory.value?.isNotEmpty() == true
                val limit = if (limitResults) 100 else -1
                exploreData.observeMerchantLocations(
                    merchantId, source, selectedTerritory.value!!, "", radiusBounds, limit
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
                (isLocationEnabled.value == true && selectedTerritory.value?.isEmpty() == true)
    }

    fun sendDash() {
        navigationCallback.postValue(NavigationRequest.SendDash)
    }

    fun receiveDash() {
        navigationCallback.postValue(NavigationRequest.ReceiveDash)
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

        viewModelScope.launch {
            locationProvider.observeUpdates().collect {
                _currentUserLocation.value = it
            }
        }
    }

    fun clearFilters() {
        _searchQuery.value = ""
        _selectedTerritory.value = ""
        _paymentMethodFilter.value = ""
        _selectedRadiusOption.value = DEFAULT_RADIUS_OPTION
    }

    private fun clearSearchResults() {
        _pagingSearchResults.postValue(PagingData.from(listOf()))
        _physicalSearchResults.postValue(listOf())
    }

    private fun getBoundedFlow(
        query: String,
        territory: String,
        payment: String,
        filterMode: FilterMode,
        bounds: GeoBounds
    ): Flow<List<SearchResult>> {
        return if (exploreTopic == ExploreTopic.Merchants) {
            exploreData.observePhysicalMerchants(query, territory, payment, bounds)
        } else {
            val types = getAtmTypes(filterMode)
            exploreData.observePhysicalAtms(query, territory, types, bounds)
        }
    }

    private fun getPagingFlow(
        query: String,
        territory: String,
        payment: String,
        filterMode: FilterMode,
        bounds: GeoBounds,
        sortByDistance: Boolean
    ): Flow<PagingData<SearchResult>> {
        val userLat = currentUserLocation.value?.latitude
        val userLng = currentUserLocation.value?.longitude
        val byDistance = _filterMode.value != FilterMode.Online &&
                _isLocationEnabled.value == true &&
                userLat != null && userLng != null &&
                sortByDistance
        val onlineFirst = _isLocationEnabled.value != true

        val pagerConfig = PagingConfig(
            pageSize = PAGE_SIZE,
            enablePlaceholders = false,
            maxSize = MAX_ITEMS_IN_MEMORY
        )

        @Suppress("UNCHECKED_CAST")
        return if (exploreTopic == ExploreTopic.Merchants) {
            Pager(pagerConfig) {
                val type = getMerchantType(filterMode)
                exploreData.observeMerchantsPaging(
                    query, territory, type, payment, bounds,
                    byDistance, userLat ?: 0.0, userLng ?: 0.0, onlineFirst
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
                    query, territory, types, bounds,
                    byDistance, userLat ?: 0.0, userLng ?: 0.0
                )
            }.flow.map { data ->
                data.map {
                    it.apply {
                        distance = calculateDistance(it, userLat, userLng)
                    }
                }
            }
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
                        _searchQuery.value, selectedTerritory.value!!, type,
                        paymentMethodFilter, radiusBounds ?: GeoBounds.noBounds
                )
            } else {
                val types = getAtmTypes(filterMode.value ?: FilterMode.All)
                exploreData.getAtmsResultsCount(
                        _searchQuery.value, types,
                        selectedTerritory.value!!, radiusBounds ?: GeoBounds.noBounds
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
            FilterMode.Physical -> MerchantType.PHYSICAL
            else -> MerchantType.BOTH
        }
    }

    private fun getAtmTypes(filterMode: FilterMode): List<String> {
        return when (filterMode) {
            FilterMode.Buy -> listOf(AtmType.BUY, AtmType.BOTH)
            FilterMode.Sell -> listOf(AtmType.SELL, AtmType.BOTH)
            FilterMode.BuySell -> listOf(AtmType.BOTH)
            else -> listOf(AtmType.BUY, AtmType.SELL, AtmType.BOTH)
        }
    }

    private fun ceilByRadius(original: GeoBounds, radius: Double): GeoBounds {
        val inRadius =
            locationProvider.getRadiusBounds(original.centerLat, original.centerLng, radius)

        return GeoBounds(
            min(original.northLat, inRadius.northLat),
            min(original.eastLng, inRadius.eastLng),
            max(original.southLat, inRadius.southLat),
            max(original.westLng, inRadius.westLng),
            original.centerLat, original.centerLng,
            original.zoomLevel
        )
    }

    private fun resolveAddress(bounds: GeoBounds) {
        lastResolvedAddress = bounds
        val address = locationProvider
            .getCurrentLocationAddress(bounds.centerLat, bounds.centerLng)
        address?.let {
            val name = "${address.country}, ${address.city}"
            _searchLocationName.postValue(name)
        }
    }

    private fun calculateDistance(item: SearchResult, userLat: Double?, userLng: Double?): Double {
        return if (item.type != MerchantType.ONLINE &&
            _isLocationEnabled.value == true &&
            userLat != null && userLng != null
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
}