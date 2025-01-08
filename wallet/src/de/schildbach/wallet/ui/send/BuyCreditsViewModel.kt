package de.schildbach.wallet.ui.send

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.TopUpsDao
import de.schildbach.wallet.database.entity.BlockchainIdentityConfig
import de.schildbach.wallet.database.entity.TopUp
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.service.platform.work.TopupIdentityOperation
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@HiltViewModel
class BuyCreditsViewModel @Inject constructor(
    val walletApplication: WalletApplication,
    val platformRepo: PlatformRepo,
    val identity: BlockchainIdentityConfig,
    val walletDataProvider: WalletDataProvider,
    val analytics: AnalyticsService,
    val dashPayConfig: DashPayConfig,
    private val topUpsDao: TopUpsDao
) : ViewModel() {
    var identityId: String? = null
    var topUpTransaction: Transaction? = null
    private val _currentWorkId = MutableStateFlow("")
    val currentWorkId: StateFlow<String>
        get() = _currentWorkId

    private suspend fun getNextWorkId() = withContext(Dispatchers.IO) {
        dashPayConfig.getTopupCounter().toString(16)
    }

    private val topupIdentityOperation = TopupIdentityOperation(walletApplication)

    fun topWorkStatus(workId: String): LiveData<Resource<WorkInfo>> {
        return TopupIdentityOperation.operationStatus(walletApplication, workId, analytics)
    }

    suspend fun topUpOnPlatform() = withContext(Dispatchers.IO) {
        identity.get(BlockchainIdentityConfig.IDENTITY_ID)?.let { identityId ->
            val workId = getNextWorkId()
            topupIdentityOperation
                .create(workId, topUpTransaction?.txId!!)
                .enqueue()
            _currentWorkId.value = workId
        }
    }

    suspend fun getTransaction(txId: Sha256Hash?) = withContext(Dispatchers.IO) {
        walletDataProvider.wallet!!.getTransaction(txId)
    }
}