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

import android.os.Bundle
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import org.dash.wallet.common.BuildConfig
import javax.inject.Inject

interface AnalyticsService {
    fun logEvent(event: String, params: Bundle)
    fun logError(error: Throwable, details: String? = null)
}

class FirebaseAnalyticsServiceImpl @Inject constructor() : AnalyticsService {
    private val firebaseAnalytics = Firebase.analytics
    private val crashlytics = Firebase.crashlytics

    override fun logEvent(event: String, params: Bundle) {
        if (BuildConfig.DEBUG) {
            return
        }

        try {
            firebaseAnalytics.logEvent(event, params)
        } catch (ex: Exception) {
            logError(ex)
        }
    }

    override fun logError(error: Throwable, details: String?) {
        if (BuildConfig.DEBUG) {
            return
        }

        details?.let { crashlytics.log(details) }
        crashlytics.recordException(error)
    }

    // TODO: remove when merged with DashPay, replace usages to injected service
    companion object {
        private var analyticsService: FirebaseAnalyticsServiceImpl? = null

        fun getInstance(): FirebaseAnalyticsServiceImpl {
            if (analyticsService == null) {
                analyticsService = FirebaseAnalyticsServiceImpl()
            }

            return analyticsService!!
        }
    }
}