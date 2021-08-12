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
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
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
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.FancyAlertDialogViewModel
import org.slf4j.LoggerFactory

class InviteHandler(val activity: AppCompatActivity) {

    private lateinit var inviteLoadingDialog: FancyAlertDialog

    companion object {
        private val log = LoggerFactory.getLogger(InviteHandler::class.java)

        fun showUsernameAlreadyDialog(activity: AppCompatActivity) {
            val inviteErrorDialog = FancyAlertDialog.newInstance(
                R.string.invitation_username_already_found_title,
                R.string.invitation_username_already_found_message,
                R.drawable.ic_invalid_invite, R.string.okay, 0
            )
            inviteErrorDialog.show(activity.supportFragmentManager, null)
            handleDialogResult(activity)
        }

        private fun handleDialogButtonClick(activity: AppCompatActivity) {
            activity.setResult(Activity.RESULT_CANCELED)
            val walletApplication = WalletApplication.getInstance()
            if (walletApplication.wallet == null) {
                startOnboarding(activity, walletApplication)
            } else {
                startMainActivity(activity, walletApplication)
            }
            activity.finish()
        }

        private fun startMainActivity(
            activity: AppCompatActivity,
            walletApplication: WalletApplication
        ) {
            val intent = MainActivity.createIntent(activity)
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (walletApplication.isMainActivityCreated) {
                LocalBroadcastManager.getInstance(activity)
                    .sendBroadcast(Intent(MainActivity.ACTIVATE_ACTION))
            } else {
                activity.startActivity(intent)
            }
        }

        private fun startOnboarding(
            activity: AppCompatActivity,
            walletApplication: WalletApplication
        ) {
            val intent = OnboardingActivity.createIntent(activity)
            if (walletApplication.isOnboardingActivityCreated) {
                LocalBroadcastManager.getInstance(activity)
                    .sendBroadcast(Intent(OnboardingActivity.ACTIVATE_ACTION))
            } else {
                activity.startActivity(intent)
            }
        }

        private fun startOnboarding(
            activity: AppCompatActivity,
            walletApplication: WalletApplication,
            invite: InvitationLinkData
        ) {
            val intent = OnboardingActivity.createIntent(activity, invite)
            if (walletApplication.isOnboardingActivityCreated) {
                intent.action = OnboardingActivity.ACTIVATE_ACTION
                LocalBroadcastManager.getInstance(activity)
                    .sendBroadcast(intent)
            } else {
                activity.startActivity(intent)
            }
        }

        private fun handleDialogResult(activity: AppCompatActivity) {
            val errorDialogViewModel =
                ViewModelProvider(activity)[FancyAlertDialogViewModel::class.java]
            errorDialogViewModel.onPositiveButtonClick.observe(activity, Observer {
                handleDialogButtonClick(activity)
            })
            errorDialogViewModel.onNegativeButtonClick.observe(activity, Observer {
                handleDialogButtonClick(activity)
            })
        }
    }

    fun handle(inviteResource: Resource<InvitationLinkData>, silentMode: Boolean = false) {
        if (!silentMode && inviteResource.status != Status.LOADING) {
            inviteLoadingDialog.dismissAllowingStateLoss()
        }
        val walletApplication = (activity.application as WalletApplication)
        when (inviteResource.status) {
            Status.LOADING -> {
                log.info("loading...")
                if (!silentMode) {
                    showInviteLoadingProgress()
                }
            }
            Status.ERROR -> {
                val displayName = inviteResource.data!!.displayName
                log.info("error processing invite from $displayName")
                showInvalidInviteDialog(displayName)
            }
            Status.CANCELED -> {
                log.info("error processing invite since we have a username already")
                showUsernameAlreadyDialog(activity)
            }
            Status.SUCCESS -> {
                log.info("invite has been validated successfully")
                val invite = inviteResource.data!!
                if (invite.isValid) {
                    activity.setResult(Activity.RESULT_OK)
                    when {
                        silentMode -> {
                            activity.startService(
                                CreateIdentityService.createIntentFromInvite(
                                    activity,
                                    walletApplication.configuration.onboardingInviteUsername,
                                    invite
                                )
                            )
                        }
                        walletApplication.wallet != null -> {
                            //TODO: this is in a new task and bypasses the Lock Screen
                            activity.startActivity(
                                AcceptInviteActivity.createIntent(
                                    activity,
                                    invite,
                                    false
                                )
                            )
                        }
                        else -> {
                            if (invite.isValid) {
                                walletApplication.configuration.onboardingInvite = invite.link
                                startOnboarding(activity, walletApplication, invite)
                            } else {
                                // TODO: is this ever executed?
                                startOnboarding(activity, walletApplication)
                            }
                        }
                    }
                    activity.finish()
                } else {
                    log.info("invite has been used previously")
                    //TODO: does this ever get executed?
                    showInviteAlreadyClaimedDialog(invite)
                    val fancyAlertDialogViewModel =
                        ViewModelProvider(activity)[FancyAlertDialogViewModel::class.java]
                    fancyAlertDialogViewModel.onPositiveButtonClick.observe(activity) {
                        if (walletApplication.wallet == null) {
                            startOnboarding(activity, walletApplication)
                            activity.finish()
                        }
                    }
                }
            }
        }
    }

    private fun showInvalidInviteDialog(displayName: String) {
        val title = activity.getString(R.string.invitation_invalid_invite_title)
        val message = activity.getString(R.string.invitation_invalid_invite_message, displayName)
        val inviteErrorDialog = FancyAlertDialog.newInstance(
            title,
            message,
            R.drawable.ic_invalid_invite,
            R.string.okay,
            0
        )
        inviteErrorDialog.show(activity.supportFragmentManager, null)
        handleDialogResult(activity)
    }

    private fun showInviteAlreadyClaimedDialog(invite: InvitationLinkData) {
        val inviteAlreadyClaimedDialog = InviteAlreadyClaimedDialog.newInstance(activity, invite)
        inviteAlreadyClaimedDialog.show(activity.supportFragmentManager, null)
        handleDialogResult(activity)
    }

    private fun showInviteLoadingProgress() {
        if (::inviteLoadingDialog.isInitialized && inviteLoadingDialog.isAdded) {
            inviteLoadingDialog.dismissAllowingStateLoss()
        }
        inviteLoadingDialog =
            FancyAlertDialog.newProgress(R.string.invitation_verifying_progress_title, 0)
        inviteLoadingDialog.show(activity.supportFragmentManager, null)
    }

    private fun showInsuffientFundsDialog() {
        val dialog = FancyAlertDialog.newProgress(
            R.string.invitation_invalid_invite_title,
            R.string.dashpay_insuffient_credits
        )
        dialog.show(activity.supportFragmentManager, null)
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
                    getInstance().clearBlockchainData()
                    return true
                }

                errorMessage.contains("InvalidIdentityAssetLockProofSignatureError") -> {
                    handle(loading(blockchainIdentityData.invite, 0))
                    handle(error(errorMessage, blockchainIdentityData.invite))
                    // now erase the blockchain data
                    getInstance().clearBlockchainData()
                    return true
                }
                errorMessage.contains("InsuffientFundsError") -> {
                    showInsuffientFundsDialog()
                    // now erase the blockchain data
                    getInstance().clearBlockchainData()
                    return true
                }
            }
        }
        return false
    }
}