/*
 * Copyright 2021 Dash Core Group
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

package org.dash.wallet.common.services.analytics

import android.os.Bundle
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase

interface AnalyticsService {
    fun logEvent(event: String, params: Bundle)
    fun logError(error: Throwable, details: String? = null)
}

class FirebaseAnalyticsServiceImpl: AnalyticsService {
    private val firebaseAnalytics = Firebase.analytics
    private val crashlytics = Firebase.crashlytics

    override fun logEvent(event: String, params: Bundle) {
        try {
            firebaseAnalytics.logEvent(event, params)
        } catch (ex: Exception) {
            logError(ex)
        }
    }

    override fun logError(error: Throwable, details: String?) {
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