/*
 * Copyright 2020 Dash Core Group.
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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet.ui.dashpay.DashPayViewModel
import de.schildbach.wallet.ui.dashpay.PlatformPaymentConfirmDialog
import de.schildbach.wallet.ui.username.CreateUsernameActions
import de.schildbach.wallet.ui.username.CreateUsernameArgs
import de.schildbach.wallet.ui.username.CreateUsernameFragment
import de.schildbach.wallet.ui.username.voting.RequestUserNameViewModel
import de.schildbach.wallet_test.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.dash.wallet.common.InteractionAwareActivity
import org.slf4j.LoggerFactory

@AndroidEntryPoint
class CreateUsernameActivity : LockScreenActivity() {

    private val dashPayViewModel: DashPayViewModel by viewModels()

    val confirmTransactionSharedViewModel: PlatformPaymentConfirmDialog.SharedViewModel by viewModels()
    private val requestUserNameViewModel: RequestUserNameViewModel by viewModels()
    companion object {
        private val log = LoggerFactory.getLogger(CreateUsernameActivity::class.java)

        private const val ACTION_CREATE_NEW = "action_create_new"
        private const val ACTION_DISPLAY_COMPLETE = "action_display_complete"
        private const val ACTION_REUSE_TRANSACTION = "action_reuse_transaction"
        private const val ACTION_FROM_INVITE = "action_from_invite"
        private const val ACTION_FROM_INVITE_REUSE_TRANSACTION = "action_from_invite_reuse_transaction"

        private const val EXTRA_USERNAME = "extra_username"
        private const val EXTRA_INVITE = "extra_invite"
        private const val EXTRA_FROM_ONBOARDING = "extra_from_onboarding"

        @JvmStatic
        fun createIntent(context: Context, username: String? = null): Intent {
            return Intent(context, CreateUsernameActivity::class.java).apply {
                action = if (username == null) ACTION_CREATE_NEW else ACTION_DISPLAY_COMPLETE
                putExtra(EXTRA_USERNAME, username)
            }
        }

        @JvmStatic
        fun createIntentReuseTransaction(context: Context, blockchainIdentityData: BlockchainIdentityBaseData): Intent {
            return Intent(context, CreateUsernameActivity::class.java).apply {
                action = if (blockchainIdentityData.usingInvite) ACTION_FROM_INVITE_REUSE_TRANSACTION else ACTION_REUSE_TRANSACTION
            }
        }

        @JvmStatic
        fun createIntentFromInvite(context: Context, invite: InvitationLinkData, fromOnboarding: Boolean): Intent {
            return Intent(context, CreateUsernameActivity::class.java).apply {
                action = ACTION_FROM_INVITE
                putExtra(EXTRA_INVITE, invite)
                putExtra(EXTRA_FROM_ONBOARDING, fromOnboarding)
                if (fromOnboarding) {
                    putExtra(INTENT_EXTRA_NO_BLOCKCHAIN_SERVICE, true)
                    putExtra(INTENT_EXTRA_KEEP_UNLOCKED, true)
                }
            }
        }

        @JvmStatic
        fun createIntentFromInviteReuseTransaction(context: Context): Intent {
            return Intent(context, CreateUsernameActivity::class.java).apply {
                action = ACTION_FROM_INVITE_REUSE_TRANSACTION
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_create_username)
        val action = when (intent?.action) {
            ACTION_DISPLAY_COMPLETE -> {
                CreateUsernameActions.DISPLAY_COMPLETE
            }
            ACTION_REUSE_TRANSACTION -> {
                CreateUsernameActions.REUSE_TRANSACTION
            }
            ACTION_FROM_INVITE -> {
                CreateUsernameActions.FROM_INVITE
            }
            else -> {
                null
            }
        }

        val username = intent?.extras?.getString(EXTRA_USERNAME)
        val invite = intent.getParcelableExtra<InvitationLinkData>(EXTRA_INVITE)
        val fromOnboardng = intent.getBooleanExtra(EXTRA_FROM_ONBOARDING, false)

        lifecycleScope.launchWhenCreated {
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_create_user_name_fragment) as NavHostFragment
            val navController = navHostFragment.navController
            val navGraph = navController.navInflater.inflate(R.navigation.nav_username)
            val bundle = Bundle()

            dashPayViewModel.createUsernameArgs = CreateUsernameArgs(
                actions = action,
                userName = username,
                invite = invite,
                fromOnboardng = fromOnboardng
            )

            if (requestUserNameViewModel.isUserNameRequested() &&
                !requestUserNameViewModel.isUsernameLocked() &&
                !requestUserNameViewModel.isUsernameLostAfterVoting() &&
                (requestUserNameViewModel.identity?.creationState
                    ?: BlockchainIdentityData.CreationState.NONE) >= BlockchainIdentityData.CreationState.VOTING
            ) {
                navGraph.setStartDestination(R.id.votingRequestDetailsFragment)
            } else {
                if (!dashPayViewModel.isDashPayInfoShown()) {
                    navGraph.setStartDestination(R.id.welcomeToDashPayFragment)
                } else {
                    navGraph.setStartDestination(R.id.requestUsernameFragment)
                }
            }

            navController.graph = navGraph
            navController.setGraph(navController.graph, bundle)
        }
    }
}
