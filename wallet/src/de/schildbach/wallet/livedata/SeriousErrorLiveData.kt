package de.schildbach.wallet.livedata

import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.ui.dashpay.PlatformRepo

enum class SeriousError {
    MissingEncryptionIV
}

interface SeriousErrorListener {
    fun onSeriousError(error: Resource<SeriousError>)
}

class SeriousErrorLiveData(val platformRepo: PlatformRepo) : MutableLiveData<Resource<SeriousError>>(), SeriousErrorListener {
    fun setError(error: SeriousError) {
        value = Resource.success(error)
    }

    override fun onSeriousError(error: Resource<SeriousError>) {
        postValue(error)
    }

    private var listening = false

    override fun onActive() {
        maybeAddEventListener()
    }

    override fun onInactive() {
        maybeRemoveEventListener()
    }

    private fun maybeAddEventListener() {
        if (!listening && hasActiveObservers()) {
            platformRepo.addSeriousErrorListener(this)
            listening = true
        }
    }

    private fun maybeRemoveEventListener() {
        if (listening) {
            platformRepo.removeSeriousErrorListener(this)
            listening = false
        }
    }
}