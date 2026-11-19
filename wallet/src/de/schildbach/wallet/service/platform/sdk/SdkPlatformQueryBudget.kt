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
 * On 2026-08-13, against SDK `0.1.0-v42int4` (commit `aec189faa`), a mainnet
 * SDK query was measured spending ~1.8 minutes before returning an error; the
 * cause was then attributed to mainnet DAPI nodes serving expired TLS
 * certificates, but what produced the delay was never identified. The
 * wrapper's existing `catch` then ran and the legacy Platform client answered
 * the same question in ~0.5 s. The fallback worked; the defect was how long
 * the SDK attempt was allowed to cost first. We have no control over which
 * mainnet nodes are healthy, so this has to be survivable indefinitely.
 *
 * At 5.1.0-int36 (dashpay/platform `0db63f1e21`; read from source and checked
 * by probing mainnet TLS handshakes on 2026-11-18, not measured through the
 * SDK):
 * - The SDK's mainnet address list is baked in at build time and omits seeds
 *   whose TLS probe failed (dashpay/platform#4418): 282 addresses, none of
 *   which served an expired certificate on 2026-11-18.
 * - Each query makes at most 4 attempts (3 retries), each bounded at 10 s for
 *   the whole attempt including the response body (#4973). A failed node is
 *   banned and the next attempt goes to another address (sticky 5-node
 *   rotation, #4545). A failed TLS handshake costs roughly 0.3–0.5 s, so a
 *   query that first hits a bad-certificate node is expected to finish in
 *   about a second.
 * - What can still exceed this bound is a node that silently drops packets:
 *   it costs the full 10 s attempt (3 of 282 on 2026-11-18, about 1% of first
 *   picks). The ceiling for one query is about 40 s.
 * - `SdkConfig.requestTimeoutMs` and `requestRetryCount` exist in the Kotlin
 *   API, but the native `dash_sdk_create_trusted` does not read them, and
 *   there is no connect-timeout or ban setting, so the wallet cannot tune
 *   this at int36.
 *
 * The bound therefore stays: as a routing choice for the adapters that still
 * have a legacy fallback, and as a hang bound for [SdkIdentityQueries].
 *
 * ## Why 6 s
 *
 * - A HEALTHY Platform read (either stack) answers in well under one second —
 *   the measured legacy-client answer for the same query is ~0.5 s. 6 s is
 *   roughly a 12x margin over that, so a slow-but-working node is never
 *   abandoned in favour of the fallback.
 * - It was ~3% of the ~1.8 min measured in August 2026, so a bad node cost
 *   seconds, not minutes; at int36 it also cuts short the ~10 s a silently
 *   dropping node costs per attempt.
 * - Worst case per query becomes ~6 s (abandoned SDK attempt) + ~0.5 s
 *   (legacy answer) ≈ 6.5 s, which is tolerable on the background sync and
 *   identity paths these wrappers serve, and is bounded per ROUND TRIP — the
 *   batch wrappers apply it to each of their calls rather than to the batch,
 *   so a healthy 100-contact batch is never truncated.
 *
 * Exception: [SdkIdentityQueries] (identity discovery by first key hash, identity
 * credit balance) has NO legacy fallback. There the bound is a hang bound, not a
 * routing choice: a timeout throws [SdkIdentityQueryTimeoutException] and the
 * caller treats the answer as unknown (discovery fails, the repository balance is
 * null, the `PlatformRepo` balance throws), never as "no identity". At int36
 * the case where this bound fires is a silently dropping node, and the read then
 * throws [SdkIdentityQueryTimeoutException] (unknown, never absence).
 *
 * Deliberately NOT applied to anything on the L1 / send path: L1 is SDK-owned
 * with no second implementation to fall back to, so a timeout there would be a
 * failure rather than a routing choice.
 */
internal const val SDK_PLATFORM_QUERY_TIMEOUT_MS = 6_000L

/**
 * Wall-clock ceiling for ONE LEGACY (`org.dashj.platform` / `DapiClient`)
 * contact-path identity fetch — [boundedLegacyPlatformQuery]'s default.
 *
 * ## Why this is not [SDK_PLATFORM_QUERY_TIMEOUT_MS]
 *
 * The 6 s budget above is a ROUTING choice: abandoning the SDK attempt costs
 * nothing because the legacy client answers the same question in ~0.5 s. The
 * legacy client has no such second implementation behind it — it IS the
 * fallback — so a budget here does not re-route, it DROPS the contact for
 * this pass, and with it the DIP-15 friendship keychain that makes that
 * contact's payments visible. Cutting a working fetch would recreate exactly
 * the class of bug the rest of this batch fixes.
 *
 * And the slow ones are working. On the field device (11.10.86, contact sync
 * 17:13:57–17:20:09) every stalled `getIdentity` returned an identity and the
 * contact was added: 17:14:31 → "added sent request from 3h6GqHt…" at
 * 17:15:03. The stalls cluster tightly at ~32–33 s each — a fixed internal
 * retry cycle, not an unbounded hang.
 *
 * 60 s therefore sits ~1.8x above the worst observed SUCCESSFUL call while
 * still converting the failure mode that has no bound at all — a call that
 * never returns, the shape that cost ten hours on the identity-discovery
 * latch — into a skip-and-retry-next-pass. It is a hang bound, not a
 * latency optimisation, and it is deliberately not applied to any L1/send
 * path.
 */
internal const val LEGACY_CONTACT_QUERY_TIMEOUT_MS = 60_000L

private val log = LoggerFactory.getLogger("SdkPlatformQueryBudget")

/**
 * Scope for ABANDONED SDK query attempts.
 *
 * This cannot be structured concurrency. The SDK's Platform queries bottom out
 * in a BLOCKING JNI call (`QueriesNative.*`, reached through
 * `TeardownGate.op { }` = `withContext(Dispatchers.IO)`), and a blocking native
 * frame has no suspension point at which cancellation could be observed. A
 * plain `withTimeoutOrNull { source.query() }` would therefore fire its timeout
 * but still not return until the native call finished (~1.8 min later in the
 * August 2026 measurement, up to about 40 s at int36) — the
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
 * budget, so a bad DAPI node costs [timeoutMs] instead of the whole SDK retry
 * cycle (~1.8 min in the August 2026 measurement, up to about 40 s at int36).
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

/**
 * Runs [block] — ONE BLOCKING legacy-Platform (`DapiClient`) call — under a
 * hard wall-clock budget, so a call that never returns cannot wedge the pass
 * that made it.
 *
 * Same containment as [boundedSdkPlatformQuery] and for the same reason: the
 * legacy client bottoms out in blocking I/O with no suspension point, so the
 * attempt runs OUTSIDE the caller's job ([abandonedQueryScope]) — otherwise
 * the timeout would fire while the caller still waited for the native frame.
 * The abandoned attempt is left to finish and reports what it cost.
 *
 * @return `Optional` wrapping [block]'s own result (including its nulls), or
 *   **null** when the budget expired — which callers must treat exactly like
 *   their existing "could not fetch" path, i.e. retry on the next pass.
 *
 * Exceptions from [block] propagate unchanged. See
 * [LEGACY_CONTACT_QUERY_TIMEOUT_MS] for why the bound is generous: a slow
 * legacy fetch is usually a SUCCESSFUL one, and there is no second
 * implementation to fall through to.
 */
internal suspend fun <T : Any> boundedLegacyPlatformQuery(
    label: String,
    timeoutMs: Long = LEGACY_CONTACT_QUERY_TIMEOUT_MS,
    block: () -> T?
): Optional<T>? {
    val startedAt = System.currentTimeMillis()
    val attempt = abandonedQueryScope.async { Optional.ofNullable(block()) }
    val result = withTimeoutOrNull(timeoutMs) { attempt.await() }
    if (result == null) {
        log.warn(
            "legacy platform query timed out after {}ms ({}); treating it as unavailable for " +
                "this pass — the next pass retries",
            timeoutMs, label
        )
        attempt.invokeOnCompletion { cause ->
            log.info(
                "legacy platform query ({}) that timed out finally settled after {}ms (cause={})",
                label, System.currentTimeMillis() - startedAt, cause?.javaClass?.simpleName ?: "none"
            )
        }
    }
    return result
}
