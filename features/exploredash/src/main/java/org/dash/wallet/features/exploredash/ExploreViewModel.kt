package org.dash.wallet.features.exploredash

import androidx.lifecycle.ViewModel
import org.dash.wallet.common.data.SingleLiveEvent

class ExploreViewModel: ViewModel() {
    val event = SingleLiveEvent<String>()
}