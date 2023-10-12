package de.schildbach.wallet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.service.CoinJoinMixingService
import de.schildbach.wallet.service.MixingStatus
import kotlinx.coroutines.launch
import org.dash.wallet.common.data.WalletUIConfig
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val walletUIConfig: WalletUIConfig,
    private val coinJoinConfig: CoinJoinConfig,
    private val coinJoinService: CoinJoinMixingService
): ViewModel() {
    val voteDashPayIsEnabled = walletUIConfig.observe(WalletUIConfig.VOTE_DASH_PAY_ENABLED)
    val coinJoinMixingStatus: MixingStatus
        get() = coinJoinService.mixingStatus

    fun setVoteDashPay(isEnabled: Boolean) {
        viewModelScope.launch {
            walletUIConfig.set(WalletUIConfig.VOTE_DASH_PAY_ENABLED, isEnabled)
        }
    }

    suspend fun shouldShowCoinJoinInfo(): Boolean {
        return coinJoinConfig.get(CoinJoinConfig.FIRST_TIME_INFO_SHOWN) != true
    }

    suspend fun setCoinJoinInfoShown() {
        coinJoinConfig.set(CoinJoinConfig.FIRST_TIME_INFO_SHOWN, true)
    }
}
