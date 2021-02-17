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
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.google.firebase.dynamiclinks.FirebaseDynamicLinks
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.util.canAffordIdentityCreation
import de.schildbach.wallet_test.R
import org.dash.wallet.common.InteractionAwareActivity
import org.dash.wallet.common.ui.FancyAlertDialog
import org.slf4j.LoggerFactory

class InviteFriendActivity : InteractionAwareActivity() {

    companion object {
        private val log = LoggerFactory.getLogger(InviteFriendActivity::class.java)

        @JvmStatic
        fun createIntent(context: Context): Intent {
            return Intent(context, InviteFriendActivity::class.java)
        }

        fun startOrError(activity: FragmentActivity) {
            if (WalletApplication.getInstance().wallet.canAffordIdentityCreation()) {
                activity.startActivity(createIntent(activity))
            } else {
                val errorDialog = FancyAlertDialog.newInstance(R.string.invitation_cant_afford_title,
                        R.string.invitation_cant_afford_message, R.drawable.ic_cant_afford_invitation,
                        0, R.string.invitation_preview_close)
                errorDialog.show(activity.supportFragmentManager, null)
            }
        }
    }

    val viewModel by lazy {
        ViewModelProvider(this).get(InviteFriendViewModel::class.java)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_holder)

        supportFragmentManager.beginTransaction()
                .replace(R.id.container, InviteFriendFragment.newInstance())
                .commitNow()

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent!!)
    }

    private fun handleIntent(intent: Intent) {
        Log.i("FirebaseDynamicLinks2", "We have a dynamic link! $intent")
        Log.i("FirebaseDynamicLinks2", "We have a dynamic link! ${intent!!.data}")
        FirebaseDynamicLinks.getInstance()
                .getDynamicLink(intent)
                .addOnSuccessListener {
                    Log.i("FirebaseDynamicLinks2", "it: ${it}")
                    if (it != null) {
                        Log.i("FirebaseDynamicLinks2", "We have a dynamic link! ${it.extensions}; ${it.link}")
                        val appLinkData = Constants.Invitation.AppLinkData(it.link!!)
                        viewModel.dashPayProfileData(appLinkData.username).observe(this, { dashPayProfile ->
                            if (dashPayProfile != null) {
                                showPreviewDialog(dashPayProfile)
                            } else {
                                Toast.makeText(this, "Unable to find user ${appLinkData.username}", Toast.LENGTH_LONG).show()
                                log.error("unable to find inviting user ${it.link}")
                            }
                        })
                    }
                }
    }

    private fun showPreviewDialog(dashPayProfile: DashPayProfile) {
        val previewDialog = InvitationPreviewDialog.newInstance(this, dashPayProfile)
        previewDialog.show(supportFragmentManager, null)
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