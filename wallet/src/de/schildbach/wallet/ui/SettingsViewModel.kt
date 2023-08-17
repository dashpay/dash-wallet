package de.schildbach.wallet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletUIConfig
import kotlinx.coroutines.launch
import javax.inject.Inject



@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val walletUIConfig: WalletUIConfig
): ViewModel() {
    val voteDashPayIsEnabled = walletUIConfig.observePreference(WalletUIConfig.VOTE_DASH_PAY_ENABLED).asLiveData()

    fun setVoteDashPay(isEnabled: Boolean) {
        viewModelScope.launch {
            walletUIConfig.setPreference(WalletUIConfig.VOTE_DASH_PAY_ENABLED, isEnabled)
        }
    }
}
