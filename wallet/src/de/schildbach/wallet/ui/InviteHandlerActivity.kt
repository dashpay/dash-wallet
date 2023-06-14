/*
 * Copyright 2021 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
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
        viewModel.blockchainIdentity.observe(this) {
            // TODO: check if needed
            // dummy observer, just to force viewModel.blockchainIdentityData to be loaded
        }
        viewModel.inviteData.observe(this) {
            inviteHandler.handle(it, externalSilentMode)
        }
    }
}
