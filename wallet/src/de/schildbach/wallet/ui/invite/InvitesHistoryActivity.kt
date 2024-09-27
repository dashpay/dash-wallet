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

package de.schildbach.wallet.ui.invite

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet_test.R
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.slf4j.LoggerFactory

@AndroidEntryPoint
class InvitesHistoryActivity : LockScreenActivity() {

    companion object {
        private val log = LoggerFactory.getLogger(InvitesHistoryActivity::class.java)

        @JvmStatic
        fun createIntent(context: Context): Intent {
            return Intent(context, InvitesHistoryActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fragment_holder)

        val caller = intent.getStringExtra(AnalyticsConstants.CALLING_ACTIVITY) ?: ""
        supportFragmentManager.beginTransaction()
                .replace(R.id.container, InvitesHistoryFragment.newInstance(caller))
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