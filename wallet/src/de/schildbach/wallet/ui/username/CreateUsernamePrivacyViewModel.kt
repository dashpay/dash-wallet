package de.schildbach.wallet.ui.username

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.CoinJoinService
import de.schildbach.wallet.service.MixingStatus
import org.dash.wallet.common.services.NetworkStateInt
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@HiltViewModel
open class CreateUsernamePrivacyViewModel @Inject constructor(
    private val analytics: AnalyticsService,
    private val coinJoinService: CoinJoinService,
    private var networkState: NetworkStateInt
) : ViewModel() {

    val isMixing: Boolean
        get() = coinJoinService.mixingStatus == MixingStatus.MIXING ||
            coinJoinService.mixingStatus == MixingStatus.PAUSED

    var mixingMode: CoinJoinMode
        get() = coinJoinService.mode
        set(value) {
            coinJoinService.mode = value
//            coinJoinService.prepareAndStartMixing() TODO restart mixing?
        }

    fun isWifiConnected(): Boolean {
        return networkState.isWifiConnected()
    }

    suspend fun startMixing(mode: CoinJoinMode) {
        analytics.logEvent(
            AnalyticsConstants.CoinJoinPrivacy.USERNAME_PRIVACY_BTN_CONTINUE,
            mapOf(AnalyticsConstants.Parameter.VALUE to mode.name)
        )

        coinJoinService.mode = mode
        coinJoinService.prepareAndStartMixing() // TODO: change the logic if needed
    }

    suspend fun stopMixing() {
//        coinJoinService.stopMixing() // TODO expose stop method?
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, mapOf())
    }
}
