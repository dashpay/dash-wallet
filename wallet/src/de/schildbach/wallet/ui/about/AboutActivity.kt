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

package de.schildbach.wallet.ui.about

import android.content.*
import android.content.Intent.ACTION_VIEW
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.TextView
import android.widget.Toast
import android.widget.Toast.LENGTH_LONG
import androidx.activity.viewModels
import androidx.core.view.isVisible
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.BaseMenuActivity
import de.schildbach.wallet.ui.ReportIssueDialogBuilder
import de.schildbach.wallet_test.BuildConfig
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.activity_about.*
import org.bitcoinj.core.VersionMessage
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.slf4j.LoggerFactory
import kotlin.math.pow
import kotlin.math.sqrt

@AndroidEntryPoint
class AboutActivity : BaseMenuActivity(), SensorEventListener {
    // variables for shake detection
    private val SHAKE_THRESHOLD = 1.50f // m/S**2

    private val MIN_TIME_BETWEEN_SHAKES_MILLISECS = 1000
    private var mLastShakeTime: Long = 0
    private lateinit var mSensorMgr: SensorManager

    companion object {
        private val log = LoggerFactory.getLogger(AboutActivity::class.java)
    }

    private val viewModel by viewModels<AboutViewModel>()

    override fun getLayoutId(): Int {
        return R.layout.activity_about
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.about_title)
        app_version_name.text = getString(R.string.about_version_name, BuildConfig.VERSION_NAME)
        app_version_code.text = getString(R.string.about_version_extra, BuildConfig.VERSION_CODE, BuildConfig.FLAVOR)
        library_version_name.text = getString(R.string.about_credits_bitcoinj_title,
                VersionMessage.BITCOINJ_VERSION)

        github_link.setOnClickListener {
            val i = Intent(ACTION_VIEW)
            i.data = Uri.parse(github_link.text.toString())
            startActivity(i)
        }
        review_and_rate.setOnClickListener { openReviewAppIntent() }
        contact_support.setOnClickListener {
            viewModel.logEvent(AnalyticsConstants.Settings.ABOUT_SUPPORT)
            handleReportIssue()
        }

        showFirebaseIds()
        showExploreDashSyncStatus()

        // Get a sensor manager to listen for shakes
        mSensorMgr = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    private fun showFirebaseIds() {
        firebase_installation_id.setCopyable("Firebase Installation ID")
        fcm_token.setCopyable("FCM token")

        viewModel.firebaseInstallationId.observe(this) {
            firebase_installation_id.isVisible = it.isNotEmpty()
            firebase_installation_id.text = it
        }

        viewModel.firebaseCloudMessagingToken.observe(this) {
            fcm_token.isVisible = it.isNotEmpty()
            fcm_token.text = it
        }
    }

    private fun showExploreDashSyncStatus() {
        val formatFlags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME
        explore_dash_last_sync.setCopyable("Explore Dash last sync")

        viewModel.exploreRemoteTimestamp.observe(this) { timestamp ->
            val formattedUpdateTime = if (timestamp <= 0L) {
                getString(R.string.about_last_explore_dash_update_error)
            } else {
                DateUtils.formatDateTime(applicationContext, timestamp, formatFlags)
            }

            val formattedSyncTime = if (viewModel.exploreLastSync <= 0L) {
                getString(R.string.about_last_explore_dash_sync_never)
            } else {
                DateUtils.formatDateTime(applicationContext, viewModel.exploreLastSync, formatFlags)
            }

            explore_dash_last_sync.text = getString(R.string.about_last_explore_dash_sync, formattedUpdateTime, formattedSyncTime)
        }
    }

    override fun onResume() {
        super.onResume()
        // Listen for shakes
        val accelerometer = mSensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            mSensorMgr.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        // stop listening for shakes
        val accelerometer = mSensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelerometer != null) {
            mSensorMgr.unregisterListener(this)
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
        alertDialog = ReportIssueDialogBuilder.createReportIssueDialog(
            this,
            WalletApplication.getInstance()
        ).buildAlertDialog()
        alertDialog.show()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event != null && event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val curTime = System.currentTimeMillis()
            if (curTime - mLastShakeTime > MIN_TIME_BETWEEN_SHAKES_MILLISECS) {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                val acceleration = sqrt(x.toDouble().pow(2.0) +
                        y.toDouble().pow(2.0) +
                        z.toDouble().pow(2.0)) - SensorManager.GRAVITY_EARTH

                if (acceleration > SHAKE_THRESHOLD) {
                    mLastShakeTime = curTime
                    log.info("Shake detected: developer mode changing to ${!configuration.developerMode}")
                    configuration.developerMode = if (!configuration.developerMode) {
                        Toast.makeText(this, R.string.about_developer_mode, LENGTH_LONG).show()
                        true
                    } else {
                        Toast.makeText(this, R.string.about_developer_mode_disabled, LENGTH_LONG).show()
                        false
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Ignore
    }

    private fun TextView.setCopyable(label: String) {
        this.setOnClickListener {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).run {
                setPrimaryClip(ClipData.newPlainText(label, this@setCopyable.text))
            }
            Toast.makeText(this@AboutActivity, R.string.copied, LENGTH_LONG).show()
        }
    }
}