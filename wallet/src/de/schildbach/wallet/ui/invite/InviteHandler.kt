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
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet_test.R
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dashj.platform.dpp.errors.ConcensusErrorMetadata
import org.dashj.platform.dpp.errors.concensus.ConcensusException
import org.dashj.platform.dpp.errors.concensus.basic.identity.IdentityAssetLockTransactionOutPointAlreadyExistsException
import org.dashj.platform.dpp.errors.concensus.basic.identity.InvalidInstantAssetLockProofSignatureException
import org.dashj.platform.dpp.errors.concensus.fee.BalanceIsNotEnoughException
import org.slf4j.LoggerFactory
import javax.inject.Inject

class InviteHandler(val activity: FragmentActivity, private val analytics: AnalyticsService) {

    private var inviteLoadingDialog: AdaptiveDialog? = null
    @Inject lateinit var platformRepo: PlatformRepo

    companion object {
        private val log = LoggerFactory.getLogger(InviteHandler::class.java)

        private fun getMainTask(activity: FragmentActivity): ActivityManager.AppTask? {
            val activityManager = activity.getSystemService(FragmentActivity.ACTIVITY_SERVICE) as ActivityManager
            return activityManager.appTasks.lastOrNull()
        }

        // TODO: this does not work well, app closes
        // what is the backstack
        private fun handleDialogButtonClick(activity: FragmentActivity) {
            activity.setResult(Activity.RESULT_CANCELED)
            val walletApplication = WalletApplication.getInstance()
            val mainTask = getMainTask(activity)
            if (walletApplication.wallet != null) {
                // if wallet exists, go to the Home Screen
                val intent = MainActivity.createIntent(activity)
                mainTask?.startActivity(activity.applicationContext, intent, null)
                    ?: activity.startActivity(intent)
            } else {
                val intent = OnboardingActivity.createIntent(activity)
                mainTask?.startActivity(activity.applicationContext, intent, null)
                    ?: activity.startActivity(intent)
            }
            activity.finish()
        }

        private fun handleMoveToFront(activity: FragmentActivity) {
            activity.setResult(Activity.RESULT_CANCELED)
            val mainTask = getMainTask(activity)
            mainTask?.moveToFront()
            activity.finish()
        }
    }

    fun handle(inviteResource: Resource<InvitationLinkData>, silentMode: Boolean = false, successFunction: ((InvitationLinkData) -> Unit)? = null) {
        if (!silentMode && inviteResource.status != Status.LOADING) {
            inviteLoadingDialog?.dismissAllowingStateLoss()
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
                if (invite.isValid == true) {
                    successFunction?.invoke(invite)
                } else {
                    log.info("the invite has already been claimed")
                    showInviteAlreadyClaimedDialog(invite)
                }
            }
        }
    }

    fun getMainTask(): ActivityManager.AppTask? {
        return getMainTask(activity)
    }

    fun showInvalidInviteDialog(displayName: String) {
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
            activity.getString(R.string.button_ok)
        ).show(activity) {
            handleDialogButtonClick(activity)
        }
        analytics.logEvent(AnalyticsConstants.Invites.ERROR_USERNAME_TAKEN, mapOf())
    }

    fun showInviteAlreadyClaimedDialog(invite: InvitationLinkData) {
        InviteAlreadyClaimedDialog
            .newInstance(activity, invite)
            .show(activity) {
                handleDialogButtonClick(activity)
            }
        analytics.logEvent(AnalyticsConstants.Invites.ERROR_ALREADY_CLAIMED, mapOf())
    }

    fun showInviteWhileOnboardingInProgressDialog() {
        AdaptiveDialog.create(
            R.drawable.ic_invalid_invite,
            activity.getString(R.string.invitation_error_title),
            activity.getString(R.string.invitation_onboarding_has_began_error),
            activity.getString(R.string.okay)
        ).show(activity) {
            handleMoveToFront(activity)
        }
    }

    fun showInviteWhileProcessingInviteInProgressDialog() {
        AdaptiveDialog.create(
            R.drawable.ic_invalid_invite,
            activity.getString(R.string.invitation_error_title),
            activity.getString(R.string.invitation_accept_invite_has_began_error),
            activity.getString(R.string.okay)
        ).show(activity) {
            // TODO: this does not work well
            handleDialogButtonClick(activity)
            //handleMoveToFront(activity)
            // activity.finish()
        }
    }

    fun showInviteLoadingProgress() {
        if (inviteLoadingDialog?.isAdded == true) {
            inviteLoadingDialog?.dismissAllowingStateLoss()
        }
        inviteLoadingDialog = AdaptiveDialog.progress(activity.getString(R.string.invitation_verifying_progress_title)).apply {
            show(this@InviteHandler.activity.supportFragmentManager, null)
        }
    }

    private fun showInsufficientFundsDialog() {
        val dialog = AdaptiveDialog.create(
            R.drawable.ic_error,
            activity.getString(R.string.invitation_invalid_invite_title),
            activity.getString(R.string.dashpay_insuffient_credits),
            activity.getString(R.string.button_ok)
        )
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
                    platformRepo.clearBlockchainIdentityData()
                    return true
                }

                exception is InvalidInstantAssetLockProofSignatureException -> {
                    handle(loading(blockchainIdentityData.invite, 0))
                    handle(error(exception.message!!, blockchainIdentityData.invite))
                    // now erase the blockchain data
                    platformRepo.clearBlockchainIdentityData()
                    return true
                }
                exception is BalanceIsNotEnoughException -> {
                    showInsufficientFundsDialog()
                    // now erase the blockchain data
                    platformRepo.clearBlockchainIdentityData()
                    return true
                }
            }
        }
        return false
    }
}