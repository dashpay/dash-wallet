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

package de.schildbach.wallet.ui.invite

import android.app.Activity
import android.app.ActivityManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.lifecycle.ViewModelProvider
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainIdentityBaseData
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Resource.Companion.error
import de.schildbach.wallet.livedata.Resource.Companion.loading
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.MainActivity
import de.schildbach.wallet.ui.OnboardingActivity
import de.schildbach.wallet.ui.dashpay.CreateIdentityService
import de.schildbach.wallet.ui.dashpay.PlatformRepo.Companion.getInstance
import de.schildbach.wallet_test.R
import org.dash.wallet.common.services.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.FancyAlertDialogViewModel
import org.slf4j.LoggerFactory

class InviteHandler(val activity: AppCompatActivity, private val analytics: AnalyticsService) {

    private lateinit var inviteLoadingDialog: FancyAlertDialog

    companion object {
        private val log = LoggerFactory.getLogger(InviteHandler::class.java)

        private fun getMainTask(activity: AppCompatActivity): ActivityManager.AppTask {
            val activityManager = activity.getSystemService(AppCompatActivity.ACTIVITY_SERVICE) as ActivityManager
            return activityManager.appTasks.last()
        }

        private fun handleDialogButtonClick(activity: AppCompatActivity) {
            activity.setResult(Activity.RESULT_CANCELED)
            val walletApplication = WalletApplication.getInstance()
            val mainTask = getMainTask(activity)
            if (walletApplication.wallet != null) {
                // if wallet exists, go to the Home Screen
                mainTask.startActivity(activity.applicationContext, MainActivity.createIntent(activity), null)
            } else {
                mainTask.startActivity(activity.applicationContext, OnboardingActivity.createIntent(activity), null)
            }
            activity.finish()
        }

        private fun handleMoveToFront(activity: AppCompatActivity) {
            activity.setResult(Activity.RESULT_CANCELED)
            val mainTask = getMainTask(activity)
            mainTask.moveToFront()
            activity.finish()
        }

        private fun handleDialogResult(activity: AppCompatActivity) {
            val errorDialogViewModel =
                ViewModelProvider(activity)[FancyAlertDialogViewModel::class.java]
            errorDialogViewModel.onPositiveButtonClick.observe(activity) {
                handleDialogButtonClick(activity)
            }
            errorDialogViewModel.onNegativeButtonClick.observe(activity) {
                handleDialogButtonClick(activity)
            }
        }

        private fun handleDialogResult(activity: AppCompatActivity, onClick : (AppCompatActivity) -> Unit) {
            val errorDialogViewModel =
                ViewModelProvider(activity)[FancyAlertDialogViewModel::class.java]
            errorDialogViewModel.onPositiveButtonClick.observe(activity) {
                onClick(activity)
            }
            errorDialogViewModel.onNegativeButtonClick.observe(activity) {
                onClick(activity)
            }
        }
    }

    fun handle(inviteResource: Resource<InvitationLinkData>, silentMode: Boolean = false) {
        if (!silentMode && inviteResource.status != Status.LOADING) {
            inviteLoadingDialog.dismissAllowingStateLoss()
        }

        when (inviteResource.status) {
            Status.LOADING -> {
                if (!silentMode) {
                    showInviteLoadingProgress()
                }
            }
            Status.ERROR -> {
                val displayName = inviteResource.data!!.displayName
                showInvalidInviteDialog(displayName)
            }
            Status.CANCELED -> {
                showUsernameAlreadyDialog()
            }
            Status.SUCCESS -> {
                val invite = inviteResource.data!!
                if (invite.isValid) {
                    val mainTask = getMainTask()
                    activity.setResult(Activity.RESULT_OK)
                    val walletApplication = (activity.application as WalletApplication)
                    when {
                        silentMode -> {
                            log.info("the invite is valid, starting silently: ${invite.link}")
                            activity.startService(CreateIdentityService.createIntentFromInvite(activity, walletApplication.configuration.onboardingInviteUsername, invite))
                        }
                        walletApplication.wallet != null -> {
                            log.info("the invite is valid, starting AcceptInviteActivity with invite: ${invite.link}")
                            mainTask.startActivity(activity.applicationContext, AcceptInviteActivity.createIntent(activity, invite, false), null)
                        }
                        else -> {
                            if (invite.isValid) {
                                log.info("the invite is valid, starting Onboarding with invite: ${invite.link}")
                                walletApplication.configuration.onboardingInvite = invite.link
                                mainTask.startActivity(activity.applicationContext, OnboardingActivity.createIntent(activity, invite), null)
                            } else {
                                log.info("the invite is valid, starting Onboarding without invite")
                                mainTask.startActivity(activity.applicationContext, OnboardingActivity.createIntent(activity), null)
                            }
                        }
                    }
                    activity.finish()
                } else {
                    showInviteAlreadyClaimedDialog(invite)
                }
            }
        }
    }

