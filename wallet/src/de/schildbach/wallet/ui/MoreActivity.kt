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
import androidx.core.os.bundleOf
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.buy_sell.BuyAndSellIntegrationsActivity
import de.schildbach.wallet.ui.explore.ExploreActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_more.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.dash.wallet.common.Constants.REQUEST_CODE_BUY_SELL
import org.dash.wallet.common.Constants.RESULT_CODE_GO_HOME
import org.dash.wallet.common.services.analytics.AnalyticsConstants

@FlowPreview
@ExperimentalCoroutinesApi
class MoreActivity : GlobalFooterActivity() {

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

        buy_and_sell.setOnClickListener {
            startBuyAndSellActivity()
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
            alertDialog = ReportIssueDialogBuilder.createReportIssueDialog(this,
                    WalletApplication.getInstance()).buildAlertDialog()
            alertDialog.show()
        }
        explore.setOnClickListener {
            startActivity(Intent(this, ExploreActivity::class.java))
        }
    }

    override fun startActivity(intent: Intent) {
        super.startActivity(intent)
        overridePendingTransition(R.anim.slide_in_right, R.anim.activity_stay)
    }

    private fun startBuyAndSellActivity() {
        analytics.logEvent(AnalyticsConstants.MoreMenu.BUY_SELL_MORE, bundleOf())
        startActivityForResult(BuyAndSellIntegrationsActivity.createIntent(this), REQUEST_CODE_BUY_SELL)
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
