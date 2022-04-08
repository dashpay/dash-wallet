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

    fun logTiming(vararg args: Pair<String, Any?>) {

        val bundle = Bundle()
        bundle.putDouble("time", stopWatch.elapsed(TimeUnit.MILLISECONDS).toDouble()/1000)

        args.forEach {
            when (it.second) {
                is Long -> bundle.putLong(it.first, it.second as Long)
                is Int -> bundle.putLong(it.first, (it.second as Int).toLong())
            }
        }
        analyticsService.logEvent(event, bundle)
        logger.info("event:$event: $stopWatch; ${args.map { "${it.first}:${it.second}"} }")
    }
}