package de.schildbach.wallet.livedata

import android.os.Looper
import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.service.BlockchainState


/**
 * @author Samuel Barbosa
 */
object BlockchainStateRepository {

    private fun isMainThread(): Boolean {
        return Looper.myLooper() === Looper.getMainLooper()
    }

    var blockchainState: BlockchainState? = null
        set(value) {
            field = value
            if (isMainThread()) {
                blockchainStateLiveData.value = value
            } else {
                blockchainStateLiveData.postValue(value)
            }
        }
    val blockchainStateLiveData: MutableLiveData<BlockchainState> = MutableLiveData()

}