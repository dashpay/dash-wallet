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
import org.dash.wallet.features.exploredash.services.GeoBounds
import org.dash.wallet.features.exploredash.services.UserLocation
import org.dash.wallet.features.exploredash.services.UserLocationStateInt
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
    }

    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    val navigationCallback = SingleLiveEvent<NavigationRequest>()

    private var boundedFilterJob: Job? = null
    private var pagingFilterJob: Job? = null

    private val searchQuery = MutableStateFlow("")
    var exploreTopic = ExploreTopic.Merchants
        private set

    private var savedLocation: UserLocation? = null
    private var currentUserLocationState: MutableStateFlow<UserLocation?> = MutableStateFlow(null)
    val currentUserLocation = currentUserLocationState.asLiveData()

    private val _pickedTerritory = MutableStateFlow("")
    var pickedTerritory: String
        get() = _pickedTerritory.value
        set(value) {
            _pickedTerritory.value = value
        }

    var isMetric = false // TODO
        private set

    // Can be miles or kilometers, see isMetric
    private val _selectedRadiusOption = MutableStateFlow(20)
    var selectedRadiusOption: Int
        get() = _selectedRadiusOption.value
        set(value) {
            _selectedRadiusOption.value = value
        }

    val radius: Double
        get() = if (isMetric) selectedRadiusOption * METERS_IN_KILOMETER else selectedRadiusOption * METERS_IN_MILE

    private val _searchBounds = MutableStateFlow<GeoBounds?>(null)
    var searchBounds: GeoBounds?
        get() = _searchBounds.value
        set(value) {
            value?.let {
                _searchBounds.value = ceilByRadius(value, radius)
            }
        }

    private val _filterMode = MutableStateFlow(FilterMode.Online)
    var filterMode: LiveData<FilterMode> = MediatorLiveData<FilterMode>().apply {
        addSource(_filterMode.asLiveData(), this::setValue)
    }

    private val _searchResults = MutableLiveData<List<SearchResult>>()
    val searchResults: LiveData<List<SearchResult>>
        get() = _searchResults

    private val _pagingSearchResults = MutableLiveData<PagingData<SearchResult>>()
    val pagingSearchResults: LiveData<PagingData<SearchResult>>
        get() = _pagingSearchResults

    private val _searchResultsTitle = MutableLiveData<String>()
    val searchResultsTitle: LiveData<String>
        get() = _searchResultsTitle

    private val _selectedItem = MutableLiveData<SearchResult?>()
    val selectedItem: LiveData<SearchResult?>
        get() = _selectedItem

    private val _isLocationEnabled = MutableLiveData(false)
    val isLocationEnabled: LiveData<Boolean>
        get() = _isLocationEnabled


    // Used for the list of search results
    private val pagingSearchFlow: Flow<PagingData<SearchResult>> = searchQuery
        .debounce(QUERY_DEBOUNCE_VALUE)
        .flatMapLatest { query ->
            _selectedRadiusOption
                .flatMapLatest { _ ->
                    _pickedTerritory
                        .flatMapLatest { territory ->
                            _filterMode
                                .flatMapLatest { mode ->
                                    _searchBounds
                                        .filterNotNull()
                                        .filter {
                                            mode == FilterMode.Online ||
                                            it.zoomLevel > MIN_ZOOM_LEVEL
                                        }
                                        .map { bounds ->
                                            if (isLocationEnabled.value == true && mode != FilterMode.Online) {
                                                locationProvider.getRadiusBounds(bounds.centerLat, bounds.centerLng, radius)
                                            } else {
                                                GeoBounds.noBounds
                                            }
                                        }
                                        .distinctUntilChanged()
                                        .flatMapLatest { bounds ->
                                            Pager(
                                                PagingConfig(
                                                    pageSize = PAGE_SIZE,
                                                    enablePlaceholders = false,
                                                    maxSize = MAX_ITEMS_IN_MEMORY
                                                )
                                            ) {
                                                getPagingSource(query, territory, mode, bounds)
                                            }.flow
                                                .cachedIn(viewModelScope)
                                        }
                                }
                        }
                }
        }

    // Used for the map
    val boundedSearchFlow = searchQuery
        .debounce(QUERY_DEBOUNCE_VALUE)
        .flatMapLatest { query ->
            _selectedRadiusOption
                .flatMapLatest { _ ->
                    _pickedTerritory
                        .flatMapLatest { territory ->
                            _searchBounds
                                .filterNotNull()
                                .filter { it.zoomLevel > MIN_ZOOM_LEVEL }
                                .flatMapLatest { bounds ->
                                    _filterMode
                                        .filterNot { it == FilterMode.Online }
                                        .flatMapLatest { mode ->
                                            getBoundedFlow(query, territory, mode, bounds)
                                        }
                                }
                        }
                }
        }


    fun init(exploreTopic: ExploreTopic) {
        if (this.exploreTopic != exploreTopic) {
            clearFilters(exploreTopic)
            _pagingSearchResults.value = PagingData.from(listOf())
            _searchResults.value = listOf()
        }

        this.exploreTopic = exploreTopic

        this.pagingFilterJob?.cancel(CancellationException())
        this.pagingFilterJob = pagingSearchFlow
            .distinctUntilChanged()
            .onEach(_pagingSearchResults::postValue)
            .launchIn(viewModelWorkerScope)

        _isLocationEnabled.observeForever { locationEnabled ->
            this.boundedFilterJob?.cancel(CancellationException())

            if (locationEnabled) {
                this.boundedFilterJob = boundedSearchFlow
                    .distinctUntilChanged()
                    .onEach(_searchResults::postValue)
                    .launchIn(viewModelWorkerScope)
            }
            // Right now we don't show the map at all while location is disabled
        }
    }

    fun clearJobs() {
        this.boundedFilterJob?.cancel(CancellationException())
        this.pagingFilterJob?.cancel(CancellationException())
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

    fun sendDash() {
        navigationCallback.postValue(NavigationRequest.SendDash)
    }

    fun receiveDash() {
        navigationCallback.postValue(NavigationRequest.ReceiveDash)
    }

    fun monitorUserLocation() {
        viewModelScope.launch {
            _isLocationEnabled.value = true
            locationProvider.observeUpdates().collect {
                val savedLocation = savedLocation

                if (savedLocation == null ||
                    locationProvider.distanceBetween(savedLocation, it) > radius / 2
                ) {
                    val locationName = locationProvider
                        .getCurrentLocationName(it.latitude, it.longitude)

                    if (locationName.isNotBlank()) {
                        _searchResultsTitle.postValue(locationName)
                    }
                }

                currentUserLocationState.value = it
            }
        }
    }

    private fun getBoundedFlow(
        query: String,
        territory: String,
        filterMode: FilterMode,
        bounds: GeoBounds
    ): Flow<List<SearchResult>> {
        return if (exploreTopic == ExploreTopic.Merchants) {
            merchantDao.observePhysical(query, territory, bounds)
        } else {
            val atms = getAtmTypes(filterMode)
            atmDao.observePhysical(query, territory, atms, bounds)
        }
    }

    private fun getPagingSource(
        query: String,
        territory: String,
        filterMode: FilterMode,
        bounds: GeoBounds
    ): PagingSource<Int, SearchResult> {
        @Suppress("UNCHECKED_CAST")
        return if (exploreTopic == ExploreTopic.Merchants) {
            val types = getMerchantTypes(filterMode)
            merchantDao.observeAllPaging(query, territory, types, bounds)
        } else {
            val types = getAtmTypes(filterMode)
            atmDao.observeAllPaging(query, territory, types, bounds)
        } as PagingSource<Int, SearchResult>
    }

    private fun clearFilters(topic: ExploreTopic) {
        searchQuery.value = ""
        _pickedTerritory.value = ""
        _filterMode.value = if (topic == ExploreTopic.Merchants) {
            FilterMode.Online
        } else {
            FilterMode.All
        }
    }

    private fun getMerchantTypes(filterMode: FilterMode): List<String> {
        return when (filterMode) {
            FilterMode.Online -> listOf(MerchantType.ONLINE, MerchantType.BOTH)
            FilterMode.Physical -> listOf(MerchantType.PHYSICAL, MerchantType.BOTH)
            else -> listOf(MerchantType.ONLINE, MerchantType.PHYSICAL, MerchantType.BOTH)
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
}