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

package de.schildbach.wallet.payments

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * A shared/exclusive lock over fresh SDK coin selection, every SDK broadcast or consumption of a
 * reservation on the payment paths, and a resubmission's reactivation (reviews 4309551218, and the
 * follow-up on its length).
 *
 * The SDK engine cannot see the dashj locks that protect an uncertain payment, so fresh SDK
 * funding is authorized by the funding gate ([PendingPaymentFundingGate]) instead. A resubmission
 * makes a released payment active again ([PendingDirectPaymentVerifier.reactivateForResubmission]),
 * which flips that gate; without coordination a funding call authorized just before could still
 * select the payment's inputs just after.
 *
 * - Fresh funding holds it SHARED ([withShared], [selectIfAllowed]): it asks the gate again under
 *   the hold and keeps it through its SDK call (selection and reservation). Fundings never wait
 *   for one another, so a send is not held up by an identity registration that waits minutes for
 *   its asset lock. A broadcast of a reservation holds it shared as well, so a wipe is serialized
 *   with it.
 * - The reactivation takes it EXCLUSIVE and fail-fast ([tryExclusive]): refused at once while any
 *   funding holds it, and otherwise short, so a funding only ever waits for that short section.
 * - Settling an invalidated payment holds it SHARED, so it never interleaves with a reactivation.
 * - A wallet wipe takes it EXCLUSIVE ([exclusiveForWipe]): it blocks new holds, CANCELS every hold
 *   under way, and waits, with no timeout, until every one has actually released before it runs.
 *   A hold inside a call that ignores cancellation (a native SDK call) is waited for until that
 *   call returns. Every hold that was waiting when the wipe began is then refused
 *   ([WalletWipedException]) rather than let through onto whatever wallet is installed next.
 *
 * Shared holds are reentrant: a coroutine already holding the lock (or a child of one, which
 * inherits the hold through its context) passes straight through [withShared] without waiting
 * and without being counted again, so a broadcast inside a send never waits for a wipe that is
 * itself waiting for that send.
 *
 * Deadlock-free by construction: while it waits for the holds to release, the wipe holds only
 * [exclusive], which a hold never needs (a nested shared hold passes through, and [tryExclusive]
 * fails fast). Everything else a hold may wait for is cancelled out of, or is a native call that
 * returns on its own.
 *
 * Outermost: taken before the verifier's scanMutex, recordMutex, settleMutex and claimMutex,
 * never while holding any of them. Not reentrant for the exclusive side.
 */
@Singleton
class FundingSelectionLock @Inject constructor() {
    companion object {
        private val log = LoggerFactory.getLogger(FundingSelectionLock::class.java)
    }

    /** Held through an exclusive section; a shared hold passes through it to register itself. */
    private val exclusive = Mutex()
    private val sharedHolders = MutableStateFlow(0)

    /** Bumped by every wipe; a shared hold that began waiting before one is refused. */
    private val wipes = AtomicLong(0)

    /** The holds admitted and not yet released, for a wipe to cancel. Guarded by itself. */
    private val holds = mutableSetOf<Hold>()

    /** One admitted shared hold: the job running its block, once it has one. */
    private class Hold {
        var job: Job? = null

        /** Set by a wipe that cancelled this hold. */
        var wiped = false
    }

    /** Marks a coroutine (and its children) as holding [lock] shared; see [withShared]. */
    private class Held(val lock: FundingSelectionLock, val outer: Held?) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>

