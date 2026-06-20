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

import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.Constants
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet_test.R
import org.bitcoinj.core.NetworkParameters
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.features.exploredash.ExploreSyncWorker
import org.slf4j.LoggerFactory

@AndroidEntryPoint
class AboutFragment : Fragment() {
    companion object {
        private val log = LoggerFactory.getLogger(AboutFragment::class.java)
        private val FORMAT_FLAGS =
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME
    }

    private val viewModel by viewModels<AboutViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val state by viewModel.uiState.collectAsState()

                val deviceSyncStatus = remember(state.exploreIsSyncing, viewModel.databasePrefs) {
                    formatDeviceSyncStatus(state.exploreIsSyncing)
                }
                val serverUpdateStatus = state.exploreRemoteTimestamp?.let { formatServerUpdate(it) }
                val network = when (Constants.NETWORK_PARAMETERS.id) {
                    NetworkParameters.ID_MAINNET -> ""
                    NetworkParameters.ID_TESTNET -> " - testnet"
                    NetworkParameters.ID_REGTEST -> " - regtest"
                    else -> " - ${Constants.NETWORK_PARAMETERS.devNetName}"
                }
                val appVersion = "${BuildConfig.VERSION_NAME} " +
                    getString(R.string.about_build_number, BuildConfig.VERSION_CODE % 100) + network

                AboutScreen(
                    uiState = AboutUIState(
                        versionName = appVersion,
                        dashjVersion = BuildConfig.DASHJ_VERSION,
                        platformVersion = BuildConfig.DPP_VERSION,
                        deviceSyncStatus = deviceSyncStatus,
                        serverUpdateStatus = serverUpdateStatus,
                        firebaseInstallationId = state.firebaseInstallationId,
                        fcmToken = state.firebaseCloudMessagingToken,
                        showForceSyncButton = !state.isMainNet,
                        isMainNet = state.isMainNet,
                        copyrightYear = BuildConfig.COMMIT_YEAR
                    ),
                    onBackClick = { findNavController().popBackStack() },
                    onForceSyncClick = { forceSync() },
                    onFirebaseInstallationIdClick = { viewModel.copyFirebaseInstallationId() },
                    onFcmTokenClick = { viewModel.copyFCMToken() },
                    onGithubLinkClick = { openGithubLink() },
                    onReviewAndRateClick = { viewModel.reviewApp() },
                    onContactSupportClick = {
                        viewModel.logEvent(AnalyticsConstants.Settings.ABOUT_SUPPORT)
                        handleReportIssue()
                    }
                )
            }
        }
    }

    private fun formatDeviceSyncStatus(isSyncing: Boolean): String {
        val dbPrefs = viewModel.databasePrefs

        return when {
            isSyncing -> "${getString(R.string.syncing)}…"
            dbPrefs.failedSyncAttempts > 0 -> getString(
                R.string.about_explore_failed_sync,
                formatTimestampOrNever(dbPrefs.lastSyncTimestamp)
            )
            dbPrefs.preloadedOnTimestamp >= dbPrefs.lastSyncTimestamp -> {
                val prefix = if (dbPrefs.preloadedTestDb) "Testnet DB " else ""
                prefix + getString(
                    R.string.about_explore_preloaded_on,
                    formatTimestampOrNever(dbPrefs.preloadedOnTimestamp)
                )
            }
            else -> formatTimestampOrNever(dbPrefs.lastSyncTimestamp)
        }
    }

    /** Formats [timestamp] as a date/time, or "never" when it is unset (≤ 0). */
    private fun formatTimestampOrNever(timestamp: Long): String =
        if (timestamp > 0L) {
            DateUtils.formatDateTime(requireContext().applicationContext, timestamp, FORMAT_FLAGS)
        } else {
            getString(R.string.about_last_explore_dash_sync_never)
        }

    private fun formatServerUpdate(timestamp: Long): String {
        return if (timestamp <= 0L) {
            getString(R.string.about_last_explore_dash_update_error)
        } else {
            DateUtils.formatDateTime(requireContext().applicationContext, timestamp, FORMAT_FLAGS)
        }
    }

    private fun forceSync() {
        try {
            ExploreSyncWorker.run(requireContext().applicationContext, viewModel.uiState.value.isMainNet)
        } catch (ex: Exception) {
            log.error(ex.message, ex)
        }
    }

    private fun openGithubLink() {
        val intent = Intent(ACTION_VIEW)
        intent.data = getString(R.string.about_github_link).toUri()
        val packageManager = requireContext().packageManager
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        }
    }

    private fun handleReportIssue() {
        if (!requireActivity().isFinishing) {
            ContactSupportDialogFragment.newInstance(
                getString(R.string.report_issue_dialog_title_issue),
                getString(R.string.report_issue_dialog_message_issue),
            ).show(requireActivity())
        }
    }
}
