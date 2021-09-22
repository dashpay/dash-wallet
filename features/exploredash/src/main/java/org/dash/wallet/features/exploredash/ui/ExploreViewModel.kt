package org.dash.wallet.features.exploredash.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.features.exploredash.repository.MerchantRepository
import javax.inject.Inject

@HiltViewModel
class ExploreViewModel @Inject constructor(
    private val merchantRepository: MerchantRepository
): ViewModel() {
    val event = SingleLiveEvent<String>()

    init {
        viewModelScope.launch {
            val merchants = merchantRepository.get()
            merchants?.forEach { Log.i("MERCHANTS", it.name ?: "null name") }
        }
    }
}