        fun covers(lock: FundingSelectionLock): Boolean = this.lock === lock || outer?.covers(lock) == true
    }

    /**
     * [block] under a shared hold; waits only for an exclusive section that is under way. Passes
     * straight through, counted once, when the calling coroutine already holds it.
     *
     * @throws WalletWipedException when a wallet wipe ran while this waited for the hold: the
     *   caller decided to fund from a wallet that is gone, and nothing of it is run
     * @throws WalletWipedCancellationException when a wallet wipe cancelled [block] while it ran:
     *   whatever it had done by then stands, the rest was cancelled. A [block] that completed
     *   despite the cancellation returns its result.
     */
    suspend fun <T> withShared(block: suspend () -> T): T {
        val context = currentCoroutineContext()
        val held = context[Held]
        if (held?.covers(this) == true) {
            return block()
        }
        val wipesBefore = wipes.get()
        val hold = Hold()
        exclusive.withLock {
            if (wipes.get() != wipesBefore) {
                throw WalletWipedException()
            }
            synchronized(holds) { holds += hold }
            sharedHolders.update { it + 1 }
        }
        try {
            var completed = false
            var result: T? = null
            try {
                withContext(Held(this, held)) {
                    val job = coroutineContext.job
                    val wipedAlready = synchronized(holds) {
                        hold.job = job
                        hold.wiped
                    }
                    if (wipedAlready) {
                        job.cancel(WalletWipedCancellationException())
                    }
                    result = block()
                    completed = true
                }
            } catch (e: CancellationException) {
                val wiped = synchronized(holds) { hold.wiped }
                if (!wiped || !currentCoroutineContext().isActive) {
                    throw e
                }
                // A block that finished before it noticed the cancellation keeps its result: a
                // broadcast that went out is reported as such, never as cancelled.
                if (completed) {
                    @Suppress("UNCHECKED_CAST")
                    return result as T
                }
                throw e as? WalletWipedCancellationException ?: WalletWipedCancellationException(e)
            }
            @Suppress("UNCHECKED_CAST")
            return result as T
        } finally {
            synchronized(holds) { holds -= hold }
            sharedHolders.update { it - 1 }
        }
    }

    /** [block] under the exclusive hold, or null at once when it cannot be had. */
    suspend fun <T> tryExclusive(block: suspend () -> T): T? {
        if (!exclusive.tryLock()) return null
        try {
            // no shared hold can register while this holds [exclusive]
            if (sharedHolders.value > 0) return null
            return block()
        } finally {
            exclusive.unlock()
        }
    }

    /**
     * [block] under the exclusive hold, for a wallet wipe: waits for the exclusive side, cancels
     * every shared hold under way, waits until each has released (no timeout: a hold in a native
     * call that ignores cancellation is waited for until that call returns), and refuses every
     * shared hold asked for while the wipe ran ([withShared]).
     *
     * [block] must not wait for anything that needs a hold of this lock. Not callable from inside
     * a shared hold.
     */
    suspend fun <T> exclusiveForWipe(block: suspend () -> T): T {
        check(currentCoroutineContext()[Held]?.covers(this) != true) {
            "a wipe cannot wait for a hold its own caller has"
        }
        // Counted at the start and again at the end, so a hold asked for at any point while the
        // wipe runs finds the count changed once it gets in.
        wipes.incrementAndGet()
        try {
            return exclusive.withLock {
                // No hold can be admitted while this holds [exclusive]: the set is complete.
                val cancelled = synchronized(holds) {
                    holds.onEach { hold ->
                        hold.wiped = true
                        hold.job?.cancel(WalletWipedCancellationException())
                    }.size
                }
                if (cancelled > 0) {
                    log.info("wallet wipe: cancelled {} funding hold(s); waiting for them to release", cancelled)
                }
                sharedHolders.first { it == 0 }
                block()
            }
        } finally {
            wipes.incrementAndGet()
        }
    }

    /**
     * [select] under a shared hold once [blocked] (the funding gate, asked again here) says it may
     * select fresh inputs; null, selecting nothing, when it may not, or when a wallet wipe ran
     * while it waited for the hold. A wipe that cancels [select] while it runs propagates as a
     * cancellation ([WalletWipedCancellationException]): it may have selected something already.
     */
    suspend fun <T> selectIfAllowed(blocked: suspend () -> Boolean, select: suspend () -> T): Selected<T>? =
        try {
            withShared { if (blocked()) null else Selected(select()) }
        } catch (e: WalletWipedException) {
            null
        }

    /** What [selectIfAllowed] selected. */
    class Selected<T>(val value: T)
}

/** A funding selection refused because the wallet it was for was wiped while it waited. */
class WalletWipedException :
    IllegalStateException("the wallet was wiped while this payment waited to select its inputs; nothing was selected")

/**
 * A shared hold cancelled by a wallet wipe while it ran: the wallet it was working for is being
 * wiped. Unlike [WalletWipedException] it does not prove nothing was done.
 */
class WalletWipedCancellationException(cause: Throwable? = null) :
    CancellationException("the wallet was wiped while this payment was under way; it was cancelled") {
    init {
        cause?.let { initCause(it) }
    }
}
