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
import androidx.appcompat.widget.Toolbar
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_more.*
import kotlinx.android.synthetic.main.activity_settings.*
import org.dash.wallet.common.ui.DialogBuilder
import org.slf4j.LoggerFactory

class SettingsActivity : BaseMenuActivity() {

    private val log = LoggerFactory.getLogger(SettingsActivity::class.java)

    override fun getLayoutId(): Int {
        return R.layout.activity_settings
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.settings_title)
        about.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
        local_currency.setOnClickListener {
            startActivity(Intent(this, ExchangeRatesActivity::class.java))
        }
        rescan_blockchain.setOnClickListener { resetBlockchain() }
    }

    override fun onStart() {
        super.onStart()
        local_currency_symbol.text = WalletApplication.getInstance()
                .configuration.exchangeCurrencyCode
    }

    private fun resetBlockchain() {
        val dialog = DialogBuilder(this)
        dialog.setTitle(R.string.preferences_initiate_reset_title)
        dialog.setMessage(R.string.preferences_initiate_reset_dialog_message)
        dialog.setPositiveButton(R.string.preferences_initiate_reset_dialog_positive) { dialog, which ->
            log.info("manually initiated blockchain reset")

            WalletApplication.getInstance().resetBlockchain()
            startActivity(WalletActivity.createIntent(this))
        }
        dialog.setNegativeButton(R.string.button_dismiss, null)
        dialog.show()
    }

}
