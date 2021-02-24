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
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
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
                val title = activity.getString(R.string.invitation_cant_afford_title)
                val message = activity.getString(R.string.invitation_cant_afford_message, Constants.DASH_PAY_FEE)
                val errorDialog = FancyAlertDialog.newInstance(title, message,
                        R.drawable.ic_cant_afford_invitation, 0, R.string.invitation_preview_close)
                errorDialog.show(activity.supportFragmentManager, null)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_holder)

        supportFragmentManager.beginTransaction()
                .replace(R.id.container, InviteFriendFragment.newInstance())
                .commitNow()
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