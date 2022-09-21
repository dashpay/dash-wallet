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
import androidx.core.os.bundleOf
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ui.main.WalletActivity
import de.schildbach.wallet.ui.payments.PaymentsActivity
import de.schildbach.wallet.ui.widget.GlobalFooterView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@FlowPreview
@AndroidEntryPoint
@ExperimentalCoroutinesApi
open class GlobalFooterActivity : LockScreenActivity(), GlobalFooterView.OnFooterActionListener {

    private lateinit var globalFooterView: GlobalFooterView

    @Inject
    lateinit var analytics: AnalyticsService

    fun setContentViewWithFooter(layoutResId: Int) {
        globalFooterView = GlobalFooterView.encloseContentView(this, layoutResId)
        setContentView(globalFooterView)
        setupFooter()
    }

    private fun setupFooter() {
        globalFooterView.onFooterActionListener = this
    }

    override fun onGotoClick() {
        val intent = PaymentsActivity.createIntent(this)
        startActivity(intent)
        analytics.logEvent(AnalyticsConstants.Home.SEND_RECEIVE_BUTTON, bundleOf())
    }

    override fun onMoreClick() {
        val intent = Intent(this, MoreActivity::class.java)
        startActivity(intent)
    }

    override fun onHomeClick() {
        val intent = Intent(this, WalletActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    fun activateHomeButton() {
        globalFooterView.activateHomeButton(true)
    }

    fun activateMoreButton() {
        globalFooterView.activateMoreButton(true)
    }

    fun activateGotoButton() {
        globalFooterView.activateGotoButton(true)
    }
}
