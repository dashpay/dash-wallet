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

package de.schildbach.wallet.ui.invite

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.fragment.app.FragmentActivity
import androidx.navigation.fragment.NavHostFragment
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet_test.R
import org.dash.wallet.common.ui.FancyAlertDialog
import org.slf4j.LoggerFactory

@AndroidEntryPoint
class InviteFriendActivity : LockScreenActivity() {

    companion object {
        private val log = LoggerFactory.getLogger(InviteFriendActivity::class.java)
        private const val ARG_IDENTITY_ID = "identity_id"
        private const val ARG_STARTED_BY_HISTORY = "started_by_history"
        private const val ARG_INVITE_INDEX = "invite_index"

        @JvmStatic
        fun createIntent(context: Context, startedByHistory: Boolean): Intent {
            val intent = Intent(context, InviteFriendActivity::class.java)
            intent.putExtra(ARG_IDENTITY_ID, "")
            intent.putExtra(ARG_STARTED_BY_HISTORY, startedByHistory)
            return intent
        }

        fun startOrError(activity: FragmentActivity, startedByHistory: Boolean = false) {
            if (WalletApplication.getInstance().canAffordIdentityCreation()) {
                activity.startActivity(createIntent(activity, startedByHistory))
            } else {
                val title = activity.getString(R.string.invitation_cant_afford_title)
                val message = activity.getString(R.string.invitation_cant_afford_message, Constants.DASH_PAY_FEE.toPlainString())
                val errorDialog = FancyAlertDialog.newInstance(
                    title,
                    message,
                    R.drawable.ic_cant_afford_invitation,
                    0,
                    R.string.invitation_preview_close,
                )
                errorDialog.show(activity.supportFragmentManager, null)
            }
        }

        fun createIntentExistingInvite(context: Context, userId: String, inviteIndex: Int, startedByHistory: Boolean = true): Intent? {
            val intent = Intent(context, InviteFriendActivity::class.java)
            intent.putExtra(ARG_IDENTITY_ID, userId)
            intent.putExtra(ARG_STARTED_BY_HISTORY, startedByHistory)
            intent.putExtra(ARG_INVITE_INDEX, inviteIndex)
            return intent
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_invite_friends)

        val userId = intent.extras?.getString(ARG_IDENTITY_ID, "") ?: ""
        val startedByHistory = intent.extras?.getBoolean(ARG_STARTED_BY_HISTORY, false) ?: false

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_invite_friends) as NavHostFragment
        val graphInflater = navHostFragment.navController.navInflater
        val navGraph = graphInflater.inflate(R.navigation.nav_invite_friends)
        val navController = navHostFragment.navController

        val bundle = Bundle()
        bundle.putBoolean(ARG_STARTED_BY_HISTORY, startedByHistory)

        val destination = if (userId.isEmpty()) {
            R.id.inviteFriendFragment
        } else {
            val inviteIndex = intent.extras?.getInt(ARG_INVITE_INDEX, -1) ?: -1
            bundle.putString(ARG_IDENTITY_ID, userId)
            bundle.putInt(ARG_INVITE_INDEX, inviteIndex)
            R.id.inviteDetailsFragment
        }
        navGraph.setStartDestination(destination)
        navController.setGraph(navGraph, bundle)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }
}
