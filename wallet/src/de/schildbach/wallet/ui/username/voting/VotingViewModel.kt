
package de.schildbach.wallet.ui.username.voting

import androidx.lifecycle.ViewModel
import javax.inject.Inject

import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.database.dao.UsernameRequestDao
import de.schildbach.wallet.service.platform.PlatformSyncService
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import kotlin.OptIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.WalletDataProvider

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
public class VotingViewModel @Inject constructor(
    private val dashPayConfig:DashPayConfig,
    private val usernameRequestDao:UsernameRequestDao,
    private val platformSyncService: PlatformSyncService,
    private val walletDataProvider: WalletDataProvider
): ViewModel() {

}