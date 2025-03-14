/*
 * Copyright 2019 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.more

import android.annotation.SuppressLint
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivityAboutBinding
import kotlinx.coroutines.launch
import org.bitcoinj.core.VersionMessage
import org.bitcoinj.params.MainNetParams
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.features.exploredash.ExploreSyncWorker
import org.slf4j.LoggerFactory


@AndroidEntryPoint
class AboutActivity : LockScreenActivity() {
    companion object {
        private val log = LoggerFactory.getLogger(AboutActivity::class.java)
    }

    private val viewModel by viewModels<AboutViewModel>()
    private lateinit var binding: ActivityAboutBinding

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        binding.appBar.setNavigationOnClickListener { finish() }

        val appName = getString(R.string.app_name_short)
        binding.title.text = "${getString(R.string.about_title)} $appName"
        binding.appVersionName.text = getString(R.string.about_version_name, BuildConfig.VERSION_NAME)
        binding.buildNumber.text = getString(R.string.about_build_number, BuildConfig.VERSION_CODE % 100)
        binding.libraryVersionName.text = getString(
            R.string.about_credits_bitcoinj_title,
            VersionMessage.BITCOINJ_VERSION
        )
        binding.platformVersionName.text = getString(
            R.string.about_credits_platform_title,
            BuildConfig.DPP_VERSION
        )
        binding.githubLink.setOnClickListener {
            val i = Intent(ACTION_VIEW)
            i.data = Uri.parse(binding.githubLink.text.toString())
            startActivity(i)
        }
        binding.reviewAndRate.setOnClickListener { viewModel.reviewApp() }
        binding.contactSupport.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Settings.ABOUT_SUPPORT)
            handleReportIssue()
        }

        val isMainNet = Constants.NETWORK_PARAMETERS == MainNetParams.get()
        binding.forceSyncBtn.isVisible = !isMainNet
        binding.forceSyncBtn.setOnClickListener {
            try {
                ExploreSyncWorker.run(applicationContext, isMainNet)
            } catch (ex: Exception) {
                log.error(ex.message, ex)
            }
        }

        showExploreDashSyncStatus()
        showFirebaseIds()

        setContentView(binding.root)
    }

    private fun showFirebaseIds() {
        viewModel.firebaseInstallationId.observe(this) {
            binding.firebaseInstallationId.text = it
        }
        binding.firebaseInstallationIdItem.setOnClickListener { viewModel.copyFirebaseInstallationId() }

        viewModel.firebaseCloudMessagingToken.observe(this) {
            binding.fcmToken.text = it
        }
        binding.fcmTokenItem.setOnClickListener { viewModel.copyFCMToken() }
    }

    private fun showExploreDashSyncStatus() {
        val formatFlags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME

        viewModel.exploreRemoteTimestamp.observe(this) { timestamp ->
            binding.lastExploreUpdateLoadingIndicator.isVisible = false
            binding.exploreDashLastServerUpdate.isVisible = true

            val formattedUpdateTime = if (timestamp <= 0L) {
                getString(R.string.about_last_explore_dash_update_error)
            } else {
                DateUtils.formatDateTime(applicationContext, timestamp, formatFlags)
            }

            binding.exploreDashLastServerUpdate.text = formattedUpdateTime
        }

        viewModel.exploreIsSyncing.observe(this) { isSyncing ->
            lifecycleScope.launch {
                val dbPrefs = viewModel.databasePrefs
                binding.exploreDashLastDeviceSync.text = if (isSyncing) {
                    "${getString(R.string.syncing)}…"
                } else if (dbPrefs.failedSyncAttempts > 0) {
                    getString(
                        R.string.about_explore_failed_sync,
                        DateUtils.formatDateTime(applicationContext, dbPrefs.lastSyncTimestamp, formatFlags)
                    )
                } else if (dbPrefs.preloadedOnTimestamp >= dbPrefs.lastSyncTimestamp) {
                    val prefix = if (dbPrefs.preloadedTestDb) "Testnet DB " else ""
                    prefix + getString(
                        R.string.about_explore_preloaded_on,
                        DateUtils.formatDateTime(applicationContext, dbPrefs.preloadedOnTimestamp, formatFlags)
                    )
                } else {
                    DateUtils.formatDateTime(applicationContext, dbPrefs.lastSyncTimestamp, formatFlags)
                }
            }
        }
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.activity_stay, R.anim.slide_out_left)
    }

    private fun handleReportIssue() {
        if (!isFinishing) {
            ContactSupportDialogFragment.newInstance(
                getString(R.string.report_issue_dialog_title_issue),
                getString(R.string.report_issue_dialog_message_issue),
            ).show(this)
        }
    }
}
