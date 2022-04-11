package org.dash.wallet.common.services.analytics

import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.common.base.Stopwatch
import org.slf4j.Logger
import java.util.concurrent.TimeUnit

class AnalyticsTimer (val analyticsService: AnalyticsService, val logger: Logger, val event: String) {
    private val stopWatch = Stopwatch.createStarted()

    fun logTiming() {
        analyticsService.logEvent(event, bundleOf(Pair("time", stopWatch.elapsed(TimeUnit.MILLISECONDS).toDouble()/1000)))
        logger.info("event:$event: $stopWatch")
    }

    fun logTiming(args: Map<String, Any>) {

        val bundle = Bundle()
        bundle.putDouble("time", stopWatch.elapsed(TimeUnit.MILLISECONDS).toDouble()/1000)

        // not all types are supported
        args.forEach { (key, value) ->
            when (value) {
                is Long -> bundle.putLong(key, value)
                is Int -> bundle.putInt(key, value)
                is String -> bundle.putString(key, value)
                is Float -> bundle.putFloat(key, value)
                is Double -> bundle.putDouble(key, value)
            }
        }
        analyticsService.logEvent(event, bundle)
        logger.info("event:$event: $stopWatch; ${args.map { "${it.key}:${it.value}"} }")
    }
}