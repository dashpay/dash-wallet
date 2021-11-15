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
import org.dash.wallet.features.exploredash.services.UserLocationState
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
    private val locationProvider: UserLocationState
) : ViewModel() {
    companion object {
        const val QUERY_DEBOUNCE_VALUE = 300L
        const val PAGE_SIZE = 100
        const val MAX_ITEMS_IN_MEMORY = 300
        const val METERS_IN_MILE = 1609.344
        const val METERS_IN_KILOMETER = 1000.0
        const val MIN_ZOOM_LEVEL = 8f
        const val DEFAULT_RADIUS_OPTION = 20
    }

    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    val navigationCallback = SingleLiveEvent<NavigationRequest>()

    private var boundedFilterJob: Job? = null
    private var pagingFilterJob: Job? = null

    private val searchQuery = MutableStateFlow("")
    var exploreTopic = ExploreTopic.Merchants
        private set

    private var lastResolvedAddress: GeoBounds? = null
    private var currentUserLocationState: MutableStateFlow<UserLocation?> = MutableStateFlow(null)
    val currentUserLocation = currentUserLocationState.asLiveData()

    private val _pickedTerritory = MutableStateFlow("")
    var pickedTerritory: String
        get() = _pickedTerritory.value
        set(value) {
            _pickedTerritory.value = value
        }

    val isMetric = !Locale.getDefault().isO3Country.equals("usa", true) &&
            !Locale.getDefault().isO3Country.equals("mmr", true)

    // Can be miles or kilometers, see isMetric
    private val _selectedRadiusOption = MutableStateFlow(DEFAULT_RADIUS_OPTION)
    var selectedRadiusOption: Int
        get() = _selectedRadiusOption.value
        set(value) { _selectedRadiusOption.value = value }

    // In meters
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

    private val _paymentMethodFilter = MutableStateFlow("")
    var paymentMethodFilter: String
        get() = _paymentMethodFilter.value
        set(value) {
            _paymentMethodFilter.value = value
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

    private val _searchLocationName = MutableLiveData<String>()
    val searchLocationName: LiveData<String>
        get() = _searchLocationName

    private val _selectedItem = MutableLiveData<SearchResult?>()
    val selectedItem: LiveData<SearchResult?>
        get() = _selectedItem

    private val _isLocationEnabled = MutableLiveData(false)
    val isLocationEnabled: LiveData<Boolean>
        get() = _isLocationEnabled

    private val _appliedFilters = MutableLiveData<FilterOptions?>(null)
    val appliedFilters: LiveData<FilterOptions?>
        get() = _appliedFilters


    // Used for the list of search results
    private val pagingSearchFlow: Flow<PagingData<SearchResult>> = searchQuery
        .debounce(QUERY_DEBOUNCE_VALUE)
        .flatMapLatest { query ->
            _paymentMethodFilter
                .flatMapLatest { payment ->
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
                                                        locationProvider.getRadiusBounds(
                                                            bounds.centerLat,
                                                            bounds.centerLng,
                                                            radius
                                                        )
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
                                                        getPagingSource(
                                                            query,
                                                            territory,
                                                            payment,
                                                            mode,
                                                            bounds
                                                        )
                                                    }.flow
                                                        .cachedIn(viewModelScope)
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
            _paymentMethodFilter
                .flatMapLatest { payment ->
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
                                                    getBoundedFlow(
                                                        query,
                                                        territory,
                                                        payment,
                                                        mode,
                                                        bounds
                                                    )
                                                }
                                        }
                                }
                        }
                }
        }


    fun init(exploreTopic: ExploreTopic) {
        if (this.exploreTopic != exploreTopic) {
            clearFilters(exploreTopic)
            _pagingSearchResults.value = PagingData.from(listOf())
            _physicalSearchResults.value = listOf()
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
                    .onEach(_physicalSearchResults::postValue)
                    .launchIn(viewModelWorkerScope)
            }
            // Right now we don't show the map at all while location is disabled
        }

        _searchBounds
            .filterNotNull()
            .filter {
                val lastResolved = lastResolvedAddress
                lastResolved == null ||
                locationProvider.distanceBetweenCenters(lastResolved, it) > radius / 2
            }
            .onEach {
                val address = locationProvider
                    .getCurrentLocationAddress(it.centerLat, it.centerLng)

                address?.let {
                    _searchLocationName.postValue("${address.country}, ${address.city}")
                }
            }
            .launchIn(viewModelWorkerScope)
    }

    fun onExitSearch() {
        this.boundedFilterJob?.cancel(CancellationException())
        this.pagingFilterJob?.cancel(CancellationException())
        clearFilters(exploreTopic)
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
                currentUserLocationState.value = it
            }
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
            val atms = getAtmTypes(filterMode)
            atmDao.observePhysical(query, territory, atms, bounds)
        }
    }

    private fun getPagingSource(
        query: String,
        territory: String,
        payment: String,
        filterMode: FilterMode,
        bounds: GeoBounds
    ): PagingSource<Int, SearchResult> {
        _appliedFilters.postValue(FilterOptions(
            territory, payment, selectedRadiusOption
        ))

        @Suppress("UNCHECKED_CAST")
        return if (exploreTopic == ExploreTopic.Merchants) {
            val type = getMerchantType(filterMode)
            merchantDao.observeAllPaging(query, territory, type, payment, bounds)
        } else {
            val types = getAtmTypes(filterMode)
            atmDao.observeAllPaging(query, territory, types, bounds)
        } as PagingSource<Int, SearchResult>
    }

    private fun clearFilters(topic: ExploreTopic) {
        _filterMode.value = if (topic == ExploreTopic.Merchants) {
            FilterMode.Online
        } else {
            FilterMode.All
        }
        searchQuery.value = ""
        _pickedTerritory.value = ""
        _paymentMethodFilter.value = ""
        _selectedRadiusOption.value = DEFAULT_RADIUS_OPTION
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
}