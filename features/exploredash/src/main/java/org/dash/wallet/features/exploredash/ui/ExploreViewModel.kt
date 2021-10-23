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

import android.util.Log
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
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import javax.inject.Inject

enum class ExploreTopic {
    Merchants, ATMs
}

enum class NavigationRequest {
    SendDash, None
}

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val exploreRepository: ExploreRepository,
    private val merchantDao: MerchantDao,
    private val atmDao: AtmDao
) : ViewModel() {
    companion object {
        const val QUERY_DEBOUNCE_VALUE = 300L
        const val PAGE_SIZE = 100
        const val MAX_ITEMS_IN_MEMORY = 300
    }

    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    val navigationCallback = SingleLiveEvent<NavigationRequest>()

    var exploreTopic = ExploreTopic.Merchants
        private set
    private var filterJob: Job? = null
    private val searchQuery = MutableStateFlow("")

    private val _pickedTerritory = MutableStateFlow("")
    var pickedTerritory: String
        get() = _pickedTerritory.value
        set(value) {
            _pickedTerritory.value = value
        }

    private val _filterMode = MutableStateFlow(FilterMode.Online)
    val filterMode: LiveData<FilterMode>
        get() = _filterMode.asLiveData()

    private val _pagingSearchResults = MutableLiveData<PagingData<SearchResult>>()
    val pagingSearchResults: LiveData<PagingData<SearchResult>>
        get() = _pagingSearchResults

    private val _selectedMerchant = MutableLiveData<Merchant?>()
    val selectedMerchant: LiveData<Merchant?>
        get() = _selectedMerchant

    val merchantsSearchFilterFlow = searchQuery
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
                            merchantDao.observeSearchResults(sanitizeQuery(query), territory)
                        } else {
                            merchantDao.observe(territory)
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


    val atmsSearchFilterFlow = searchQuery
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
                            atmDao.observeSearchResults(sanitizeQuery(query), territory)
                        } else {
                            atmDao.observe(territory)
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

    fun init(exploreTopic: ExploreTopic) {
        if (this.exploreTopic != exploreTopic) {
            clearFilters(exploreTopic)
            _pagingSearchResults.value = PagingData.from(listOf())
        }

        this.exploreTopic = exploreTopic
        this.filterJob?.cancel(CancellationException())

        this.filterJob = if (exploreTopic == ExploreTopic.Merchants) {
            merchantsSearchFilterFlow
        } else {
            atmsSearchFilterFlow
        }.onEach(_pagingSearchResults::postValue)
            .launchIn(viewModelWorkerScope)
    }

    private var isSynced = false
    // TODO: replace with smart sync
    fun dumbSync() {
        if (!isSynced) {
            viewModelScope.launch {
                val merchants = try {
                    exploreRepository.getMerchants() ?: listOf()
                } catch (ex: Exception) {
                    Log.e("EXPLOREDASH", ex.message ?: "null msg")
                    listOf()
                }

                merchantDao.save(merchants)

                val atms = try {
                    exploreRepository.getAtms() ?: listOf()
                } catch (ex: Exception) {
                    Log.e("EXPLOREDASH", ex.message ?: "null msg")
                    listOf()
                }

                atmDao.save(atms)

                if (merchants.any() && atms.any()){
                    isSynced = true
                }
            }
        }
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
        _selectedMerchant.postValue(merchant)
    }

    fun openAtmDetails(atm: Atm) {
        Log.i("EXPLOREDASH", "Atm details")
    }

    fun openSearchResults() {
        _selectedMerchant.postValue(null)
    }

    fun sendDash() {
        navigationCallback.postValue(NavigationRequest.SendDash)
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

    enum class FilterMode {
        All, Online, Physical, Buy, Sell, BuySell
    }
}