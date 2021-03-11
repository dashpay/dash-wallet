package de.schildbach.wallet.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.liveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.dynamiclinks.FirebaseDynamicLinks
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
import org.slf4j.LoggerFactory

class MainActivityViewModel(application: Application) : BaseProfileViewModel(application) {

    companion object {
        private val log = LoggerFactory.getLogger(MainActivityViewModel::class.java)
    }

    private val isPlatformAvailableData = liveData(Dispatchers.IO) {
        val status = platformRepo.isPlatformAvailable()
        if (status.status == Status.SUCCESS && status.data != null) {
            emit(status.data)
        } else {
            emit(false)
        }
    }

    val inviteData = MutableLiveData<Resource<Pair<Uri, Boolean>>>()

    fun handleInvite(intent: Intent) {
        FirebaseDynamicLinks.getInstance().getDynamicLink(intent).addOnSuccessListener {
            val link = it?.link
            if (link != null && Constants.Invitation.isValid(link)) {
                log.debug("received invite $link")

                dashPayProfile?.username.apply {
                    if (this == link.getQueryParameter("user")) {
                        inviteData.postValue(Resource.canceled(Pair(link, false)))
                        return@addOnSuccessListener
                    }
                }

                inviteData.value = Resource.loading()
                val cftx = link.getQueryParameter("cftx")!!
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val isValid = platformRepo.validateInvitation(cftx)
                        inviteData.postValue(Resource.success(Pair(link, isValid)))
                    } catch (e: Exception) {
                        inviteData.postValue(Resource.error(e, Pair(link, false)))
                    }
                }
            } else {
                log.debug("invalid invite ignored")
            }
        }
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