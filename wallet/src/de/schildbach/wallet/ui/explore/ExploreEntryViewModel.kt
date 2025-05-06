package de.schildbach.wallet.ui.explore

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.NetworkParameters
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeApi
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class ExploreEntryViewModel @Inject constructor(
    private val exploreConfig: ExploreConfig,
    private val crowdNodeApi: CrowdNodeApi,
    private val analytics: AnalyticsService,
    private val blockchainStateProvider: BlockchainStateProvider,
    private val walletData: WalletDataProvider
): ViewModel() {
    companion object {
        private val log = LoggerFactory.getLogger(ExploreEntryViewModel::class.java)
    }

    private val _stakingAPY = MutableLiveData<Double>()
    val stakingAPY: LiveData<Double>
        get() = _stakingAPY

    private val _isBlockchainSynced = MutableLiveData<Boolean>()
    val isBlockchainSynced: LiveData<Boolean>
        get() = _isBlockchainSynced

    init {
        blockchainStateProvider.observeState()
            .filterNotNull()
            .onEach { state ->
                updateSyncStatus(state)
            }
            .launchIn(viewModelScope)
    }

    fun getLastStakingAPY() {
        viewModelScope.launch(Dispatchers.IO) {
            val withoutFees = (100.0 - crowdNodeApi.getFee()) / 100
            log.info("fees: without $withoutFees")
            _stakingAPY.postValue(withoutFees * blockchainStateProvider.getLastMasternodeAPY())
        }
    }

    suspend fun isInfoShown(): Boolean =
        exploreConfig.get(ExploreConfig.HAS_INFO_SCREEN_BEEN_SHOWN) ?: false

    fun setIsInfoShown(isShown: Boolean) {
        viewModelScope.launch {
            exploreConfig.set(ExploreConfig.HAS_INFO_SCREEN_BEEN_SHOWN, isShown)
        }
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }

    fun isTestNet(): Boolean {
        return walletData.wallet?.params?.id != NetworkParameters.ID_MAINNET
    }

    private suspend fun updateSyncStatus(state: BlockchainState) {
        if (_isBlockchainSynced.value != state.isSynced()) {
            _isBlockchainSynced.value = state.isSynced()

            if (state.isSynced()) {
                val withoutFees = (100.0 - crowdNodeApi.getFee()) / 100
                _stakingAPY.postValue(withoutFees * blockchainStateProvider.getMasternodeAPY())
            }
        }
    }
}