package org.dash.wallet.integrations.maya.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import org.dash.wallet.common.integrations.ExchangeIntegrationProvider
import org.dash.wallet.common.ui.address_input.AddressSource
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MayaAddressInputViewModel @Inject constructor(
    exchangeIntegrationProvider: ExchangeIntegrationProvider
) : ViewModel() {
    private val inputCurrency = MutableStateFlow<String?>(null)
    private val _addressSources = MutableStateFlow(listOf<AddressSource>())
    val addressSources: Flow<List<AddressSource>>
        get() = _addressSources

    init {

//        inputCurrency
//            .filterNotNull()
//            .flatMapLatest(exchangeIntegrationProvider::observeDepositAddresses)
//            .onEach { a -> print(a) }
//            .onEach {
//                val sources = it.map {
//                        integration ->
//                    AddressSource(
//                        integration.id,
//                        integration.name,
//                        integration.iconId,
//                        integration.address,
//                        integration.currency
//                    )
//                }
//                _addressSources.value = sources
//            }
//            .launchIn(viewModelScope)

        inputCurrency
            .filterNotNull()
            .mapLatest(exchangeIntegrationProvider::getDepositAddresses)
            .onEach {
                val sources = it.map {
                        integration ->
                    AddressSource(
                        integration.id,
                        integration.name,
                        integration.iconId,
                        integration.address,
                        integration.currency
                    )
                }
                _addressSources.value = sources
            }
            .launchIn(viewModelScope)
        // exchangeIntegrationProvider.observeDepositAddresses()
//            .onEach {
//                val sources = it.map {
//                        integration ->
//                    AddressSource(integration.id, integration.name,
//                        integration.iconId, integration.address, integration.currency)
//                }
//
//                _addressSources.value = sources
//            }
//            .launchIn(viewModelScope)
    }

    fun setCurrency(currency: String) {
        inputCurrency.value = currency
    }
}
