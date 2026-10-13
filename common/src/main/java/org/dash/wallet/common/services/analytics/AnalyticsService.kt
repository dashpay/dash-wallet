/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.common.services.analytics

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.os.bundleOf
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import org.dash.wallet.common.BuildConfig
import org.dash.wallet.common.Configuration
import javax.inject.Inject

interface AnalyticsService {
    /**
     * Whether the user allows analytics event collection (Settings, MO-1065;
     * on by default). While false, [logEvent] drops every event and the
     * analytics SDK's own automatic events are switched off too.
     * [logError] (crash reporting) is not affected. Change it with [setEnabled].
     */
    val isEnabled: Boolean

    /**
     * Persists the user's choice and applies it to the analytics SDK.
     *
     * @return false when the choice could not be saved. An opt-out still stops
     * collection for this session (it may resume after a restart); an opt-in
     * leaves collection off. Calling again retries the write.
     */
    fun setEnabled(enabled: Boolean): Boolean

    /** Applies the stored choice to the analytics SDK without writing it (app startup). */
    fun applyStoredSetting()

    /**
     * Whether analytics can run at all in this build. False when Firebase was
     * never configured (built without google-services.json, whether or not
     * WalletApplication then initialized its placeholder FirebaseApp with
     * [PLACEHOLDER_FIREBASE_PROJECT_ID]): every call is a no-op then, so there
     * is nothing for the user to opt out of. Devices
     * without Google Play services are still available — Firebase Analytics
     * uploads events without it.
     */
    val isAvailable: Boolean

    fun logEvent(event: String, params: Map<AnalyticsConstants.Parameter, Any>)
    fun logError(error: Throwable, details: String? = null)

    companion object {
        /**
         * Project ID of the placeholder FirebaseApp that WalletApplication
         * initializes when the build has no google-services.json. Analytics
         * treats that app as "not configured".
         */
        const val PLACEHOLDER_FIREBASE_PROJECT_ID = "dash-wallet-local-build"
    }
}

class FirebaseAnalyticsServiceImpl @VisibleForTesting constructor(
    private val configuration: Configuration,
    // Debug builds log events and errors to logcat instead of Firebase.
    // Injectable only so tests can reach the forwarding path.
    private val isDebug: Boolean
) : AnalyticsService {
    @Inject constructor(configuration: Configuration) : this(configuration, BuildConfig.DEBUG)

    // Firebase is only configured when the build included google-services.json
    // (see gradle/google-services.gradle). Builds without it must not crash —
    // analytics simply no-ops. Resolved lazily so construction never throws.
    //
    // Never resolve it before FirebaseApp.initializeApp has run (WalletApplication
    // applies [isEnabled] right after that stage): a premature failure would be
    // cached as null for the life of this instance.
    private val firebaseAnalytics by lazy {
        try {
            if (FirebaseApp.getInstance().options.projectId == AnalyticsService.PLACEHOLDER_FIREBASE_PROJECT_ID) {
                Log.w("FIREBASE", "placeholder FirebaseApp (built without google-services.json); analytics disabled")
                return@lazy null
            }
            // No collection-setting side effect here: instances are unscoped, so a
            // background instance resolving late could overwrite a newer opt-out.
            // The setting is applied only through [setEnabled] / [applyStoredSetting] (WalletApplication
            // applies the stored value right after Firebase initializes).
            Firebase.analytics
        } catch (ex: IllegalStateException) {
            Log.w("FIREBASE", "FirebaseApp not initialized (built without google-services.json); analytics disabled")
            null
        }
    }
    private val crashlytics by lazy {
        try {
            Firebase.crashlytics.also { it.setCrashlyticsCollectionEnabled(!isDebug) }
        } catch (ex: IllegalStateException) {
            null
        }
    }

    override val isEnabled: Boolean
        get() = configuration.analyticsEnabled

    override fun setEnabled(enabled: Boolean): Boolean {
        // Always write, even when the value looks unchanged: after a failed write the
        // in-memory value already matches, and a retry must still reach disk.
        val persisted = configuration.setAnalyticsEnabled(enabled)
        if (!persisted) {
            Log.w("FIREBASE", "failed to persist analytics setting: $enabled")
            if (enabled) {
                // commit() updates memory even when the disk write fails; an unsaved
                // opt-in must not read back as on.
                configuration.setAnalyticsEnabled(false)
            }
        }
        // An opt-out applies for this session even if the write failed; an opt-in
        // only once it is saved, so collection never outruns the saved choice.
        applyCollection(enabled && persisted)
        return persisted
    }

    override fun applyStoredSetting() = applyCollection(isEnabled)

    private fun applyCollection(enabled: Boolean) {
        try {
            firebaseAnalytics?.setAnalyticsCollectionEnabled(enabled)
        } catch (ex: Exception) {
            Log.w("FIREBASE", "failed to apply analytics collection setting", ex)
        }
    }

    override val isAvailable: Boolean
        get() = firebaseAnalytics != null

    override fun logEvent(event: String, params: Map<AnalyticsConstants.Parameter, Any>) {
        if (!isEnabled) {
            return
        }

        if (isDebug) {
            Log.i("FIREBASE", "Skip event logging in debug mode: $event")

            if (params.isNotEmpty()) {
                Log.i("FIREBASE", "Parameters: ${params.keys.joinToString("; ") { "${it.paramName}: ${params[it]}" } }")
            }

            return
        }

        try {
            firebaseAnalytics?.logEvent(event, bundleOf(*params.map { it.key.paramName to it.value }.toTypedArray()))
        } catch (ex: Exception) {
            logError(ex)
        }
    }

    override fun logError(error: Throwable, details: String?) {
        if (isDebug) {
            Log.i("FIREBASE", "Skip error logging in debug mode: $error")
            details?.let { Log.i("FIREBASE", "Details: $details") }
            return
        }

        details?.let { crashlytics?.log(details) }
        crashlytics?.recordException(error)
    }
}
