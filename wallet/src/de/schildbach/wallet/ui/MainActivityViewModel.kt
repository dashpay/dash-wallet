package de.schildbach.wallet.ui

import android.app.Application
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.BlockchainIdentityData
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.CanAffordIdentityCreationLiveData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bitcoinj.evolution.CreditFundingTransaction

class MainActivityViewModel(application: Application) : BaseProfileViewModel(application) {

    private val isPlatformAvailableData = liveData(Dispatchers.IO) {
        val status = platformRepo.isPlatformAvailable()
        if (status.status == Status.SUCCESS && status.data != null) {
            emit(status.data)
        } else {
            emit(false)
        }
    }

    fun validateInvitation(txId: String): MutableLiveData<Resource<Boolean>> {
        val liveData = MutableLiveData<Resource<Boolean>>()
        liveData.postValue(Resource.loading())
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val isValid = platformRepo.validateInvitation(txId)
                liveData.postValue(Resource.success(isValid))
            } catch (e: Exception) {
                liveData.postValue(Resource.error(e))
            }
        }
        return liveData
    }

    val blockchainStateData = AppDatabase.getAppDatabase().blockchainStateDao().load()
    val blockchainState: BlockchainState?
        get() = blockchainStateData.value

    val canAffordIdentityCreationLiveData = CanAffordIdentityCreationLiveData(walletApplication)

    val isAbleToCreateIdentityLiveData = MediatorLiveData<Boolean>().apply {
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
        get() = isAbleToCreateIdentityLiveData.value ?: false

    private fun combineLatestData(): Boolean {
        val isPlatformAvailable = isPlatformAvailableData.value ?: false
        val isSynced = blockchainStateData.value?.isSynced() ?: false
        val noIdentityCreatedOrInProgress = (blockchainIdentityData.value == null) || blockchainIdentityData.value!!.creationState == BlockchainIdentityData.CreationState.NONE
        val canAffordIdentityCreation = canAffordIdentityCreationLiveData.value ?: false
        return isSynced && isPlatformAvailable && noIdentityCreatedOrInProgress && canAffordIdentityCreation
    }

    val goBackAndStartActivityEvent = SingleLiveEvent<Class<*>>()
    val showCreateUsernameEvent = SingleLiveEventExt<Unit>()
}