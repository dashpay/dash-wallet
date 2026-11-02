package de.schildbach.wallet.ui.explore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.bitcoinj.core.NetworkParameters
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.features.exploredash.utils.ExploreConfig
import javax.inject.Inject

@HiltViewModel
class ExploreEntryViewModel @Inject constructor(
    private val exploreConfig: ExploreConfig,
    private val walletData: WalletDataProvider
): ViewModel() {
    suspend fun isInfoShown(): Boolean =
        exploreConfig.get(ExploreConfig.HAS_INFO_SCREEN_BEEN_SHOWN) ?: false

    fun setIsInfoShown(isShown: Boolean) {
        viewModelScope.launch {
            exploreConfig.set(ExploreConfig.HAS_INFO_SCREEN_BEEN_SHOWN, isShown)
        }
    }

    fun isTestNet(): Boolean {
        return walletData.wallet?.params?.id != NetworkParameters.ID_MAINNET
    }
}
