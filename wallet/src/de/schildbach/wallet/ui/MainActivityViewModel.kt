package de.schildbach.wallet.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.liveData
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.data.BlockchainIdentityBaseData
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.Dispatchers

class MainActivityViewModel(application: Application) : AndroidViewModel(application) {

    private val platformRepo = PlatformRepo.getInstance()

    val isPlatformAvailableData = liveData(Dispatchers.IO) {
        val status = platformRepo.isPlatformAvailable()
        if (status.status == Status.SUCCESS && status.data != null) {
            emit(status.data)
        } else {
            emit(false)
        }
    }
    val isPlatformAvailable: Boolean
        get() = isPlatformAvailableData.value ?: false

    val blockchainStateData = AppDatabase.getAppDatabase().blockchainStateDao().load()
    val blockchainState: BlockchainState?
        get() = blockchainStateData.value

    val blockchainIdentityData = AppDatabase.getAppDatabase().blockchainIdentityDataDaoAsync().loadBase()
    val blockchainIdentity: BlockchainIdentityBaseData?
        get() = blockchainIdentityData.value
}