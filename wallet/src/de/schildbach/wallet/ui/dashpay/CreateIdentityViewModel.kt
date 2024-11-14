package de.schildbach.wallet.ui.dashpay

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@HiltViewModel
class CreateIdentityViewModel @Inject constructor(
    private val walletApplication: WalletApplication,
    private val analytics: AnalyticsService,
    blockchainIdentityDataDao: BlockchainIdentityConfig,
    dashPayProfileDao: DashPayProfileDao,
    val dashPayConfig: DashPayConfig
) : BaseProfileViewModel(blockchainIdentityDataDao, dashPayProfileDao) {

    private val _creationState = MutableStateFlow(BlockchainIdentityData.CreationState.NONE)
    val creationState: StateFlow<BlockchainIdentityData.CreationState>
        get() = _creationState

    private val _creationException = MutableStateFlow<String?>(null)
    val creationException: StateFlow<String?>
        get() = _creationException
    init {
        blockchainIdentityDataDao.observe(BlockchainIdentityConfig.CREATION_STATE)
            .filterNotNull()
            .onEach {
                _creationState.value = BlockchainIdentityData.CreationState.valueOf(it)
            }
            .launchIn(viewModelScope)

        blockchainIdentityDataDao.observe(BlockchainIdentityConfig.CREATION_STATE_ERROR_MESSAGE)
            .filterNotNull()
            .onEach {
                _creationException.value = it
            }
            .launchIn(viewModelScope)
    }

    fun retryCreateIdentity() {
        viewModelScope.launch {
            val username = blockchainIdentityDataDao.get(BlockchainIdentityConfig.USERNAME)
            walletApplication.startService(
                CreateIdentityService.createIntentForRetry(
                    walletApplication
                )
            )
        }
    }

    suspend fun shouldShowMixDash(): Boolean = dashPayConfig.get(DashPayConfig.MIX_DASH_SHOWN)?.not() ?: true
}