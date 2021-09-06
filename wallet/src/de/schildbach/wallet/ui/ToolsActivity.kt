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
import de.schildbach.wallet.ui.send.SweepWalletActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_tools.*
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl

class ToolsActivity : BaseMenuActivity() {
    private val analytics = FirebaseAnalyticsServiceImpl.getInstance()

    override fun getLayoutId(): Int {
        return R.layout.activity_tools
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.tools_title)
        address_book.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.Settings.ADDRESS_BOOK, bundleOf())
            startActivity(Intent(this, AddressBookActivity::class.java))
        }
        import_keys.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.Settings.IMPORT_PRIVATE_KEY, bundleOf())
            startActivity(Intent(this, SweepWalletActivity::class.java))
        }
        network_monitor.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.Settings.NETWORK_MONITORING, bundleOf())
            startActivity(Intent(this, NetworkMonitorActivity::class.java))
        }
    }
}
