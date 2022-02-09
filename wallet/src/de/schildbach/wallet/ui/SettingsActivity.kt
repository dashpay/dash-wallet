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
import androidx.core.os.bundleOf
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_settings.*
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl
import org.slf4j.LoggerFactory
import android.app.Activity
import de.schildbach.wallet.ui.ExchangeRatesFragment.*
import org.dash.wallet.common.data.ExchangeRate


class SettingsActivity : BaseMenuActivity() {

    private val log = LoggerFactory.getLogger(SettingsActivity::class.java)
    private val analytics = FirebaseAnalyticsServiceImpl.getInstance()
    companion object Constants {
        private const val RC_DEFAULT_FIAT_CURRENCY_SELECTED: Int = 100
    }
    override fun getLayoutId(): Int {
        return R.layout.activity_settings
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTitle(R.string.settings_title)
        about.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.Settings.ABOUT, bundleOf())
            startActivity(Intent(this, AboutActivity::class.java))
        }
        local_currency.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.Settings.LOCAL_CURRENCY, bundleOf())
            val intent = Intent(this, ExchangeRatesActivity::class.java)
            intent.putExtra(ARG_SHOW_AS_DIALOG, false)
            intent.putExtra(ARG_CURRENCY_CODE, configuration.exchangeCurrencyCode)
            startActivityForResult(intent, RC_DEFAULT_FIAT_CURRENCY_SELECTED)
        }
        rescan_blockchain.setOnClickListener { resetBlockchain() }
    }

    override fun onStart() {
        super.onStart()
        local_currency_symbol.text = WalletApplication.getInstance()
                .configuration.exchangeCurrencyCode
    }

    private fun resetBlockchain() {
        var isFinished = false
        alertDialog = baseAlertDialogBuilder.apply {
            title = getString(R.string.preferences_initiate_reset_title)
            message = getString(R.string.preferences_initiate_reset_dialog_message)
            positiveText = getString(R.string.preferences_initiate_reset_dialog_positive)
            positiveAction = {
                isFinished = true
                log.info("manually initiated blockchain reset")
                analytics.logEvent(AnalyticsConstants.Settings.RESCAN_BLOCKCHAIN_RESET, bundleOf())

                WalletApplication.getInstance().resetBlockchain()
                WalletApplication.getInstance().configuration.updateLastBlockchainResetTime()
                startActivity(WalletActivity.createIntent(this@SettingsActivity))
            }
            negativeText = getString(R.string.button_dismiss)
            dismissAction = {
                if (!isFinished) {
                    analytics.logEvent(AnalyticsConstants.Settings.RESCAN_BLOCKCHAIN_DISMISS, bundleOf())
                }
            }
        }.buildAlertDialog()
        alertDialog.show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if(resultCode == Activity.RESULT_OK && requestCode == RC_DEFAULT_FIAT_CURRENCY_SELECTED){
            val exchangeRate: ExchangeRate? = data?.getParcelableExtra(BUNDLE_EXCHANGE_RATE)
            local_currency_symbol.text = if(exchangeRate != null) exchangeRate.currencyCode else
                WalletApplication.getInstance().configuration.exchangeCurrencyCode
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
