package de.schildbach.wallet.ui.invite

import android.app.Application
import androidx.lifecycle.MediatorLiveData
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.data.BlockchainIdentityData
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet.ui.dashpay.BaseProfileViewModel
import de.schildbach.wallet.ui.dashpay.CanAffordIdentityCreationLiveData

class CreateInviteViewModel(application: Application) : BaseProfileViewModel(application){

    val blockchainStateData = AppDatabase.getAppDatabase().blockchainStateDao().load()
    val blockchainState: BlockchainState?
        get() = blockchainStateData.value

    val canAffordIdentityCreationLiveData = CanAffordIdentityCreationLiveData(walletApplication)

    val isAbleToCreateInviteLiveData = MediatorLiveData<Boolean>().apply {
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
    val isAbleToCreateInvite: Boolean
        get() = isAbleToCreateInviteLiveData.value ?: false

    private fun combineLatestData(): Boolean {
        val isSynced = blockchainStateData.value?.isSynced() ?: false
        val noIdentityCreatedOrInProgress = (blockchainIdentityData.value == null) || blockchainIdentityData.value!!.creationState == BlockchainIdentityData.CreationState.NONE
        val canAffordIdentityCreation = canAffordIdentityCreationLiveData.value ?: false
        return isSynced && !noIdentityCreatedOrInProgress && canAffordIdentityCreation
    }

    val invitationsLiveData = AppDatabase.getAppDatabase().invitationsDaoAsync().loadAll()

    val invitationCount: Int
        get() = invitationsLiveData.value?.size ?: 0

    val isAbleToPerformInviteAction = MediatorLiveData<Boolean>().apply {
        addSource(isAbleToCreateInviteLiveData) {
            value = combineLatestInviteActionData()
        }
        addSource(invitationsLiveData) {
            value = combineLatestInviteActionData()
        }
    }

    private fun combineLatestInviteActionData(): Boolean {
        val hasInviteHistory = invitationCount > 0
        val canAffordInviteCreation = isAbleToCreateInvite
        return hasInviteHistory || canAffordInviteCreation
    }
}