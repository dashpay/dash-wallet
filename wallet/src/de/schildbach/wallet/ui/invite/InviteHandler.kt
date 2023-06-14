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
import androidx.fragment.app.FragmentActivity
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Resource.Companion.error
import de.schildbach.wallet.livedata.Resource.Companion.loading
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet.ui.OnboardingActivity
import de.schildbach.wallet.ui.dashpay.CreateIdentityService
import de.schildbach.wallet.ui.dashpay.PlatformRepo.Companion.getInstance
import de.schildbach.wallet_test.R
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dashj.platform.dpp.errors.ConcensusErrorMetadata
import org.dashj.platform.dpp.errors.concensus.ConcensusException
import org.dashj.platform.dpp.errors.concensus.basic.identity.IdentityAssetLockTransactionOutPointAlreadyExistsException
import org.dashj.platform.dpp.errors.concensus.basic.identity.InvalidInstantAssetLockProofSignatureException
import org.dashj.platform.dpp.errors.concensus.fee.BalanceIsNotEnoughException
import org.slf4j.LoggerFactory

class InviteHandler(val activity: FragmentActivity, private val analytics: AnalyticsService) {

    private lateinit var inviteLoadingDialog: FancyAlertDialog

    companion object {
        private val log = LoggerFactory.getLogger(InviteHandler::class.java)

        private fun getMainTask(activity: FragmentActivity): ActivityManager.AppTask {
            val activityManager = activity.getSystemService(FragmentActivity.ACTIVITY_SERVICE) as ActivityManager
            return activityManager.appTasks.last()
        }

        private fun handleDialogButtonClick(activity: FragmentActivity) {
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

        private fun handleMoveToFront(activity: FragmentActivity) {
            activity.setResult(Activity.RESULT_CANCELED)
            val mainTask = getMainTask(activity)
            mainTask.moveToFront()
            activity.finish()
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
                            log.info("the invite is valid, starting MainActivity with invite: ${invite.link}")
                            mainTask.startActivity(activity.applicationContext, MainActivity.createIntent(activity, invite), null)
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
        AdaptiveDialog.create(
            R.drawable.ic_invalid_invite,
            activity.getString(R.string.invitation_invalid_invite_title),
            activity.getString(R.string.invitation_invalid_invite_message, displayName),
            activity.getString(R.string.okay)
        ).show(activity) {
            handleDialogButtonClick(activity)
        }
        analytics.logEvent(AnalyticsConstants.Invites.ERROR_INVALID, mapOf())
    }

    fun showUsernameAlreadyDialog() {
        AdaptiveDialog.create(
            R.drawable.ic_invalid_invite,
            activity.getString(R.string.invitation_username_already_found_title),
            activity.getString(R.string.invitation_username_already_found_message),
            activity.getString(R.string.okay)
        ).show(activity) {
            handleDialogButtonClick(activity)
        }
        analytics.logEvent(AnalyticsConstants.Invites.ERROR_USERNAME_TAKEN, mapOf())
    }

    private fun showInviteAlreadyClaimedDialog(invite: InvitationLinkData) {
        val inviteAlreadyClaimedDialog = InviteAlreadyClaimedDialog.newInstance(activity, invite)
        inviteAlreadyClaimedDialog.onFancyAlertButtonsClickListener = object :
            FancyAlertDialog.FancyAlertButtonsClickListener {
            override fun onPositiveButtonClick() {
                handleDialogButtonClick(activity)
            }

            override fun onNegativeButtonClick() {
                handleDialogButtonClick(activity)
            }
        }
        inviteAlreadyClaimedDialog.show(activity.supportFragmentManager, null)
        analytics.logEvent(AnalyticsConstants.Invites.ERROR_ALREADY_CLAIMED, mapOf())
    }

    fun showInviteWhileOnboardingInProgressDialog() {
        AdaptiveDialog.create(
            R.drawable.ic_invalid_invite,
            activity.getString(R.string.invitation_onboarding_has_began_error_title),
            activity.getString(R.string.invitation_onboarding_has_began_error),
            activity.getString(R.string.okay)
        ).show(activity) {
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
        analytics.logEvent(AnalyticsConstants.Invites.ERROR_INSUFFICIENT_FUNDS, mapOf())
    }

    /**
     * handle non-recoverable errors from using an invite
     */
    fun handleError(blockchainIdentityData: BlockchainIdentityBaseData): Boolean {
        // handle errors
        var exception: ConcensusException
        if (blockchainIdentityData.creationStateErrorMessage.also {
            val errorMetadata = ConcensusErrorMetadata(it!!)
                exception = ConcensusException.Companion.create(errorMetadata)
        } != null) {

            when {
                exception is IdentityAssetLockTransactionOutPointAlreadyExistsException -> {
                    showInviteAlreadyClaimedDialog(blockchainIdentityData.invite!!)
                    // now erase the blockchain data
                    getInstance().clearBlockchainIdentityData()
                    return true
                }

                exception is InvalidInstantAssetLockProofSignatureException -> {
                    handle(loading(blockchainIdentityData.invite, 0))
                    handle(error(exception.message!!, blockchainIdentityData.invite))
                    // now erase the blockchain data
                    getInstance().clearBlockchainIdentityData()
                    return true
                }
                exception is BalanceIsNotEnoughException -> {
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