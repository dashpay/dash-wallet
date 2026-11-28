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

package de.schildbach.wallet.service.platform

import de.schildbach.wallet.database.entity.DashPayContactRequest
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * The repair half of `PlatformSynchronizationService.checkDatabaseIntegrity`:
 * re-adds DIP-15 friend key chains for contact-request rows that are in Room
 * but missing from the wallet, and re-fetches contacts that have no profile.
 *
 * ## Why this is the only retry
 *
 * A contact sync inserts the request row FIRST and then adds the key chain,
 * which needs a (60 s-bounded) contact identity lookup. When that lookup
 * times out the chain is skipped, but the row stays — and the next contact
 * sync is incremental (it only asks for requests newer than the newest row),
 * so it never sees that request again. This pass, which walks every stored
 * row, is the only thing that ever retries it. Field evidence (12.0.0-qa28 /
 * qa30, mainnet): 7 contacts timed out on a restore, their chains were never
 * added (223 of 230), and 5 incoming DashPay payments were never seen.
 *
 * ## Bounded
 *
 *  - only rows whose key chain is ACTUALLY missing are looked up; a pass over
 *    a healthy wallet does no network work for key chains at all;
 *  - the dashpay data contract (needed to rebuild a request document) is only
 *    loaded when there is such a row to repair;
 *  - a contact whose repair fails (lookup timed out, identity missing, ...) is
 *    backed off exponentially, [initialBackoffMs] doubling up to
 *    [maxBackoffMs], so a contact that keeps timing out costs one 60 s lookup
 *    per backoff window instead of one per pass. The contract load is backed
 *    off the same way. The state is in memory only: a new process (the
 *    natural "try again" point) starts fresh.
 *
 * Profile repair does NOT need the contract, so it runs on every pass even
 * when the contract cannot be loaded — one batched query, the same cost class
 * as the pass's regular profile refresh.
 *
 * Pure decision logic with every side effect behind a lambda, so it is unit
 * testable without a wallet or a network.
 */
