package org.dash.wallet.common.transactions

import kotlinx.coroutines.suspendCancellableCoroutine
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionConfidence
import org.bitcoinj.utils.Threading
import org.dash.wallet.common.transactions.filters.TransactionFilter
import kotlin.coroutines.resume

suspend fun Transaction.waitToMatchFilters(vararg filters: TransactionFilter) {
    return suspendCancellableCoroutine { continuation ->
        var transactionConfidenceListener: TransactionConfidence.Listener? = null
        transactionConfidenceListener = TransactionConfidence.Listener { _, _ ->
            if (filters.isEmpty() || filters.any { it.matches(this) }) {
                confidence.removeEventListener(transactionConfidenceListener)

                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }

        // Check if already matches
        if (filters.isEmpty() || filters.any { it.matches(this) }) {
            if (continuation.isActive) {
                continuation.resume(Unit)
            }
            return@suspendCancellableCoroutine
        }

        this.confidence.addEventListener(Threading.USER_THREAD, transactionConfidenceListener)

        continuation.invokeOnCancellation {
            confidence.removeEventListener(transactionConfidenceListener)
        }
    }
}