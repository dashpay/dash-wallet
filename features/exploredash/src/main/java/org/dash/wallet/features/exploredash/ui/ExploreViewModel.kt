package org.dash.wallet.features.exploredash.ui

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.features.exploredash.data.MerchantDao
import org.dash.wallet.features.exploredash.repository.MerchantRepository
import org.dash.wallet.features.exploredash.data.model.Merchant
import org.dash.wallet.features.exploredash.data.model.SearchResult
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val merchantRepository: MerchantRepository,
    private val merchantDao: MerchantDao
) : ViewModel() {
    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    val event = SingleLiveEvent<String>()

    private val searchQuery = MutableStateFlow("")

    private val _filterMode = MutableStateFlow(FilterMode.All)
    val filterMode: LiveData<FilterMode>
        get() = _filterMode.asLiveData()

    private val _searchResults = MutableLiveData(listOf<SearchResult>())
    val searchResults: LiveData<List<SearchResult>>
        get() = _searchResults

    fun initData() {
//        searchQuery.debounce(300)
//            .onEach {
//                val results = merchantRepository.search(it) ?: listOf()
//                val header = SearchResult("Alabama".hashCode(), true,"Alabama")
//                _searchResults.postValue(listOf(header) + results)
//            }
//            .launchIn(viewModelWorkerScope)


        merchantDao.observeAll()
            .filterNotNull()
            .flatMapLatest { merchants ->
                _filterMode
                    .map { filterByMode(merchants, it) }
                    .map(::groupByTerritory)
            }
            .onEach(_searchResults::postValue)
            .launchIn(viewModelWorkerScope)
    }

    // TODO: replace with smart sync
    fun dumbSync() {
        viewModelScope.launch {
            val merchants = try {
                merchantRepository.get() ?: listOf()
            } catch (ex: Exception) {
                event.postValue(ex.message) // TODO
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

    private fun filterByMode(merchants: List<Merchant>, mode: FilterMode): List<Merchant> {
        val filtered = if (mode == FilterMode.All) {
            merchants.filter { it.active != false }
        } else {
            merchants.filter {
                it.active != false && (it.type == "both" ||
                        it.type == mode.toString().toLowerCase(Locale.getDefault()))
            }
        }
        return filtered
    }

    private fun groupByTerritory(merchants: List<Merchant>): List<SearchResult> {
        return merchants.groupBy { it.territory }.flatMap { kv ->
            listOf(SearchResult(kv.key.hashCode(), true, kv.key)) + kv.value
        }
    }

    enum class FilterMode {
        All, Online, Physical
    }
}