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
import javax.inject.Inject

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
    private val locationProvider: UserLocationState
) : ViewModel() {
    companion object {
        const val QUERY_DEBOUNCE_VALUE = 300L
        const val PAGE_SIZE = 100
        const val MAX_ITEMS_IN_MEMORY = 300
        const val TEMP__RADIUS_IN_MILES = 50 // TODO
    }

    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    val navigationCallback = SingleLiveEvent<NavigationRequest>()

    private var filterJob: Job? = null
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

    private val merchantsPagingFlow = searchQuery
        .debounce(QUERY_DEBOUNCE_VALUE)
        .flatMapLatest { query ->
            _pickedTerritory
                .flatMapLatest { territory ->
                    Pager(
                        PagingConfig(
                            pageSize = PAGE_SIZE,
                            enablePlaceholders = false,
                            maxSize = MAX_ITEMS_IN_MEMORY
                        )
                    ) {
                        if (query.isNotBlank()) {
                            merchantDao.pagingSearch(sanitizeQuery(query), territory)
                        } else {
                            merchantDao.pagingGet(territory)
                        }
                    }.flow
                        .cachedIn(viewModelScope)
                        .flatMapLatest { data ->
                            _filterMode.map { mode ->
                                data.filter { shouldShow(it, mode) }
                                    .map { it as SearchResult }
                            }
                        }
                }
        }


    private val atmsPagingFlow = searchQuery
        .debounce(QUERY_DEBOUNCE_VALUE)
        .flatMapLatest { query ->
            _pickedTerritory
                .flatMapLatest { territory ->
                    Pager(
                        PagingConfig(
                            pageSize = PAGE_SIZE,
                            enablePlaceholders = false,
                            maxSize = MAX_ITEMS_IN_MEMORY
                        )
                    ) {
                        if (query.isNotBlank()) {
                            atmDao.pagingSearch(sanitizeQuery(query), territory)
                        } else {
                            atmDao.pagingGet(territory)
                        }
                    }.flow
                        .cachedIn(viewModelScope)
                        .flatMapLatest { data ->
                            _filterMode.map { mode ->
                                data.filter { shouldShow(it, mode) }
                                    .map { it as SearchResult }
                            }
                        }
                }
        }

    // TODO (ashikhmin) this most likely will need to be used along with paging source
    // since paging doesn't play well with showing POIs on a map. Paging sources might be
    // only needed when the location is turned off or the user is searching Online merchants
    // and therefore there is a lot of data to show. Will be resolved in NMA-1036
    val merchantsSearchFilterFlow = searchQuery
        .debounce(300)
        .flatMapLatest { query ->
            _pickedTerritory
                .flatMapLatest { territory ->
                    if (query.isNotBlank()) {
                        merchantDao.observeSearchResults(sanitizeQuery(query), territory)
                    } else {
                        merchantDao.observe(territory)
                    }.filterNotNull()
                        .flatMapLatest { merchants ->
                            _filterMode
                                .map { mode ->
                                    merchants.filter { shouldShow(it, mode) }
                                }
                        }
                }
        }


    fun init(exploreTopic: ExploreTopic) {
        if (this.exploreTopic != exploreTopic) {
            clearFilters(exploreTopic)
            _pagingSearchResults.value = PagingData.from(listOf())
        }

        this.exploreTopic = exploreTopic
        this.filterJob?.cancel(CancellationException())

        this.filterJob = if (exploreTopic == ExploreTopic.Merchants) {
            merchantsPagingFlow
        } else {
            atmsPagingFlow
        }.onEach(_pagingSearchResults::postValue)
            .launchIn(viewModelWorkerScope)

        merchantsSearchFilterFlow
            .onEach(_searchResults::postValue)
            .launchIn(viewModelWorkerScope)
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

    private fun clearFilters(topic: ExploreTopic) {
        searchQuery.value = ""
        _pickedTerritory.value = ""
        _filterMode.value = if (topic == ExploreTopic.Merchants) {
            FilterMode.Online
        } else {
            FilterMode.All
        }
    }

    private fun shouldShow(item: SearchResult, mode: FilterMode): Boolean {
        if (item is Merchant) {
            return shouldShow(item, mode)
        } else if (item is Atm) {
            return shouldShow(item, mode)
        }

        return false
    }

    private fun shouldShow(merchant: Merchant, mode: FilterMode): Boolean {
        return if (mode == FilterMode.All) {
            // Showing all merchants
            merchant.active != false
        } else {
            // Showing merchants of specific type or both types
            merchant.active != false && (merchant.type == MerchantType.BOTH ||
                    merchant.type == mode.toString().lowercase())
        }
    }

    private fun shouldShow(atm: Atm, mode: FilterMode): Boolean {
        return if (mode == FilterMode.All) {
            // Showing all ATMs
            atm.active != false
        } else {
            // Showing ATMs of specific type or both types
            atm.active != false && (atm.type == AtmType.BOTH ||
                    (atm.type == AtmType.BUY && mode == FilterMode.Buy) ||
                    (atm.type == AtmType.SELL && mode == FilterMode.Sell))
        }
    }

    private fun sanitizeQuery(query: String): String {
        val escapedQuotes = query.replace(Regex.fromLiteral("\""), "\"\"")
        return "\"$escapedQuotes*\""
    }

    fun monitorUserLocation() {
        viewModelScope.launch {
            _isLocationEnabled.value = true
            locationProvider.observeUpdates().collect {
                val savedLocation = savedLocation

                if (savedLocation == null ||
                    locationProvider.distanceBetween(savedLocation, it) > TEMP__RADIUS_IN_MILES / 2) {
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
}