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

package de.schildbach.wallet.ui.staking

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.*
import de.schildbach.wallet.ui.more.ContactSupportDialogFragment
import de.schildbach.wallet.ui.verify.VerifySeedActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityStakingBinding
import kotlinx.coroutines.launch
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.services.AuthenticationManager
import org.dash.wallet.common.ui.WebViewFragmentDirections
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.integrations.crowdnode.model.CrowdNodeException
import org.dash.wallet.integrations.crowdnode.model.OnlineAccountStatus
import org.dash.wallet.integrations.crowdnode.model.SignUpStatus
import org.dash.wallet.integrations.crowdnode.ui.CrowdNodeViewModel
import org.dash.wallet.integrations.crowdnode.ui.NavigationRequest
import org.slf4j.LoggerFactory
import javax.inject.Inject

@AndroidEntryPoint
class StakingActivity : LockScreenActivity() {
    companion object {
        private val log = LoggerFactory.getLogger(StakingActivity::class.java)
    }

    private val viewModel: CrowdNodeViewModel by viewModels()
    private lateinit var binding: ActivityStakingBinding
    private lateinit var navController: NavController

    @Inject
    lateinit var securityFunctions: AuthenticationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityStakingBinding.inflate(layoutInflater)
        lifecycleScope.launch { navController = setNavigationGraph() }

        viewModel.navigationCallback.observe(this, ::handleNavigationRequest)
        viewModel.observeOnlineAccountStatus().observe(this, ::handleOnlineAccountStatus)
        viewModel.observeCrowdNodeError().observe(this, ::handleCrowdNodeError)

        val intent = Intent(this, StakingActivity::class.java)
        viewModel.setNotificationIntent(intent)

        setContentView(binding.root)
    }

    private fun handleNavigationRequest(request: NavigationRequest) {
        when (request) {
            NavigationRequest.BackupPassphrase -> checkPinAndBackupPassphrase()
            NavigationRequest.RestoreWallet -> {
                ResetWalletDialog.newInstance(viewModel.analytics).show(supportFragmentManager, "reset_wallet_dialog")
            }
            NavigationRequest.BuyDash -> {
                // TODO: replace with navController navigation when Staking is integrated into nav_home
                setResult(Constants.USER_BUY_SELL_DASH)
                finish()
            }
            NavigationRequest.SendReport -> {
                log.info("CrowdNode initiated report")
                ContactSupportDialogFragment.newInstance(
                    getString(R.string.report_issue_dialog_title_issue),
                    getString(R.string.report_issue_dialog_message_issue),
                        contextualData = "CrowdNode initiated report"
                ).show(this)
            }
            else -> { }
        }
    }

    private fun handleOnlineAccountStatus(status: OnlineAccountStatus) {
        when (status) {
            OnlineAccountStatus.None -> { }
            OnlineAccountStatus.Linking, OnlineAccountStatus.SigningUp -> super.turnOffAutoLogout()
            OnlineAccountStatus.Validating -> {
                val isWebView = navController.currentDestination?.id == R.id.crowdNodeWebViewFragment
                if (isWebView) {
                    navController.navigate(WebViewFragmentDirections.webViewToPortal())
                }
                super.turnOnAutoLogout()
            }
            else -> super.turnOnAutoLogout()
        }
    }

    private fun handleCrowdNodeError(error: Exception?) {
        if (error is CrowdNodeException && error.message == CrowdNodeException.MISSING_PRIMARY) {
            AdaptiveDialog.create(
                R.drawable.ic_error,
                getString(org.dash.wallet.common.R.string.error),
                getString(R.string.crowdnode_primary_missing),
                getString(R.string.button_close)
            ).show(this)
            viewModel.clearError()
        }
    }

    private fun checkPinAndBackupPassphrase() {
        lifecycleScope.launch {
            val pin = securityFunctions.authenticate(this@StakingActivity)

            if (pin != null) {
                val intent = VerifySeedActivity.createIntent(
                    this@StakingActivity,
                    pin,
                    startMainActivityOnClose = false
                )
                startActivity(intent)
            }
        }
    }

    private suspend fun setNavigationGraph(): NavController {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val navGraph = navController.navInflater.inflate(R.navigation.nav_crowdnode)

        viewModel.recheckState()
        val status = viewModel.signUpStatus
        binding.progressBar.isVisible = false

        navGraph.setStartDestination(
            when (status) {
                SignUpStatus.LinkedOnline, SignUpStatus.Finished -> R.id.crowdNodePortalFragment
                SignUpStatus.NotStarted -> {
                    val isInfoShown = viewModel.getIsInfoShown()
                    if (isInfoShown) R.id.entryPointFragment else R.id.firstTimeInfo
                }
                else -> R.id.newAccountFragment
            }
        )

        navController.graph = navGraph

        return navController
    }

    override fun onPause() {
        super.onPause()
        viewModel.changeNotifyWhenDone(true)
        viewModel.cancelLinkingOnlineAccount()
    }

    override fun onResume() {
        super.onResume()
        viewModel.changeNotifyWhenDone(false)

        if (this::navController.isInitialized &&
            navController.currentDestination?.id == R.id.crowdNodeWebViewFragment) {
            viewModel.linkOnlineAccount()
        }
    }
}