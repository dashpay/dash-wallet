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

import android.content.*
import android.content.Intent.ACTION_VIEW
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.firebase.installations.FirebaseInstallations
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.ExploreSyncWorker
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.Toast
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_about.*
import kotlinx.coroutines.launch
import org.bitcoinj.core.VersionMessage
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.repository.ExploreRepository
import java.lang.Exception
import javax.inject.Inject

@AndroidEntryPoint
class AboutActivity : BaseMenuActivity() {
    @Inject
    lateinit var analytics: AnalyticsService
    @Inject
    lateinit var firebaseRepository: ExploreRepository

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
        contact_support.setOnClickListener {
            analytics.logEvent(AnalyticsConstants.Settings.ABOUT_SUPPORT, bundleOf())
            handleReportIssue()
        }

        showFirebaseInstallationId()
        showExploreDashSyncStatus()
    }

    private fun showFirebaseInstallationId() {
        FirebaseInstallations.getInstance().id.addOnCompleteListener { task ->
            firebase_installation_id.isVisible = task.isSuccessful && BuildConfig.DEBUG
            if (task.isSuccessful) {
                firebase_installation_id.text = task.result
            }
            firebase_installation_id.setCopyable("Firebase Installation ID")
        }
    }

    private fun showExploreDashSyncStatus() {
        lifecycleScope.launch {
            val formatFlags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME

            val formattedUpdateTime = try {
                val timestamp = firebaseRepository.getLastUpdate()
                DateUtils.formatDateTime(applicationContext, timestamp, formatFlags)
            } catch (ex: Exception) {
                getString(R.string.about_last_explore_dash_update_error)
            }

            val preferences = applicationContext.getSharedPreferences(ExploreSyncWorker.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            val lastSync = preferences.getLong(ExploreSyncWorker.PREFS_LAST_SYNC_KEY, 0)
            val formattedSyncTime = if (lastSync == 0L) {
                getString(R.string.about_last_explore_dash_sync_never)
            } else {
                DateUtils.formatDateTime(applicationContext, lastSync, formatFlags)
            }

            explore_dash_last_sync.text = getString(R.string.about_last_explore_dash_sync, formattedUpdateTime, formattedSyncTime)
            explore_dash_last_sync.setCopyable("Explore Dash last sync")
        }
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
        alertDialog = ReportIssueDialogBuilder.createReportIssueDialog(this,
                WalletApplication.getInstance()).buildAlertDialog()
        alertDialog.show()
    }

    private fun TextView.setCopyable(label: String) {
        this.setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).run {
                setPrimaryClip(ClipData.newPlainText(label, this@setCopyable.text))
            }
            Toast(this@AboutActivity).toast("Copied")
        }
    }
}