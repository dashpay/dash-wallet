package org.dash.wallet.common.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.dash.wallet.common.livedata.ConnectionLiveData

class ConnectivityViewModel(application: Application) : AndroidViewModel(application) {
    val connectivityLiveData = ConnectionLiveData(application)
}