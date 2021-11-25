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
import org.dash.wallet.features.exploredash.data.AtmDao
import org.dash.wallet.features.exploredash.data.MerchantDao
import org.dash.wallet.features.exploredash.data.model.*
import org.dash.wallet.features.exploredash.data.model.GeoBounds
import org.dash.wallet.features.exploredash.services.UserLocation
import org.dash.wallet.features.exploredash.services.UserLocationStateInt
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
    private val merchantDao: MerchantDao,
    private val atmDao: AtmDao,
    private val locationProvider: UserLocationStateInt
) : ViewModel() {
    companion object {
        const val QUERY_DEBOUNCE_VALUE = 300L
        const val PAGE_SIZE = 100
        const val MAX_ITEMS_IN_MEMORY = 300
        const val METERS_IN_MILE = 1609.344
        const val METERS_IN_KILOMETER = 1000.0
        const val MIN_ZOOM_LEVEL = 8f
        const val DEFAULT_RADIUS_OPTION = 20
        const val MAX_MARKERS = 100
        const val DEFAULT_SORT_BY_DISTANCE = true
    }

    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    val navigationCallback = SingleLiveEvent<NavigationRequest>()

    private var boundedFilterJob: Job? = null
    private var pagingFilterJob: Job? = null

    val isMetric = !Locale.getDefault().isO3Country.equals("usa", true) &&
            !Locale.getDefault().isO3Country.equals("mmr", true)

    private val searchQuery = MutableStateFlow("")
    var exploreTopic = ExploreTopic.Merchants
        private set

    private var lastResolvedAddress: GeoBounds? = null
    private var _currentUserLocation: MutableStateFlow<UserLocation?> = MutableStateFlow(null)
    val currentUserLocation = _currentUserLocation.asLiveData()

    private val _selectedTerritory = MutableStateFlow("")
    var selectedTerritory: String
        get() = _selectedTerritory.value
        set(value) {
            _selectedTerritory.value = value
        }

    // Can be miles or kilometers, see isMetric
    private val _selectedRadiusOption = MutableStateFlow(DEFAULT_RADIUS_OPTION)
    var selectedRadiusOption: Int
        get() = _selectedRadiusOption.value
        set(value) { _selectedRadiusOption.value = value }

    // In meters
    val radius: Double
        get() = if (isMetric) selectedRadiusOption * METERS_IN_KILOMETER else selectedRadiusOption * METERS_IN_MILE

    private var radiusBounds: GeoBounds? = null

    private val _searchBounds = MutableStateFlow<GeoBounds?>(null)
    var searchBounds: GeoBounds?
        get() = _searchBounds.value
        set(value) {
            value?.let {
                _searchBounds.value = ceilByRadius(value, radius)
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


    // Used for the list of search results
    private val pagingSearchFlow: Flow<PagingData<SearchResult>> = searchQuery
        .debounce(QUERY_DEBOUNCE_VALUE)
        .flatMapLatest { query ->
            _paymentMethodFilter.flatMapLatest { payment ->
                _sortByDistance.flatMapLatest { sortByDistance ->
                    _selectedRadiusOption.flatMapLatest { _ ->
                        _selectedTerritory.flatMapLatest { territory ->
                            _filterMode.flatMapLatest { mode ->
                                _searchBounds
                                    .filterNotNull()
                                    .filter {
                                        mode == FilterMode.Online ||
                                                isLocationEnabled.value != true ||
                                                territory.isNotBlank() ||
                                                it.zoomLevel > MIN_ZOOM_LEVEL
                                    }
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
                                        Pager(
                                            PagingConfig(
                                                pageSize = PAGE_SIZE,
                                                enablePlaceholders = false,
                                                maxSize = MAX_ITEMS_IN_MEMORY
                                            )
                                        ) {
                                            _appliedFilters.postValue(
                                                FilterOptions(query, territory, payment, selectedRadiusOption)
                                            )

                                            getPagingSource(query, territory, payment, mode, bounds, sortByDistance)
                                        }.flow
                                            .cachedIn(viewModelScope)
                                    }
                            }
                        }
                    }
                }
            }
        }

    // Used for the map
    val boundedSearchFlow = searchQuery
        .debounce(QUERY_DEBOUNCE_VALUE)
        .flatMapLatest { query ->
            _paymentMethodFilter.flatMapLatest { payment ->
                _selectedRadiusOption.flatMapLatest { _ ->
                    _selectedTerritory.flatMapLatest { territory ->
                        _searchBounds
                            .filterNotNull()
                            .filter {
                                territory.isNotBlank() ||
                                it.zoomLevel > MIN_ZOOM_LEVEL
                            }
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
            _pagingSearchResults.value = PagingData.from(listOf())
            _physicalSearchResults.value = listOf()
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
                    .onEach(_physicalSearchResults::postValue)
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
                (selectedTerritory.isEmpty() || lastResolved == null ||
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
        _filterMode.value = mode
    }

    fun submitSearchQuery(query: String) {
        searchQuery.value = query
    }

    suspend fun getTerritoriesWithPOIs(): List<String> {
        return if (exploreTopic == ExploreTopic.Merchants) {
            merchantDao.getTerritories().filter { it.isNotEmpty() }
        } else {
            atmDao.getTerritories().filter { it.isNotEmpty() }
        }
    }

    fun openMerchantDetails(merchant: Merchant) {
        _selectedItem.postValue(merchant)
    }

    fun openAtmDetails(atm: Atm) {
        _selectedItem.postValue(atm)
    }

    fun openSearchResults() {
        _selectedItem.postValue(null)
    }

    fun onMapMarkerSelected(id: Int) {
        val item = _physicalSearchResults.value?.firstOrNull { it.id == id }
        item?.let {
            if (item is Merchant) {
                openMerchantDetails(item)
            } else {
                openAtmDetails(item as Atm)
            }
        }
    }

    fun sendDash() {
        navigationCallback.postValue(NavigationRequest.SendDash)
    }

    fun receiveDash() {
        navigationCallback.postValue(NavigationRequest.ReceiveDash)
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
        searchQuery.value = ""
        _selectedTerritory.value = ""
        _paymentMethodFilter.value = ""
        _selectedRadiusOption.value = DEFAULT_RADIUS_OPTION
    }

    private fun resetFilterMode() {
        _filterMode.value = if (exploreTopic == ExploreTopic.Merchants) {
            FilterMode.Online
        } else {
            FilterMode.All
        }
    }

    private fun getBoundedFlow(
        query: String,
        territory: String,
        payment: String,
        filterMode: FilterMode,
        bounds: GeoBounds
    ): Flow<List<SearchResult>> {
        return if (exploreTopic == ExploreTopic.Merchants) {
            merchantDao.observePhysical(query, territory, payment, bounds)
        } else {
            val types = getAtmTypes(filterMode)
            atmDao.observePhysical(query, territory, types, bounds)
        }
    }

    private fun getPagingSource(
        query: String,
        territory: String,
        payment: String,
        filterMode: FilterMode,
        bounds: GeoBounds,
        sortByDistance: Boolean
    ): PagingSource<Int, SearchResult> {
        val userLat = currentUserLocation.value?.latitude
        val userLng = currentUserLocation.value?.longitude
        val byDistance = _filterMode.value != FilterMode.Online &&
                         _isLocationEnabled.value == true &&
                         userLat != null && userLng != null &&
                         sortByDistance
        val onlineFirst = _isLocationEnabled.value != true

        @Suppress("UNCHECKED_CAST")
        return if (exploreTopic == ExploreTopic.Merchants) {
            val type = getMerchantType(filterMode)
            merchantDao.observeAllPaging(query, territory, type, payment, bounds,
                    byDistance, userLat ?: 0.0, userLng ?: 0.0, onlineFirst)
        } else {
            val types = getAtmTypes(filterMode)
            atmDao.observeAllPaging(query, territory, types, bounds,
                    byDistance, userLat ?: 0.0, userLng ?: 0.0)
        } as PagingSource<Int, SearchResult>
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
                merchantDao.getPagingResultsCount(
                        searchQuery.value, selectedTerritory, type,
                        paymentMethodFilter, radiusBounds ?: GeoBounds.noBounds
                )
            } else {
                val types = getAtmTypes(filterMode.value ?: FilterMode.All)
                atmDao.getPagingResultsCount(
                        searchQuery.value, types,
                        selectedTerritory, radiusBounds ?: GeoBounds.noBounds
                )
            }
            _pagingSearchResultsCount.postValue(result)
        }
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

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun setPhysicalResults(results: List<SearchResult>) {
        _physicalSearchResults.value = results
    }
}