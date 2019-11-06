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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.net.Uri
import android.os.Bundle
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_about.*
import org.bitcoinj.core.VersionMessage


class AboutActivity : BaseMenuActivity() {

    override fun getLayoutId(): Int {
        return R.layout.activity_about
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.about_title)
        app_version_name.text = getString(R.string.about_version_name, BuildConfig.VERSION_NAME)
        library_version_name.text = getString(R.string.about_credits_bitcoinj_title,
                VersionMessage.BITCOINJ_VERSION)

        github_link.setOnClickListener {
            val i = Intent(ACTION_VIEW)
            i.data = Uri.parse(github_link.text.toString())
            startActivity(i)
        }
        review_and_rate.setOnClickListener { openReviewAppIntent() }
        contact_support.setOnClickListener { handleReportIssue() }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_left)
    }

    private fun openReviewAppIntent() {
        val uri = Uri.parse("market://details?id=$packageName")
        val goToMarket = Intent(ACTION_VIEW, uri)
        // To count with Play market backstack, After pressing back button,
        // and go back to our application, we need to add following flags to intent.
        goToMarket.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY or
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        try {
            startActivity(goToMarket)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(ACTION_VIEW,
                    Uri.parse("http://play.google.com/store/apps/details?id=$packageName")))
        }
    }

    private fun handleReportIssue() {
        ReportIssueDialogBuilder.createReportIssueDialog(this,
                WalletApplication.getInstance()).show()
    }

}