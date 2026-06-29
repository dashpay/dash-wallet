package de.schildbach.wallet.ui.explore

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.asLiveData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.bitcoinj.core.NetworkParameters
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.entity.BlockchainState
import org.dash.wallet.common.services.BlockchainStateProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import org.dash.wallet.integrations.crowdnode.api.CrowdNodeApi
import org.dash.wallet.integrations.crowdnode.model.SignUpStatus
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

    // CrowdNode functionality is limited: the staking entry point is only shown
    // if the active wallet is already associated with a CrowdNode account
    val hasCrowdNodeAccount: LiveData<Boolean> = crowdNodeApi.signUpStatus
        .map { it != SignUpStatus.NotStarted }
        .asLiveData()

    // Persistent banner: shown whenever the wallet still has a CrowdNode balance to withdraw.
    val showWithdrawalBanner: LiveData<Boolean> = combine(
        crowdNodeApi.signUpStatus,
        crowdNodeApi.balance
    ) { status, balance ->
        status != SignUpStatus.NotStarted && (balance.data?.isPositive == true)
    }.asLiveData()

    init {
        blockchainStateProvider.observeState()
            .filterNotNull()
            .onEach { state ->
                updateSyncStatus(state)
            }
            .launchIn(viewModelScope)

        viewModelScope.launch(Dispatchers.IO) {
            crowdNodeApi.restoreStatus()
        }
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
                // the sign up status might be restorable from the blockchain now.
                // restoreStatus() scans the entire wallet history and acquires the
                // wallet keychain lock, so it must not run on the main thread.
                withContext(Dispatchers.IO) {
                    crowdNodeApi.restoreStatus()
                    val withoutFees = (100.0 - crowdNodeApi.getFee()) / 100
                    _stakingAPY.postValue(withoutFees * blockchainStateProvider.getMasternodeAPY())
                }
            }
        }
    }
}