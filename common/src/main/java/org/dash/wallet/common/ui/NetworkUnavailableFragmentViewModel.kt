package org.dash.wallet.common.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import org.dash.wallet.common.data.SingleLiveEvent

class NetworkUnavailableFragmentViewModel(application: Application) : AndroidViewModel(application) {
    val clickButton = SingleLiveEvent<Unit>()
}