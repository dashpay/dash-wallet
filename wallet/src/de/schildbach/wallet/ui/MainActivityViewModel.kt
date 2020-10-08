package de.schildbach.wallet.ui

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.liveData
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainIdentityBaseData
import de.schildbach.wallet.data.BlockchainIdentityData
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.CanAffordIdentityCreationLiveData
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.Dispatchers

class MainActivityViewModel(application: Application) : AndroidViewModel(application) {

    private val walletApplication = application as WalletApplication
    private val platformRepo = PlatformRepo.getInstance()

    private val isPlatformAvailableData = liveData(Dispatchers.IO) {
        val status = platformRepo.isPlatformAvailable()
        if (status.status == Status.SUCCESS && status.data != null) {
            emit(status.data)
        } else {
            emit(false)
        }
    }

    val blockchainStateData = AppDatabase.getAppDatabase().blockchainStateDao().load()
    val blockchainState: BlockchainState?
        get() = blockchainStateData.value

    val blockchainIdentityData = AppDatabase.getAppDatabase().blockchainIdentityDataDaoAsync().loadBase()
    val blockchainIdentity: BlockchainIdentityBaseData?
        get() = blockchainIdentityData.value

    val canAffordIdentityCreationLiveData = CanAffordIdentityCreationLiveData(walletApplication)

    val hasIdentity: Boolean
        get() = blockchainIdentity?.creationComplete ?: false

    val isAbleToCreateIdentityData = MediatorLiveData<Boolean>().apply {
        addSource(isPlatformAvailableData) {
            value = combineLatestData()
        }
        addSource(blockchainStateData) {
            value = combineLatestData()
        }
        addSource(blockchainIdentityData) {
            value = combineLatestData()
        }
        addSource(canAffordIdentityCreationLiveData) {
            value = combineLatestData()
        }
    }
    val isAbleToCreateIdentity: Boolean
        get() = isAbleToCreateIdentityData.value ?: false

    private fun combineLatestData(): Boolean {
        val isPlatformAvailable = isPlatformAvailableData.value ?: false
        val isSynced = blockchainStateData.value?.isSynced() ?: false
        val noIdentityCreatedOrInProgress = (blockchainIdentityData.value == null) || blockchainIdentityData.value!!.creationState == BlockchainIdentityData.CreationState.NONE
        val canAffordIdentityCreation = canAffordIdentityCreationLiveData.value ?: false
        return isSynced && isPlatformAvailable && noIdentityCreatedOrInProgress && canAffordIdentityCreation
    }

    val goBackAndStartActivityEvent = SingleLiveEvent<Class<*>>()
}