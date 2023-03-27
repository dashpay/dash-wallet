package org.dash.wallet.common.util

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlin.time.Duration

/**
 * @author Joffrey Bion
 * https://stackoverflow.com/a/54828055/2279177
 */
@OptIn(FlowPreview::class)
class TickerFlow(
    private val period: Duration,
    private val initialDelay: Duration = Duration.ZERO
): AbstractFlow<Unit>() {
    override suspend fun collectSafely(collector: FlowCollector<Unit>) {
        delay(initialDelay)
        while (true) {
            collector.emit(Unit)
            delay(period)
        }
    }
}
