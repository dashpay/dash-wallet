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

import android.annotation.SuppressLint
import android.content.Intent
import de.schildbach.wallet.ui.widget.GlobalFooterView
import de.schildbach.wallet_test.R


@SuppressLint("Registered")
open class GlobalFooterActivity : LockScreenActivity(), GlobalFooterView.OnFooterActionListener {

    private lateinit var globalFooterView: GlobalFooterView

    fun setContentViewWithFooter(layoutResId: Int) {
        globalFooterView = GlobalFooterView.encloseContentView(this, layoutResId)
        setContentView(globalFooterView)
        setupFooter()
    }

    fun setContentViewFooter(layoutResId: Int) {
        super.setContentView(layoutResId)
        globalFooterView = findViewById(R.id.global_footer_view)
        setupFooter()
    }

    private fun setupFooter() {
        globalFooterView.onFooterActionListener = this
    }

    override fun onGotoClick() {
        val intent = PaymentsActivity.createIntent(this, PaymentsActivity.ACTIVE_TAB_RECENT)
        startActivity(intent)
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
