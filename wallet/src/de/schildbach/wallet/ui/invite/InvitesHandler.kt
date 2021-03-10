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

import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.livedata.Status
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.FancyAlertDialog

class InvitesHandler(val activity: AppCompatActivity) {

    private lateinit var inviteLoadingDialog: FancyAlertDialog

    fun handle(inviteResource: Resource<Pair<Uri, Boolean>>) {
        when (inviteResource.status) {
            Status.LOADING -> {
                showInviteLoadingProgress()
            }
            Status.ERROR -> {
                inviteLoadingDialog.dismissAllowingStateLoss()
                val displayName = inviteResource.data!!.first.getQueryParameter("display-name")!!
                showInvalidInviteDialog(displayName)
            }
            Status.CANCELED -> {
                Toast.makeText(activity, "This is your own invitation", Toast.LENGTH_LONG).show()
                activity.startActivity(InvitesHistoryActivity.createIntent(activity))
            }
            Status.SUCCESS -> {
                inviteLoadingDialog.dismissAllowingStateLoss()
                val isValid = inviteResource.data!!.second
                if (isValid) {
                    activity.startActivity(InviteWelcomeActivity.createIntent(activity))
                } else {
                    val link = inviteResource.data.first
                    showInviteAlreadyClaimedDialog(link)
                }
            }
        }
    }

    private fun showInvalidInviteDialog(displayName: String) {
        val title = activity.getString(R.string.invitation_invalid_invite_title)
        val message = activity.getString(R.string.invitation_invalid_invite_message, displayName)
        val inviteErrorDialog = FancyAlertDialog.newInstance(title, message, R.drawable.ic_invalid_invite, R.string.okay, 0)
        inviteErrorDialog.show(activity.supportFragmentManager, null)
    }

    private fun showInviteAlreadyClaimedDialog(link: Uri) {
        val displayName = link.getQueryParameter("display-name")!!
        val profilePictureUrl = link.getQueryParameter("avatar-url")!!
        val inviteAlreadyClaimedDialog = InviteAlreadyClaimedDialog.newInstance(activity, displayName, profilePictureUrl)
        inviteAlreadyClaimedDialog.show(activity.supportFragmentManager, null)
    }

    private fun showInviteLoadingProgress() {
        if (::inviteLoadingDialog.isInitialized && inviteLoadingDialog.isAdded) {
            inviteLoadingDialog.dismissAllowingStateLoss()
        }
        inviteLoadingDialog = FancyAlertDialog.newProgress(0, 0)
        inviteLoadingDialog.show(activity.supportFragmentManager, null)
    }
}