/*
 * Copyright 2020 Dash Core Group
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

package de.schildbach.wallet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_fund_new_account.*
import org.dash.wallet.common.InteractionAwareActivity
import android.widget.Toast
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import de.schildbach.wallet.ui.dashpay.NewAccountConfirmDialog

class FundNewAccountActivity : InteractionAwareActivity() {

    companion object {

        @JvmStatic
        fun createIntent(context: Context): Intent {
            return Intent(context, FundNewAccountActivity::class.java)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fund_new_account)

        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        title = null

        register.setOnClickListener {
            val dialog = NewAccountConfirmDialog.createDialog()
            dialog.show(supportFragmentManager, "NewAccountConfirmDialog")
        }

        val confirmTransactionSharedViewModel: SingleActionSharedViewModel = ViewModelProviders.of(this).get(SingleActionSharedViewModel::class.java)
        confirmTransactionSharedViewModel.clickConfirmButtonEvent.observe(this, Observer {
            Toast.makeText(this, "Not yet implemented", Toast.LENGTH_LONG).show()
        })
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
