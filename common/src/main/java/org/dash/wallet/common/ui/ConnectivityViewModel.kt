package org.dash.wallet.common.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.dash.wallet.common.livedata.ConnectionLiveData

open class ConnectivityViewModel(application: Application) : AndroidViewModel(application) {
    val connectivityLiveData = ConnectionLiveData(application)
    val isConnected
        get() = connectivityLiveData.value?: false
}