    private fun getMainTask(): ActivityManager.AppTask {
        return getMainTask(activity)
    }

    private fun showInvalidInviteDialog(displayName: String) {
        val title = activity.getString(R.string.invitation_invalid_invite_title)
        val message = activity.getString(R.string.invitation_invalid_invite_message, displayName)
        val inviteErrorDialog = FancyAlertDialog.newInstance(title, message, R.drawable.ic_invalid_invite, R.string.okay, 0)
        inviteErrorDialog.show(activity.supportFragmentManager, null)
        handleDialogResult(activity)
        analytics.logEvent(AnalyticsConstants.Invites.ERROR_INVALID, bundleOf())
    }

    fun showUsernameAlreadyDialog() {
        val inviteErrorDialog = FancyAlertDialog.newInstance(
            R.string.invitation_username_already_found_title,
            R.string.invitation_username_already_found_message,
            R.drawable.ic_invalid_invite, R.string.okay, 0
        )
        inviteErrorDialog.show(activity.supportFragmentManager, null)
        handleDialogResult(activity)
        analytics.logEvent(AnalyticsConstants.Invites.ERROR_USERNAME_TAKEN, bundleOf())
    }

    private fun showInviteAlreadyClaimedDialog(invite: InvitationLinkData) {
        val inviteAlreadyClaimedDialog = InviteAlreadyClaimedDialog.newInstance(activity, invite)
        inviteAlreadyClaimedDialog.show(activity.supportFragmentManager, null)
        handleDialogResult(activity)
        analytics.logEvent(AnalyticsConstants.Invites.ERROR_ALREADY_CLAIMED, bundleOf())
    }

    fun showInviteWhileOnboardingInProgressDialog() {
        val inviteErrorDialog = FancyAlertDialog.newInstance(
            R.string.invitation_onboarding_has_began_error_title,
            R.string.invitation_onboarding_has_began_error,
            R.drawable.ic_invalid_invite, R.string.okay, 0
        )
        inviteErrorDialog.show(activity.supportFragmentManager, null)
        handleDialogResult(activity) {
            handleMoveToFront(activity)
        }
    }

    private fun showInviteLoadingProgress() {
        if (::inviteLoadingDialog.isInitialized && inviteLoadingDialog.isAdded) {
            inviteLoadingDialog.dismissAllowingStateLoss()
        }
        inviteLoadingDialog = FancyAlertDialog.newProgress(R.string.invitation_verifying_progress_title, 0)
        inviteLoadingDialog.show(activity.supportFragmentManager, null)
    }

    private fun showInsufficientFundsDialog() {
        val dialog = FancyAlertDialog.newProgress(R.string.invitation_invalid_invite_title, R.string.dashpay_insuffient_credits)
        dialog.show(activity.supportFragmentManager, null)
        analytics.logEvent(AnalyticsConstants.Invites.ERROR_INSUFFICIENT_FUNDS, bundleOf())
    }

    /**
     * handle non-recoverable errors from using an invite
     */
    fun handleError(blockchainIdentityData: BlockchainIdentityBaseData): Boolean {
        // handle errors
        var errorMessage: String
        if (blockchainIdentityData.creationStateErrorMessage.also { errorMessage = it!! } != null) {
            when {
                (errorMessage.contains("IdentityAssetLockTransactionOutPointAlreadyExistsError")) -> {
                    showInviteAlreadyClaimedDialog(blockchainIdentityData.invite!!)
                    // now erase the blockchain data
                    getInstance().clearBlockchainIdentityData()
                    return true
                }

                errorMessage.contains("InvalidIdentityAssetLockProofSignatureError") -> {
                    handle(loading(blockchainIdentityData.invite, 0))
                    handle(error(errorMessage, blockchainIdentityData.invite))
                    // now erase the blockchain data
                    getInstance().clearBlockchainIdentityData()
                    return true
                }
                errorMessage.contains("InsuffientFundsError") -> {
                    showInsufficientFundsDialog()
                    // now erase the blockchain data
                    getInstance().clearBlockchainIdentityData()
                    return true
                }
            }
        }
        return false
    }
}