/*
 * Copyright 2026 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.service.platform.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.Optional

/**
 * Wall-clock ceiling for ONE Kotlin-SDK Platform (L2 / DAPI) round trip before
 * the wrapper gives up and lets its existing legacy-Platform-client fallback
 * answer.
 *
 * ## Why a bound is needed at all
 *
 * Mainnet DAPI nodes serve EXPIRED TLS CERTIFICATES. Against such a node the
 * SDK's query does not fail fast: it spends ~1.8 minutes in handshake failure
 * and retry before returning an error, at which point the wrapper's existing
 * `catch` runs and the legacy Platform client answers the SAME question in
 * ~0.5 s. The fallback already works; the only defect is how long the SDK
 * attempt is allowed to cost first. We have no control over which mainnet
 * nodes are healthy, so this has to be survivable indefinitely.
 *
 * ## Why 6 s
 *
 * - A HEALTHY Platform read (either stack) answers in well under one second —
 *   the measured legacy-client answer for the same query is ~0.5 s. 6 s is
 *   roughly a 12x margin over that, so a slow-but-working node is never
 *   abandoned in favour of the fallback.
 * - It is ~3% of the ~1.8 min a TLS-broken node costs, which is the whole
 *   point: a bad node now costs seconds, not minutes.
 * - Worst case per query becomes ~6 s (abandoned SDK attempt) + ~0.5 s
 *   (legacy answer) ≈ 6.5 s, which is tolerable on the background sync and
 *   identity paths these wrappers serve, and is bounded per ROUND TRIP — the
 *   batch wrappers apply it to each of their calls rather than to the batch,
 *   so a healthy 100-contact batch is never truncated.
 *
 * Deliberately NOT applied to anything on the L1 / send path: L1 is SDK-owned
 * with no second implementation to fall back to, so a timeout there would be a
 * failure rather than a routing choice.
 */
internal const val SDK_PLATFORM_QUERY_TIMEOUT_MS = 6_000L

private val log = LoggerFactory.getLogger("SdkPlatformQueryBudget")

/**
 * Scope for ABANDONED SDK query attempts.
 *
 * This cannot be structured concurrency. The SDK's Platform queries bottom out
 * in a BLOCKING JNI call (`QueriesNative.*`, reached through
 * `TeardownGate.op { }` = `withContext(Dispatchers.IO)`), and a blocking native
 * frame has no suspension point at which cancellation could be observed. A
 * plain `withTimeoutOrNull { source.query() }` would therefore fire its timeout
 * but still not return until the native call finished ~1.8 min later — the
 * cancellation would be delivered to a coroutine that cannot act on it, and the
 * caller would wait exactly as long as before.
 *
 * Running the attempt here instead makes it a NON-child of the caller's job, so
 * abandoning it actually returns control. `SupervisorJob` keeps one abandoned
 * failure from touching any other; `async` stores its exception in the Deferred
 * (never in a handler), so an attempt nobody awaits again cannot crash the app.
 *
 * The abandoned attempt is deliberately NOT cancelled: cancelling could not stop
 * the native call anyway, and leaving it to complete lets [invokeOnCompletion]
 * report what the bad node actually cost — the field measurement this fix exists
 * to bound.
 */
private val abandonedQueryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs [block] — ONE Kotlin-SDK Platform round trip — under a hard wall-clock
 * budget, so a TLS-broken DAPI node costs [timeoutMs] instead of ~1.8 min.
 *
 * @return `Optional` wrapping [block]'s own result (INCLUDING its nulls, which
 *   every caller uses to mean "the SDK definitively reported nothing"), or
 *   **null** when the budget expired.
 *
 * A null return is the caller's existing "fall back to the legacy Platform
 * client" signal, taken by the identical code path as any other SDK failure —
 * no new error path is introduced. Callers therefore stay:
 * `bounded { ... } ?: return null` for the timeout, then their own handling of
 * the boxed value.
 *
 * Exceptions from [block] propagate unchanged to the caller's existing `catch`.
 * A [kotlinx.coroutines.CancellationException] raised because the CALLER's job
 * was cancelled likewise propagates (only this function's OWN timeout is
 * swallowed), so every wrapper's `if (t is CancellationException) throw t`
 * keeps working exactly as before.
 */
internal suspend fun <T : Any> boundedSdkPlatformQuery(
    label: String,
    timeoutMs: Long = SDK_PLATFORM_QUERY_TIMEOUT_MS,
    block: suspend () -> T?
): Optional<T>? {
    val startedAt = System.currentTimeMillis()
    val attempt = abandonedQueryScope.async { Optional.ofNullable(block()) }
    val result = withTimeoutOrNull(timeoutMs) { attempt.await() }
    if (result == null) {
        log.warn("SDK platform query timed out after {}ms; falling back ({})", timeoutMs, label)
        attempt.invokeOnCompletion { cause ->
            log.info(
                "SDK platform query ({}) that timed out finally settled after {}ms (cause={})",
                label, System.currentTimeMillis() - startedAt, cause?.javaClass?.simpleName ?: "none"
            )
        }
    }
    return result
}
