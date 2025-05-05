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

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.ui.invite.InviteHandler
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet_test.databinding.ActivityTransparentBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.OnboardingState
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import javax.inject.Inject

/** forward invitation to MainActivity if there is a wallet, otherwise Onboarding.
 * If onboarding is underway, then only save the invitation
 */

@AndroidEntryPoint
class InviteHandlerActivity : AppCompatActivity() {
    companion object {
        private val log = LoggerFactory.getLogger(InviteHandlerActivity::class.java)
    }

    private lateinit var binding: ActivityTransparentBinding
    private val viewModel: InviteHandlerViewModel by viewModels()
    @Inject
    lateinit var analytics: AnalyticsService
    @Inject
    lateinit var walletDataProvider: WalletDataProvider
    @Inject
    lateinit var configuration: Configuration

    private val inviteHandler by lazy {
        InviteHandler(this, analytics)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransparentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        handleInvite(intent)
    }

    private fun onboardingInProgress(): Boolean {
        OnboardingState.init(configuration)
        return OnboardingState.isOnboarding()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleInvite(intent)
    }

    private fun handleInvite(intent: Intent?) {
        if (intent != null) {
            lifecycleScope.launch {
                viewModel.handleInvite(intent)?.let { invitation ->
                    handleInvite(invitation)
                }
            }
        } else {
            finish()
        }
    }

    private fun handleInvite(invite: InvitationLinkData) {
        val mainTask = inviteHandler.getMainTask()
        log.info("mainTask: $mainTask")
        when {
            onboardingInProgress() -> {
                lifecycleScope.launch {
                    viewModel.setInvitationLink(invite, true)
                    finish()
                }
            }
            walletDataProvider.wallet != null -> {
                log.info("the invite will be forwarded, starting MainActivity with invite: ${invite.link}, mainTask: {}", mainTask != null)
                val intent = MainActivity.createIntent(this, invite)
                mainTask?.startActivity(applicationContext, intent, null)
                    ?: startActivity(intent)
                finish()
            }

            else -> {
                log.info("the invite will be forwarded, starting Onboarding with invite: ${invite.link}")
                val intent = OnboardingActivity.createIntent(this, invite)
                mainTask?.startActivity(applicationContext, intent, null)
                    ?: startActivity(intent)
                finish()
            }
        }
    }
}
