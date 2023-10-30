package de.schildbach.wallet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.service.CoinJoinMode
import de.schildbach.wallet.service.CoinJoinService
import de.schildbach.wallet.service.MixingStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.WalletEx
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.WalletUIConfig
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val walletUIConfig: WalletUIConfig,
    private val coinJoinConfig: CoinJoinConfig,
    private val coinJoinService: CoinJoinService,
    private val walletDataProvider: WalletDataProvider
): ViewModel() {
    val voteDashPayIsEnabled = walletUIConfig.observe(WalletUIConfig.VOTE_DASH_PAY_ENABLED)
    val coinJoinMixingMode: Flow<CoinJoinMode>
        get() = coinJoinConfig.observeMode()

    val coinJoinMixingStatus: MixingStatus
        get() = coinJoinService.mixingStatus

    val walletBalance: String
        get() = dashFormat.format(walletDataProvider.wallet!!.getBalance(Wallet.BalanceType.ESTIMATED)).toString()

    val mixedBalance: String
        get() = dashFormat.format((walletDataProvider.wallet as WalletEx).coinJoinBalance).toString()

    val mixingPercent: String
        get() {
            val wallet = walletDataProvider.wallet as WalletEx
            val mixedBalance = wallet.coinJoinBalance
            val anonymizableBalance = wallet.getAnonymizableBalance(false, false)
            return if (mixedBalance.value + anonymizableBalance.value != 0L) {
                return "${mixedBalance.value * 100 / (mixedBalance.value + anonymizableBalance.value)}%"
            } else {
                return "N/A"
            }
        }

    private val dashFormat = MonetaryFormat.BTC.noCode().minDecimals(2)

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
