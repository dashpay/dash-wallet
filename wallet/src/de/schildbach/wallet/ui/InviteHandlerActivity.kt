/*
 * Copyright 2021 Dash Core Group
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

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.ui.invite.InviteHandler
import org.dash.wallet.common.data.OnboardingState
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import javax.inject.Inject

@AndroidEntryPoint
class InviteHandlerActivity : AppCompatActivity() {

    private val log = LoggerFactory.getLogger(InviteHandlerActivity::class.java)

    companion object {

        private const val EXTRA_INVITE = "extra_invite"
        private const val EXTRA_SILENT_MODE = "extra_silent_mode"

        @JvmStatic
        fun createIntent(context: Context, invite: InvitationLinkData, silentMode: Boolean): Intent {
            return Intent(context, InviteHandlerActivity::class.java).apply {
                putExtra(EXTRA_INVITE, invite)
                putExtra(EXTRA_SILENT_MODE, silentMode)
            }
        }
    }

    private val viewModel: InviteHandlerViewModel by viewModels()
    @Inject
    lateinit var analytics: AnalyticsService

    private val inviteHandler by lazy {
        InviteHandler(this, analytics)
    }

    private val externalInvite by lazy {
        intent.getParcelableExtra<InvitationLinkData>(EXTRA_INVITE)
    }

    private val externalSilentMode by lazy {
        intent.getBooleanExtra(EXTRA_SILENT_MODE, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (onboardingInProgress()) {
            log.info("ignoring invite since onboarding is in progress")
            inviteHandler.showInviteWhileOnboardingInProgressDialog()
            return
        }

        initViewModel()
        val invite = externalInvite

        if (invite != null) {
            viewModel.handleInvite(invite)
        } else {
            viewModel.handleInvite(intent)
        }
    }

    private fun onboardingInProgress(): Boolean {
        val walletApplication = application as WalletApplication
        OnboardingState.init(walletApplication.configuration)
        return walletApplication.wallet != null && OnboardingState.isOnboarding()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        viewModel.handleInvite(getIntent())
    }

    private fun initViewModel() {
        viewModel.blockchainIdentityData.observe(this, {
            // dummy observer, just to force viewModel.blockchainIdentityData to be loaded
        })
        viewModel.inviteData.observe(this, {
            inviteHandler.handle(it, externalSilentMode)
        })
    }
}
