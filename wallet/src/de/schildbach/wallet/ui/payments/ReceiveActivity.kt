/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.ui.payments

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.EnterAmountFragment
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet.ui.send.EnterAmountSharedViewModel
import de.schildbach.wallet_test.R
import org.bitcoinj.core.Coin

@AndroidEntryPoint
class ReceiveActivity : LockScreenActivity() {

    companion object {

        @JvmStatic
        fun createIntent(context: Context): Intent {
            return Intent(context, ReceiveActivity::class.java).apply {
                putExtra(INTENT_EXTRA_KEEP_UNLOCKED, true)
            }
        }
    }

    private val enterAmountSharedViewModel by viewModels<EnterAmountSharedViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receive)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                    .replace(R.id.container, EnterAmountFragment.newInstance(Coin.ZERO))
                    .commitNow()
        }

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        setTitle(R.string.receive_title)

        enterAmountSharedViewModel.maxButtonVisibleData.value = false
        enterAmountSharedViewModel.buttonTextData.call(R.string.receive_title)
        enterAmountSharedViewModel.messageTextData.value = R.string.receive_enter_amount_message
        enterAmountSharedViewModel.buttonClickEvent.observe(this) {
            val dashAmount = enterAmountSharedViewModel.dashAmount
            val fiatAmount = enterAmountSharedViewModel.exchangeRate?.coinToFiat(dashAmount)
            val address = enterAmountSharedViewModel.receiveAddress
            val dialogFragment = ReceiveDetailsDialog.createDialog(address, dashAmount, fiatAmount)
            dialogFragment.show(supportFragmentManager, "ReceiveDetailsDialog")
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }

        return super.onOptionsItemSelected(item)
    }
}
