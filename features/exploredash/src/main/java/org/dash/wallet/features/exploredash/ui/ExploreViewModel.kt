package org.dash.wallet.features.exploredash.ui

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.features.exploredash.repository.MerchantRepository
import org.dash.wallet.features.exploredash.repository.model.Merchant
import org.dash.wallet.features.exploredash.repository.model.SearchResult
import java.util.*
import javax.inject.Inject

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val merchantRepository: MerchantRepository
): ViewModel() {
    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)

    val event = SingleLiveEvent<String>()

    private val searchQuery = MutableStateFlow("")

    private val _filterMode = MutableLiveData(FilterMode.All)
    val filterMode: LiveData<FilterMode>
        get() = _filterMode

    private val _searchResults = MutableLiveData(listOf<SearchResult>())
    val searchResults: LiveData<List<SearchResult>>
        get() = _searchResults

    fun init() {
        viewModelScope.launch {
            val merchants = try {
                 merchantRepository.get() ?: listOf()
            } catch (ex: Exception) {
                event.postValue(ex.message) // TODO
                listOf()
            }

            filterMode.observeForever { mode ->
                val filtered = if (mode == FilterMode.All) {
                    merchants.filter { it.active != false }
                } else {
                    merchants.filter { it.active != false && (it.type == "both" ||
                            it.type == mode.toString().toLowerCase(Locale.getDefault())) }
                }

                val grouped = mutableListOf<SearchResult>()
                filtered.groupBy { it.territory }.forEach { kv ->
                    grouped.add(SearchResult(kv.key.hashCode(), true, kv.key))
                    kv.value.forEach { grouped.add(it) }
                }

                _searchResults.postValue(grouped)
            }
        }

//        searchQuery.debounce(300)
//            .onEach {
//                val results = merchantRepository.search(it) ?: listOf()
//                val header = SearchResult("Alabama".hashCode(), true,"Alabama")
//                _searchResults.postValue(listOf(header) + results)
//            }
//            .launchIn(viewModelWorkerScope)
    }

    fun setFilterMode(mode: FilterMode) {
        _filterMode.value = mode
    }

    fun submitSearchQuery(query: String) {
        searchQuery.value = query
    }

    enum class FilterMode {
        All, Online, Physical
    }
}