internal class ContactIntegrityRepair(
    private val clock: () -> Long = System::currentTimeMillis,
    private val initialBackoffMs: Long = DEFAULT_INITIAL_BACKOFF_MS,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS
) {
    companion object {
        private val log = LoggerFactory.getLogger(ContactIntegrityRepair::class.java)

        /** First wait after a failed repair; a couple of contact passes. */
        const val DEFAULT_INITIAL_BACKOFF_MS = 2 * 60 * 1000L

        /** Ceiling for the doubling wait. */
        const val DEFAULT_MAX_BACKOFF_MS = 60 * 60 * 1000L

        internal const val CONTRACT_KEY = "contract:dashpay"

        /** Backoff key of a request WE sent (our receiving chain from the contact). */
        internal fun sentKey(row: DashPayContactRequest) = "sent:${row.toUserId}"

        /** Backoff key of a request we RECEIVED (our sending chain to the contact). */
        internal fun receivedKey(row: DashPayContactRequest) = "received:${row.userId}:${row.accountReference}"
    }

    /** Which side of the friendship a stored row describes. */
    enum class Direction { SENT, RECEIVED }

    data class Result(
        /** Rows whose key chain was missing at the start of the pass. */
        val keyChainsMissing: Int,
        /** Key chains this pass added to the wallet. */
        val keyChainsAdded: Int,
        /** Missing key chains not attempted because their backoff window is still open. */
        val keyChainsDeferred: Int,
        /** Missing key chains not attempted because the dashpay contract could not be loaded. */
        val contractUnavailable: Boolean,
        /** Contacts that had no profile row and were re-fetched. */
        val profilesRefetched: List<String>
    )

    private data class Backoff(val failures: Int, val nextAttemptAt: Long)

    private val backoff = ConcurrentHashMap<String, Backoff>()

    /** True when [key] has no open backoff window. */
    internal fun isDue(key: String): Boolean {
        val entry = backoff[key] ?: return true
        return clock() >= entry.nextAttemptAt
    }

    internal fun failureCount(key: String): Int = backoff[key]?.failures ?: 0

    private fun recordFailure(key: String) {
        backoff.compute(key) { _, old ->
            val failures = (old?.failures ?: 0) + 1
            // initial * 2^(failures-1), capped; the shift is clamped so it cannot overflow
            val wait = (initialBackoffMs shl (failures - 1).coerceAtMost(20)).coerceAtMost(maxBackoffMs)
            Backoff(failures, clock() + wait)
        }
    }

    private fun recordSuccess(key: String) {
        backoff.remove(key)
    }

    /**
     * Runs one repair pass.
     *
     * @param hasKeyChain whether the wallet already holds the chain for a row (local, cheap)
     * @param ensureContractLoaded loads the dashpay data contract if needed; false = unavailable
     * @param addKeyChain rebuilds the request and adds its chain; true = added
     * @param hasProfile whether a contact already has a profile row
     * @param fetchProfiles fetches and stores profiles for the given contact ids
     */
    suspend fun run(
        sentRequests: List<DashPayContactRequest>,
        receivedRequests: List<DashPayContactRequest>,
        hasKeyChain: (DashPayContactRequest, Direction) -> Boolean,
        ensureContractLoaded: suspend () -> Boolean,
        addKeyChain: suspend (DashPayContactRequest, Direction) -> Boolean,
        hasProfile: suspend (String) -> Boolean,
        fetchProfiles: suspend (List<String>) -> Unit
    ): Result {
        val rows = sentRequests.map { Triple(it, Direction.SENT, sentKey(it)) } +
            receivedRequests.map { Triple(it, Direction.RECEIVED, receivedKey(it)) }

        val missing = rows.filter { (row, direction, _) -> !hasKeyChain(row, direction) }
        val (due, deferred) = missing.partition { (_, _, key) -> isDue(key) }
        // A chain that showed up some other way (e.g. a later incremental sync) needs no backoff.
        val missingKeys = missing.mapTo(HashSet()) { it.third }
        rows.forEach { (_, _, key) -> if (key !in missingKeys) recordSuccess(key) }

        var added = 0
        var contractUnavailable = false
        if (due.isNotEmpty()) {
            val contractLoaded = if (isDue(CONTRACT_KEY)) {
                val loaded = try {
                    ensureContractLoaded()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.warn("check database integrity: loading the dashpay contract failed", e)
                    false
                }
                if (loaded) recordSuccess(CONTRACT_KEY) else recordFailure(CONTRACT_KEY)
                loaded
            } else {
                false
            }

            if (!contractLoaded) {
                contractUnavailable = true
                log.warn(
                    "check database integrity: {} contact key chain(s) missing but the dashpay data " +
                        "contract is unavailable (attempt {}); key chains retry on a later pass",
                    due.size,
                    failureCount(CONTRACT_KEY)
                )
            } else {
                for ((row, direction, key) in due) {
                    val ok = try {
                        addKeyChain(row, direction)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("check database integrity: adding the key chain for {} failed", key, e)
                        false
                    }
                    when {
                        ok -> {
                            added++
                            recordSuccess(key)
                            log.warn(
                                "check database integrity: added missing {} key chain for {}; " +
                                    "transactions may also be missing",
                                direction, key
                            )
                        }
                        // Lost a race with another path that added it — not a failure.
                        hasKeyChain(row, direction) -> recordSuccess(key)
                        else -> {
                            recordFailure(key)
                            log.info(
                                "check database integrity: key chain for {} still missing (failure {}); " +
                                    "backing off",
                                key, failureCount(key)
                            )
                        }
                    }
                }
            }
        }
        if (deferred.isNotEmpty()) {
            log.info(
                "check database integrity: {} missing key chain(s) deferred by backoff",
                deferred.size
            )
        }

        val contactIds = LinkedHashSet<String>()
        sentRequests.forEach { contactIds.add(it.toUserId) }
        receivedRequests.forEach { contactIds.add(it.userId) }
        val missingProfiles = contactIds.filter { !hasProfile(it) }
        if (missingProfiles.isNotEmpty()) {
            log.info("check database integrity: re-fetching {} missing profile(s)", missingProfiles.size)
            fetchProfiles(missingProfiles)
        }

        return Result(
            keyChainsMissing = missing.size,
            keyChainsAdded = added,
            keyChainsDeferred = deferred.size,
            contractUnavailable = contractUnavailable,
            profilesRefetched = missingProfiles
        )
    }
}
