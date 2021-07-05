/*
 * Copyright 2019 Dash Core Group
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

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Observer
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet.util.showBlockchainSyncingMessage
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_more.*
import org.dash.wallet.common.Constants.REQUEST_CODE_BUY_SELL
import org.dash.wallet.common.Constants.RESULT_CODE_GO_HOME
import org.dash.wallet.common.UserInteractionAwareCallback



class MoreActivity : GlobalFooterActivity() {

    private var blockchainState: BlockchainState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (walletApplication.wallet == null) {
            finish()
            return
        }

        setContentViewWithFooter(R.layout.activity_more)
        activateMoreButton()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

        setTitle(R.string.more_title)

        AppDatabase.getAppDatabase().blockchainStateDao().load().observe(this, Observer {
            blockchainState = it
        })

        buy_and_sell.setOnClickListener {
            if (blockchainState != null && blockchainState?.replaying!!) {
                showBlockchainSyncingMessage()
            } else {
                startBuyAndSellActivity()
            }
        }
        security.setOnClickListener {
            startActivity(Intent(this, SecurityActivity::class.java))
        }
        settings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        tools.setOnClickListener {
            startActivity(Intent(this, ToolsActivity::class.java))
        }
        contact_support.setOnClickListener {
            val dialog = ReportIssueDialogBuilder.createReportIssueDialog(this,
                    WalletApplication.getInstance()).show()
            dialog.window!!.callback = UserInteractionAwareCallback(dialog.window!!.callback, this)

        }
    }

    override fun startActivity(intent: Intent) {
        super.startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.activity_stay)
    }

    private fun startBuyAndSellActivity() {
        startActivityForResult(BuyAndSellLiquidUpholdActivity.createIntent(this), REQUEST_CODE_BUY_SELL);
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_CODE_GO_HOME) {
            onBackPressed()
        }
    }
}
