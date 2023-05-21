package de.schildbach.wallet.ui.username

import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.CoinJoinService
import org.dash.wallet.common.livedata.NetworkStateInt
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@HiltViewModel
open class CreateUsernamePrivacyViewModel @Inject constructor(
    private val analytics: AnalyticsService,
    private val coinJoinService: CoinJoinService,
    var networkState: NetworkStateInt,

) : ViewModel() {
    fun isWifiConnected(): Boolean {
        return networkState.isWifiConnected()
    }
    fun setCoinJoinMode(mode: CoinJoinMode) {
        coinJoinService.setMode(mode)
    }
    fun logEvent(event: String, params: Bundle = bundleOf()) {
        analytics.logEvent(event, params)
    }
}
