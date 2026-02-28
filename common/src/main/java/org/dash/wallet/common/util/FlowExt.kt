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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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

/** from ChatGPT */
fun <T> Flow<T>.window(intervalMillis: Long): Flow<List<T>> = flow {
    val buffer = mutableListOf<T>()
    val lock = Any()
    var lastEmitTime = System.currentTimeMillis()

    this@window.collect { value ->
        var batchToEmit: List<T>? = null

        synchronized(lock) {
            buffer.add(value)
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastEmitTime >= intervalMillis) {
                batchToEmit = buffer.toList()
                buffer.clear()
                lastEmitTime = currentTime
            }
        }

        batchToEmit?.let { emit(it) }
    }

    val finalBatch = synchronized(lock) {
        if (buffer.isNotEmpty()) buffer.toList() else null
    }
    finalBatch?.let { emit(it) }
}
