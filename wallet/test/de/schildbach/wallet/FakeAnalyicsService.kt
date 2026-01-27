package de.schildbach.wallet

import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService

// src/test/java/.../FakeAnalyticsService.kt
class FakeAnalyticsService : AnalyticsService {
    override fun logEvent(event: String, params: Map<AnalyticsConstants.Parameter, Any>) {

    }

    override fun logError(error: Throwable, details: String?) {

    }
}
