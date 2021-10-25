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
import org.dash.wallet.features.exploredash.data.model.MerchantType
import org.dash.wallet.features.exploredash.data.model.SearchResult
import javax.inject.Inject

enum class NavigationRequest {
    SendDash, None
}

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

    val navigationCallback = SingleLiveEvent<NavigationRequest>()

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

    private val _selectedMerchant = MutableLiveData<Merchant?>()
    val selectedMerchant: LiveData<Merchant?>
        get() = _selectedMerchant

    val searchFilterFlow = searchQuery
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
        _selectedMerchant.postValue(merchant)
    }

    fun openSearchResults() {
        _selectedMerchant.postValue(null)
    }

    fun sendDash() {
        navigationCallback.postValue(NavigationRequest.SendDash)
    }

    private fun filterByMode(merchants: List<Merchant>, mode: FilterMode): List<Merchant> {
        return if (mode == FilterMode.All) {
            // Showing all merchants
            merchants.filter { it.active != false }
        } else {
            // Showing merchants of specific type or both types
            merchants.filter {
                it.active != false && (it.type == MerchantType.BOTH ||
                        it.type == mode.toString().lowercase())
            }
        }
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