package org.dash.wallet.common.util

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

/**
 * @author Joffrey Bion
 * https://stackoverflow.com/a/54828055/2279177
 */
@FlowPreview
@ExperimentalTime
class TickerFlow(private val period: Duration, private val initialDelay: Duration = Duration.ZERO): AbstractFlow<Unit>() {
    override suspend fun collectSafely(collector: FlowCollector<Unit>) {
        delay(initialDelay)
        while (true) {
            collector.emit(Unit)
            delay(period)
        }
    }

}