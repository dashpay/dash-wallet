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
import androidx.core.os.bundleOf
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import org.dash.wallet.common.BuildConfig
import javax.inject.Inject

interface AnalyticsService {
    fun logEvent(event: String, params: Map<AnalyticsConstants.Parameter, Any>)
    fun logError(error: Throwable, details: String? = null)
}

class FirebaseAnalyticsServiceImpl @Inject constructor() : AnalyticsService {
    private val firebaseAnalytics = Firebase.analytics
    private val crashlytics = Firebase.crashlytics

    init {
        crashlytics.setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)
    }

    override fun logEvent(event: String, params: Map<AnalyticsConstants.Parameter, Any>) {
        if (BuildConfig.DEBUG) {
            Log.i("FIREBASE", "Skip event logging in debug mode: $event")

            if (params.isNotEmpty()) {
                Log.i("FIREBASE", "Parameters: ${params.keys.joinToString("; ") { "${it.paramName}: ${params[it]}" } }")
            }

            return
        }

        try {
            firebaseAnalytics.logEvent(event, bundleOf(*params.map { it.key.paramName to it.value }.toTypedArray()))
        } catch (ex: Exception) {
            logError(ex)
        }
    }

    override fun logError(error: Throwable, details: String?) {
        if (BuildConfig.DEBUG) {
            Log.i("FIREBASE", "Skip error logging in debug mode: $error")
            details?.let { Log.i("FIREBASE", "Details: $details") }
            return
        }

        details?.let { crashlytics.log(details) }
        crashlytics.recordException(error)
    }
}
