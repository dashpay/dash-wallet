/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.app.Application
import android.content.Intent
import androidx.core.os.bundleOf
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.invite.AcceptInviteActivity
import de.schildbach.wallet_test.R
import org.bitcoinj.crypto.MnemonicException
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.services.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    application: Application,
    private val analytics: AnalyticsService
) : AndroidViewModel(application) {

    private val log = LoggerFactory.getLogger(OnboardingViewModel::class.java)

    private val walletApplication = application as WalletApplication

    internal val showToastAction = SingleLiveEvent<String>()
    internal val showRestoreWalletFailureAction = SingleLiveEvent<MnemonicException>()
    internal val startActivityAction = SingleLiveEvent<Intent>()

    val platformRepo by lazy {
        PlatformRepo.getInstance()
    }

    fun createNewWallet(onboardingInvite: InvitationLinkData?) {
        walletApplication.initEnvironmentIfNeeded()
        val wallet = Wallet(Constants.NETWORK_PARAMETERS)
        log.info("successfully created new wallet")
        walletApplication.wallet = wallet
        walletApplication.configuration.armBackupSeedReminder()

        if (onboardingInvite != null) {
            analytics.logEvent(AnalyticsConstants.Invites.NEW_WALLET, bundleOf())
            startActivityAction.call(AcceptInviteActivity.createIntent(getApplication(), onboardingInvite, true))
        } else {
            startActivityAction.call(SetPinActivity.createIntent(getApplication(), R.string.set_pin_create_new_wallet, onboarding = true))
        }
    }
}
