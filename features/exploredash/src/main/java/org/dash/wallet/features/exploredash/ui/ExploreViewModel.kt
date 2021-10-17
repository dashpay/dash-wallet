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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.features.exploredash.data.MerchantDao
import org.dash.wallet.features.exploredash.repository.MerchantRepository
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.SearchResult
import javax.inject.Inject

@ExperimentalCoroutinesApi
@FlowPreview
@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val merchantRepository: MerchantRepository,
    private val merchantDao: MerchantDao,
    private val locationUpdatesUseCase: UserLocationState
) : ViewModel() {
    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    val event = SingleLiveEvent<String>()

    private val searchQuery = MutableStateFlow("")

    private var currentUserLocationState = MutableStateFlow(UserLocation(0.0, 0.0))
    val observeCurrentUserLocation = currentUserLocationState.asLiveData()

    private val _pickedTerritory = MutableStateFlow("")
    var pickedTerritory: String
        get() = _pickedTerritory.value
        set(value) {
            _pickedTerritory.value = value
        }

    private val _filterMode = MutableStateFlow(FilterMode.Online)
    val filterMode: LiveData<FilterMode>
        get() = _filterMode.asLiveData()

    private val _searchResults = MutableLiveData<List<SearchResult>>()
    val searchResults: LiveData<List<SearchResult>>
        get() = _searchResults

    val searchFilterFlow = searchQuery
        .debounce(300)
        .flatMapLatest { query ->
            _pickedTerritory
                .flatMapLatest { territory ->
                    if (query.isNotBlank()) {
                        val qq = sanitizeQuery(query)
                        merchantDao.observeSearchResults(qq, territory)
                    } else {
                        merchantDao.observe(territory)
                    }.filterNotNull()
                        .flatMapLatest { merchants ->
                            _filterMode
                                .map { filterByMode(merchants, it) }
                                .map(::groupByTerritory)
                        }
                }
        }

    fun init() {
        searchFilterFlow
            .onEach(_searchResults::postValue)
            .launchIn(viewModelWorkerScope)
    }

    // TODO: replace with smart sync
    fun dumbSync() {
        viewModelScope.launch {
            val merchants = try {
                merchantRepository.get() ?: listOf()
            } catch (ex: Exception) {
                event.postValue(ex.message)
                listOf()
            }

            merchantDao.save(merchants)
        }
    }

    fun setFilterMode(mode: FilterMode) {
        _filterMode.value = mode
    }

    fun submitSearchQuery(query: String) {
        searchQuery.value = query
    }

    suspend fun getTerritoriesWithMerchants(): List<String> {
        return merchantDao.getTerritories().filter { it.isNotEmpty() }
    }

    fun openMerchantDetails(merchant: Merchant) {
        // TODO details
        event.postValue("${merchant.name}: ${merchant.address4}")
    }

    private fun filterByMode(merchants: List<Merchant>, mode: FilterMode): List<Merchant> {
        val filtered = if (mode == FilterMode.All) {
            merchants.filter { it.active != false }
        } else {
            merchants.filter {
                it.active != false && (it.type == "both" ||
                        it.type == mode.toString().lowercase())
            }
        }
        return filtered
    }

    private fun groupByTerritory(merchants: List<Merchant>): List<SearchResult> {
        return merchants
            .groupBy { it.territory ?: "" }
            .toSortedMap()
            .flatMap { kv ->
                listOf(SearchResult(kv.key.hashCode(), true, kv.key)) + kv.value.sortedBy { it.name }
            }
    }

    private fun sanitizeQuery(query: String): String {
        val escapedQuotes = query.replace(Regex.fromLiteral("\""), "\"\"")
        return "\"$escapedQuotes*\""
    }

    enum class FilterMode {
        All, Online, Physical
    }

    fun monitorUserLocation() {
        viewModelScope.launch {
            locationUpdatesUseCase.fetchUpdates().collect {
                currentUserLocationState.value = it
            }
        }
    }

}