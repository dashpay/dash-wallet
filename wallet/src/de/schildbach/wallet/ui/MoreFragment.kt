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
import android.view.View
import android.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import de.schildbach.wallet.AppDatabase
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.BlockchainState
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_more.*
import kotlinx.android.synthetic.main.activity_payments.*
import org.dash.wallet.integration.uphold.ui.UpholdAccountActivity

class MoreFragment : Fragment(R.layout.activity_more) {

    private var blockchainState: BlockchainState? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        //setSupportActionBar(toolbar)
        /*
        val actionBar = supportActionBar
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
        }

         */

        toolbar.setTitle(R.string.more_title)

        AppDatabase.getAppDatabase().blockchainStateDao().load().observe(viewLifecycleOwner, Observer {
            blockchainState = it
        })

        buy_and_sell.setOnClickListener {
            if (blockchainState != null && blockchainState?.replaying!!) {
                //TODO: ???
                //showBlockchainSyncingMessage()
            } else {
                startBuyAndSellActivity()
            }
        }
        security.setOnClickListener {
            startActivity(Intent(requireContext(), SecurityActivity::class.java))
        }
        settings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        tools.setOnClickListener {
            startActivity(Intent(requireContext(), ToolsActivity::class.java))
        }
        contact_support.setOnClickListener {
            ReportIssueDialogBuilder.createReportIssueDialog(requireContext(),
                    WalletApplication.getInstance()).show()
        }
    }

    override fun startActivity(intent: Intent) {
        super.startActivity(intent)
        requireActivity().overridePendingTransition(R.anim.slide_in_right, R.anim.activity_stay)
    }

    private fun startBuyAndSellActivity() {
        val wallet = WalletApplication.getInstance().wallet
        startActivity(UpholdAccountActivity.createIntent(requireContext(), wallet))
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        //TODO: AppBar
        /*
        when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                return true
            }
        }
         */
        return super.onOptionsItemSelected(item)
    }

}
