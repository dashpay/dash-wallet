package org.dash.wallet.common.util

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.AbstractFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.launch
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

/**
 * @author Tobias Preuss @johnjohndoe
 * https://github.com/EventFahrplan/EventFahrplan/blob/master/commons/src/main/java/info/metadude/android/eventfahrplan/commons/flow/FlowExtensions.kt
 */
fun <T> Flow<T>.observe(lifecycleOwner: LifecycleOwner, collector: FlowCollector<T>) {
    lifecycleOwner.lifecycleScope.launch {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            collect(collector)
        }
    }
}
