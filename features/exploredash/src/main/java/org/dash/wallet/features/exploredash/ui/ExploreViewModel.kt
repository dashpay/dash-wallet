package org.dash.wallet.features.exploredash.ui

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.features.exploredash.repository.MerchantRepository
import org.dash.wallet.features.exploredash.repository.model.Merchant
import javax.inject.Inject

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val merchantRepository: MerchantRepository
): ViewModel() {
    val event = SingleLiveEvent<String>()

    private val _searchResults = MutableLiveData(listOf<Merchant>())
    val searchResults: MutableLiveData<List<Merchant>>
        get() = _searchResults

    fun init() {
        viewModelScope.launch {
            val merchants = merchantRepository.get()
            _searchResults.postValue(merchants)
        }
    }
}