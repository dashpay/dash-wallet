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
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.service.BlockchainStateDataProvider
import de.schildbach.wallet.ui.dashpay.CreateIdentityService
import de.schildbach.wallet.ui.invite.InviteHandler
import de.schildbach.wallet.ui.invite.InviteHandler.Companion
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet_test.databinding.ActivityTransparentBinding
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.OnboardingState
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import javax.inject.Inject

@AndroidEntryPoint
class InviteHandlerActivity : AppCompatActivity() {
    companion object {
        private val log = LoggerFactory.getLogger(InviteHandlerActivity::class.java)

        private const val EXTRA_INVITE = "extra_invite"
        private const val EXTRA_SILENT_MODE = "extra_silent_mode"

        @JvmStatic
        @Deprecated("only the android OS should create this activity")
        fun createIntent(context: Context, invite: InvitationLinkData, silentMode: Boolean): Intent {
            return Intent(context, InviteHandlerActivity::class.java).apply {
                putExtra(EXTRA_INVITE, invite)
                putExtra(EXTRA_SILENT_MODE, silentMode)
            }
        }
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

    private val externalInvite by lazy {
        intent.getParcelableExtra<InvitationLinkData>(EXTRA_INVITE)
    }

    private val externalSilentMode by lazy {
        intent.getBooleanExtra(EXTRA_SILENT_MODE, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransparentBinding.inflate(layoutInflater)
        setContentView(binding.root)

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
        OnboardingState.init(configuration)
        return /*walletDataProvider.wallet != null &&*/ OnboardingState.isOnboarding()
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
            when (it.status) {
                Status.LOADING -> {
                    inviteHandler.showInviteLoadingProgress()
                }
                Status.ERROR -> {
                    val displayName = it.data!!.displayName
                    inviteHandler.showInvalidInviteDialog(displayName)
                }
                Status.CANCELED -> {
                    inviteHandler.showUsernameAlreadyDialog()
                }
                Status.SUCCESS -> {
                    val invite = it.data!!
                    val mainTask = inviteHandler.getMainTask()
                    setResult(Activity.RESULT_OK)
                    when {
                        walletDataProvider.wallet != null -> {
                            log.info("the invite will be forwarded, starting MainActivity with invite: ${invite.link}")
                            val intent = MainActivity.createIntent(this, invite)
                            mainTask?.startActivity(applicationContext, intent, null)
                                ?: startActivity(intent)
                        }
                        else -> {
                            log.info("the invite will be forwarded, starting Onboarding with invite: ${invite.link}")
                            configuration.onboardingInvite = invite.link
                            val intent = OnboardingActivity.createIntent(this, invite)
                            mainTask?.startActivity(applicationContext, intent, null)
                                ?: startActivity(intent)
                        }
                    }
                    finish()
                }
            }
        }
    }
}
