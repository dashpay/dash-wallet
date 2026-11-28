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
import org.dash.wallet.common.services.PaymentSubmissionPendingException
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
 * - A BIP70 POST holds it SHARED together with its last ownership check
 *   ([PendingDirectPaymentVerifier.submitWhileOwned]), so a wipe never clears a payment's recovery
 *   record between that check and the POST.
 * - A wallet wipe raises the WIPE BARRIER ([raiseWipeBarrier]) the moment Reset Wallet starts,
 *   before anything of the wallet or the SDK is torn down: from then on every new hold is refused
 *   at once ([WalletWipedException]) and every hold under way is CANCELLED. The wipe then waits,
 *   with no timeout, until every admitted hold has actually released ([awaitWipeDrained]) before
 *   it detaches the wallet or stops the SDK. A hold inside a call that ignores cancellation (a
 *   native SDK call, a non-cancellable BIP70 POST) is waited for until that call returns. The
 *   barrier stays up through the whole teardown and is lowered ([lowerWipeBarrier]) only once the
 *   old wallet is gone, so a hold asked for afterwards sees the next wallet. [exclusiveForWipe] is
 *   the scoped form, and runs straight inside a barrier that is already up.
 *
 * Shared holds are reentrant: a coroutine already holding the lock (or a child of one, which
 * inherits the hold through its context) passes straight through [withShared] without waiting
 * and without being counted again, so a broadcast inside a send never waits for a wipe that is
 * itself waiting for that send.
 *
 * Deadlock-free by construction: while the barrier is up nothing waits for the lock (a new hold
 * and a reactivation are both refused at once), so nothing the teardown awaits (the SDK stop, the
 * migration and identity jobs it joins, the verifier's watches) can be stuck behind it. The only
 * wait is the drain, at the very start of the wipe, before the wipe has stopped or holds anything
 * an admitted hold could need; a nested shared hold passes through, and everything else a hold
 * may wait for is cancelled out of, or is a call that returns on its own.
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

    /** Up from the start of Reset Wallet until its old wallet is gone. Guarded by [holds]. */
    private var wipeBarrier = false

    /** [exclusiveForWipe] sections run with no barrier up. Guarded by [holds]. */
    private var scopedWipes = 0

    /** Whether a wipe refuses every hold now. Call under [holds]. */
    private fun wipeUnderWayLocked(): Boolean = wipeBarrier || scopedWipes > 0

    /** Marks and cancels every admitted hold. Call under [holds]. */
    private fun cancelHoldsLocked(): Int = holds.onEach { hold ->
        hold.wiped = true
        hold.job?.cancel(WalletWipedCancellationException())
    }.size

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
     * @throws WalletWipedException when a wallet wipe is under way, or ran while this waited for
     *   the hold: the caller decided to fund from a wallet that is going or gone, and nothing of
     *   it is run. Refused at once, never after a wait, so nothing the wipe awaits is stuck here.
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
        if (synchronized(holds) { wipeUnderWayLocked() }) {
            throw WalletWipedException()
        }
        val hold = Hold()
        exclusive.withLock {
            // Checked and registered in one step against [raiseWipeBarrier], which raises the
            // barrier and cancels the registered holds in one step: a hold is either refused here
            // or cancelled there, never admitted unseen.
            synchronized(holds) {
                if (wipes.get() != wipesBefore || wipeUnderWayLocked()) {
                    throw WalletWipedException()
                }
                holds += hold
                sharedHolders.update { it + 1 }
            }
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

    /** [block] under the exclusive hold, or null at once when it cannot be had or a wipe is under way. */
    suspend fun <T> tryExclusive(block: suspend () -> T): T? {
        if (!exclusive.tryLock()) return null
        try {
            // no shared hold can register while this holds [exclusive]
            if (sharedHolders.value > 0 || synchronized(holds) { wipeUnderWayLocked() }) return null
            return block()
        } finally {
            exclusive.unlock()
        }
    }

    /**
     * Raises the wipe barrier: the first step of Reset Wallet, before anything of the wallet or
     * the SDK is torn down. From here on every new hold is refused at once and a reactivation
     * fails fast; every hold under way is cancelled. Does not wait: [awaitWipeDrained] does.
     * Idempotent, any thread (the main thread when the user confirms the reset).
     */
    fun raiseWipeBarrier() {
        synchronized(holds) {
            if (!wipeBarrier) {
                wipeBarrier = true
                wipes.incrementAndGet()
            }
            val cancelled = cancelHoldsLocked()
            if (cancelled > 0) {
                log.info("wallet wipe: cancelled {} funding hold(s)", cancelled)
            }
        }
    }

    /** Whether the wipe barrier is up ([raiseWipeBarrier] .. [lowerWipeBarrier]). */
    val isWipeBarrierRaised: Boolean get() = synchronized(holds) { wipeBarrier }

    /**
     * Waits, with no timeout, until every hold admitted before the barrier went up has released
     * (one in a call that ignores cancellation is waited for until that call returns), and until
     * a reactivation in its short exclusive section has left it. Only then may the wipe detach
     * the wallet or stop the SDK.
     *
     * Must not be called from inside a hold of this lock, and the caller must hold nothing an
     * admitted hold could wait for: call it first, before any teardown step.
     */
    suspend fun awaitWipeDrained() {
        check(currentCoroutineContext()[Held]?.covers(this) != true) {
            "a wipe cannot wait for a hold its own caller has"
        }
        check(isWipeBarrierRaised) { "no wipe barrier to drain" }
        exclusive.withLock {
            if (sharedHolders.value > 0) {
                log.info("wallet wipe: waiting for {} funding hold(s) to release", sharedHolders.value)
            }
            sharedHolders.first { it == 0 }
        }
    }

    /**
     * Lowers the wipe barrier once the old wallet is gone: holds asked for from here on are for
     * the next wallet. Idempotent.
     */
    fun lowerWipeBarrier() {
        synchronized(holds) {
            if (wipeBarrier) {
                wipeBarrier = false
                wipes.incrementAndGet()
            }
        }
    }

    /**
     * [block] under the wipe barrier: refuses new holds, cancels every shared hold under way,
     * waits until each has released (no timeout), runs [block], and refuses every shared hold
     * asked for while it ran ([withShared]).
     *
     * Inside a barrier already raised by Reset Wallet ([raiseWipeBarrier]) it raises nothing
     * again: it drains (by then a no-op) and runs [block] within that barrier, which the reset
     * lowers when it is done.
     *
     * [block] must not wait for anything that needs a hold of this lock. Not callable from inside
     * a shared hold.
     */
    suspend fun <T> exclusiveForWipe(block: suspend () -> T): T {
        check(currentCoroutineContext()[Held]?.covers(this) != true) {
            "a wipe cannot wait for a hold its own caller has"
        }
        val scoped = synchronized(holds) {
            if (wipeBarrier) {
                false
            } else {
                // Counted at the start and again at the end, so a hold asked for at any point
                // while the wipe runs finds the count changed once it gets in.
                scopedWipes++
                wipes.incrementAndGet()
                val cancelled = cancelHoldsLocked()
                if (cancelled > 0) {
                    log.info("wallet wipe: cancelled {} funding hold(s); waiting for them to release", cancelled)
                }
                true
            }
        }
        try {
            return exclusive.withLock {
                sharedHolders.first { it == 0 }
                block()
            }
        } finally {
            if (scoped) {
                synchronized(holds) {
                    scopedWipes--
                    wipes.incrementAndGet()
                }
            }
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

    /**
     * [block], a submission (an SDK broadcast, and whatever must follow it on the same wallet),
     * under a shared hold, with what a wipe did to it reported by kind instead of thrown:
     * - [refused]: a wipe was under way before the hold was admitted; nothing of [block] ran, so
     *   nothing was sent.
     * - [cancelled]: a wipe cancelled [block] after admission. `submitted` says whether [block]
     *   had reached [SubmissionMark.submitting] (the call that may put something on the network)
     *   by then: false proves nothing was sent; true means it may have been, and the outcome is
     *   unknown, never "not sent".
     *
     * The [SubmissionMark] travels in the coroutine context: a nested call (an SDK send made under
     * the caller's own hold) marks the outermost one, and passes its cancellation up to it rather
     * than reporting it, because only the outermost hold's caller is still running afterwards.
     */
    suspend fun <T> withSharedSubmission(
        refused: (WalletWipedException) -> T,
        cancelled: (cause: WalletWipedCancellationException, submitted: Boolean) -> T,
        block: suspend (SubmissionMark) -> T
    ): T {
        val outer = currentCoroutineContext()[SubmissionMark]
        val mark = outer ?: SubmissionMark()
        return try {
            if (outer != null) {
                withShared { block(mark) }
            } else {
                withContext(mark) { withShared { block(mark) } }
            }
        } catch (e: WalletWipedException) {
            refused(e)
        } catch (e: WalletWipedCancellationException) {
            // Inside a hold the wipe cancelled (nested, or the caller itself is cancelled): the
            // outermost hold reports it.
            if (!currentCoroutineContext().isActive) throw e
            cancelled(e, mark.reached)
        }
    }
}

/**
 * How far a [FundingSelectionLock.withSharedSubmission] block got: [submitting] is called right
 * before the call that may put something on the network, or as soon as a result shows that it
 * did. Shared by every nested submission of one outermost hold.
 */
class SubmissionMark : AbstractCoroutineContextElement(SubmissionMark) {
    companion object Key : CoroutineContext.Key<SubmissionMark>

    @Volatile
    var reached: Boolean = false
        private set

    fun submitting() {
        reached = true
    }
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

/**
 * A submission a wallet wipe cancelled after it may have reached the network: an SDK broadcast
 * under way, or one that went out before the wipe stopped what was to follow it. Its outcome is
 * unknown, so it is a [PaymentSubmissionPendingException]: every caller reports it as "may have
 * been sent" and never offers a retry. [txId] is empty when the transaction id is not known.
 */
class WalletWipedDuringSubmissionException(txId: String?, cause: WalletWipedCancellationException) :
    PaymentSubmissionPendingException(
        txId.orEmpty(),
        cause,
        "The wallet was reset while this payment was being sent, so it may have been sent. " +
            "Check the transaction history before sending it again."
    )
