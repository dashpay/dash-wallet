package org.dash.wallet.common.services.analytics

import com.google.common.base.Stopwatch
import org.slf4j.Logger
import java.util.concurrent.TimeUnit

class AnalyticsTimer(val analyticsService: AnalyticsService, val logger: Logger, val event: String) {
    private val stopWatch = Stopwatch.createStarted()

    fun logTiming() {
        analyticsService.logEvent(
            event,
            mapOf(
                AnalyticsConstants.Parameter.TIME to
                    stopWatch.elapsed(TimeUnit.MILLISECONDS).toDouble() / 1000
            )
        )
        logger.info("event:$event: $stopWatch")
    }

    fun logTiming(args: Map<AnalyticsConstants.Parameter, Any>) {
        val mutable = args.toMutableMap()
        mutable[AnalyticsConstants.Parameter.TIME] = stopWatch.elapsed(TimeUnit.MILLISECONDS).toDouble() / 1000
        analyticsService.logEvent(event, args)
        logger.info("event:$event: $stopWatch; ${args.map { "${it.key}:${it.value}"} }")
    }
}
