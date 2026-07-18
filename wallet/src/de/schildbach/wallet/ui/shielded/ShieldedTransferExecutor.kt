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

package de.schildbach.wallet.ui.shielded

import android.content.Context
import android.content.Intent
import androidx.annotation.StringRes
import androidx.core.os.bundleOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import de.schildbach.wallet.service.platform.sdk.SdkWriteResult
import de.schildbach.wallet.service.platform.sdk.ShieldFromWalletOutcome
import de.schildbach.wallet.service.platform.sdk.ShieldedBalanceService
import de.schildbach.wallet.service.platform.sdk.creditsToDash
import de.schildbach.wallet.service.platform.sdk.dashToCredits
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet.ui.more.MoreFragment
import de.schildbach.wallet_test.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.NotificationService
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a terminal [ShieldedSubmitState] surfaces to a user who is not
 * looking at the transfer screen: the system-notification (or in-app
 * toast) title/message pair, plus whether tapping the notification lands
 * on the More screen with the "Transfer completed" toast (the existing
 * post-success route). Pure data — host-JVM-testable via
 * [shieldedOutcomeNotification].
 */
internal data class ShieldedOutcomeNotification(
    @StringRes val titleRes: Int,
    @StringRes val messageRes: Int,
    /** Tapping routes to the More screen; true also shows its "Transfer completed" toast. */
    val showsTransferCompletedToast: Boolean
)

/**
 * Map a submit state to its outcome surfacing, or null for the states
 * with nothing to announce (Idle, Proving). Copy reuses the in-screen
 * strings so the story is identical wherever the user learns the result:
 *
 * - Success → "Transfer completed" (+ balance-updated line);
 * - LockedPendingShield → "Transfer in progress" (completes automatically);
 * - NotSent → "This transfer was not sent" (funds untouched, retry safe);
 * - MayHaveGoneThrough → the existing terminal ambiguous copy (do NOT
 *   send again);
 * - Stalled (not terminal, but announced once when the watchdog fires) →
 *   "taking longer than expected" — may still complete in the
 *   background; no failure wording, no retry.
 */
internal fun shieldedOutcomeNotification(state: ShieldedSubmitState): ShieldedOutcomeNotification? =
    when (state) {
        ShieldedSubmitState.Success -> ShieldedOutcomeNotification(
            R.string.shielded_transfer_completed,
            R.string.shielded_notification_success_message,
            showsTransferCompletedToast = true
        )
        ShieldedSubmitState.LockedPendingShield -> ShieldedOutcomeNotification(
            R.string.shielded_locked_pending_title,
            R.string.shielded_notification_pending_message,
            showsTransferCompletedToast = false
        )
        is ShieldedSubmitState.NotSent -> ShieldedOutcomeNotification(
            R.string.shielded_transfer_failed,
            R.string.shielded_transfer_failed_message,
            showsTransferCompletedToast = false
        )
        ShieldedSubmitState.MayHaveGoneThrough -> ShieldedOutcomeNotification(
            R.string.shielded_transfer_ambiguous_title,
            R.string.shielded_transfer_ambiguous_message,
            showsTransferCompletedToast = false
        )
        is ShieldedSubmitState.Stalled -> ShieldedOutcomeNotification(
            R.string.shielded_transfer_stalled_title,
            R.string.shielded_transfer_stalled_message,
            showsTransferCompletedToast = false
        )
        ShieldedSubmitState.Idle, ShieldedSubmitState.Proving -> null
    }

/**
 * The exact deficit rs-platform-wallet's shielded note selection reports
 * when the requested amount + its Rust-computed fee exceeds the available
 * shielded balance — amounts in credits (1 duff = 1000 credits). Raised
 * strictly pre-broadcast (see the note-selection rule in
 * `classifyBroadcastFailure`); message-matched until the SDK exposes the
 * amounts as typed fields.
 */
private val INSUFFICIENT_SHIELDED_BALANCE =
    Regex("""Insufficient shielded balance: available (\d+), required (\d+)""")

/**
 * The asset-lock coin-selection shortfall rs-platform-wallet reports when
 * the requested amount + the L1 fee exceeds the SDK wallet's spendable
 * funds (asset_lock/build.rs `map_builder_error` promotes the builder's
 * InsufficientFunds shapes to `AssetLockInsufficientFunds`). Raised while
 * BUILDING the asset-lock transaction — strictly pre-broadcast (see the
 * asset-lock rule in `classifyBroadcastFailure`). Unlike the shielded
 * note-selection message, its `required` figure does NOT include the fee
 * (available == required in the live incident), so no exact deficit can
 * be parsed — the retry must ESTIMATE a reserve instead
 * ([assetLockMaxFeeReserve]).
 */
private const val ASSET_LOCK_SELECTION_SHORT = "asset lock coin selection is short"

/**
 * [failure]'s reason plus its cause chain's messages (bounded), the shared
 * traversal behind the message-matched max-spend retry rules.
 */
private fun notBroadcastMessages(failure: SdkWriteResult.NotBroadcast): List<String> = buildList {
    add(failure.reason)
    var t: Throwable? = failure.cause
    var depth = 0
    while (t != null && depth < 10) {
        t.message?.let(::add)
        t = t.cause
        depth++
    }
}

/** Whether [failure] is the pre-broadcast asset-lock coin-selection shortfall. */
internal fun isAssetLockSelectionShort(failure: SdkWriteResult.NotBroadcast): Boolean =
    notBroadcastMessages(failure).any { it.contains(ASSET_LOCK_SELECTION_SHORT) }

/**
 * The L1 fee reserve to withhold from a max ("spend everything") shield
 * whose asset-lock coin selection came up short.
 *
 * Rust builds the asset lock at `DEFAULT_FEE_PER_KB = 1000` duffs/kB
 * (rs-platform-wallet asset_lock/manager.rs) — 1 duff per byte — and a max
 * spend selects (essentially) every spendable UTXO as an input. So the fee
 * is bounded by the transaction size: ~148 vbytes per input plus ~300
 * bytes for the outputs, the asset-lock payload and overhead, doubled as a
 * safety margin. Over-reserving is LOSSLESS: the builder returns any
 * excess as an L1 change output, so a too-big reserve merely leaves a few
 * duffs unshielded in the wallet. [spendableUtxoCount] comes from dashj,
 * which is a valid proxy for the SDK wallet's input count because the two
 * are kept in byte-exact UTXO parity (the dual-engine parity harness gates
 * every wallet shield). Clamped to a 1000-duff minimum so a degenerate
 * count still reserves something meaningful.
 */
internal fun assetLockMaxFeeReserve(spendableUtxoCount: Int): Dash {
    val estimatedTxBytes = spendableUtxoCount.coerceAtLeast(0).toLong() * 148L + 300L
    return Dash((estimatedTxBytes * 2L).coerceAtLeast(1000L))
}

/** A one-shot fee adjustment for a max shielded withdraw — see [shieldedMaxFeeAdjustment]. */
internal data class ShieldedMaxFeeAdjustment(val deficitCredits: Long, val adjustedAmount: Dash)

/**
 * The fee-adjusted retry amount for a max ("spend everything") shielded
 * withdraw that failed note selection, or null when [failure] isn't that
 * exact, provably-pre-broadcast shape (or the numbers don't make sense).
 *
 * The note-selection fee is computed Rust-side from the selected note
 * count and platform fee constants — not computable app-side — so a max
 * spend can only learn it from the failure itself: `required` is
 * amount + exact fee, and `required - available` is precisely what the
 * request must shrink by. A max spend selects all notes either way, so
 * the fee on the retry equals the fee baked into `required` — one
 * adjusted attempt converges. The adjusted amount is floored to a whole
 * duff ([creditsToDash]) since the app's money type can't carry sub-duff
 * credits.
 */
internal fun shieldedMaxFeeAdjustment(
    requested: Dash,
    failure: SdkWriteResult.NotBroadcast
): ShieldedMaxFeeAdjustment? {
    val match = notBroadcastMessages(failure)
        .firstNotNullOfOrNull { INSUFFICIENT_SHIELDED_BALANCE.find(it) } ?: return null
    val available = match.groupValues[1].toLongOrNull() ?: return null
    val required = match.groupValues[2].toLongOrNull() ?: return null
    val deficitCredits = required - available
    if (deficitCredits <= 0) return null
    val requestedCredits = try {
        dashToCredits(requested)
    } catch (e: ArithmeticException) {
        return null
    }
    val adjustedCredits = requestedCredits - deficitCredits
    if (adjustedCredits <= 0) return null
    val adjustedAmount = creditsToDash(adjustedCredits)
    return if (adjustedAmount.isPositive) ShieldedMaxFeeAdjustment(deficitCredits, adjustedAmount) else null
}

/**
 * App-scoped owner of the shielded internal-transfer spend, so the ~30s
 * Halo 2 proof + broadcast survives the transfer screen (and its
 * ViewModel): the user can dismiss the proving dialog, switch screens or
 * background the app, and the operation still completes and reports.
 *
 * ## State contract
 *
 * [submitState] is the single source of truth for the in-flight
 * operation; [ShieldedTransferViewModel] only MIRRORS it into its
 * UIState — a recreated ViewModel re-attaches to an in-flight Proving
 * (the overlay/in-progress button shows again) or to an unacknowledged
 * terminal result, and can never re-submit by observing:
 *
 * - [submit] is the only way to start a spend, and it atomically refuses
 *   unless the current state is [ShieldedSubmitState.Idle] or the
 *   retry-safe [ShieldedSubmitState.NotSent] — one broadcast attempt per
 *   user confirmation, exactly the funds-safety semantics the ViewModel
 *   had (`canContinue` mirrors the same rule for the button).
 * - [acknowledge] (the user handled the result on screen) resets any
 *   terminal state; an in-flight Proving is never cleared by anything.
 * - [clearForNewVisit] (fresh screen entry) drops the low-stakes
 *   Success/NotSent leftovers but keeps the funds-critical
 *   MayHaveGoneThrough / LockedPendingShield sticky until acknowledged.
 *
 * ## Outcome surfacing
 *
 * A terminal outcome is announced exactly once, where the user is:
 * - transfer screen visible → the screen's own state machine (success
 *   navigation, inline error, terminal overlays) — nothing extra here;
 * - anywhere else (elsewhere in the app OR backgrounded) → a system
 *   notification through the app's [NotificationService] (generic
 *   channel) — the dialog copy promises "We will notify you", and only
 *   a notification is durable enough to honor it (an in-app toast was
 *   missed live during an activity-recreation gap). Tapping the success
 *   one opens the More screen with the existing "Transfer completed"
 *   toast. When it lands while foregrounded-elsewhere, Success is also
 *   auto-acknowledged so a later return to a still-alive transfer screen
 *   doesn't re-run the success navigation.
 */
@Singleton
class ShieldedTransferExecutor @Inject constructor(
    private val shieldedBalanceService: ShieldedBalanceService,
    private val walletDataProvider: WalletData,
    private val notificationService: NotificationService,
    @ApplicationContext private val appContext: Context,
    private val applicationScope: CoroutineScope
) {
    companion object {
        private val log = LoggerFactory.getLogger(ShieldedTransferExecutor::class.java)

        /** One tag: a newer outcome replaces a stale one instead of stacking. */
        internal const val NOTIFICATION_TAG = "shielded_transfer_outcome"

        /**
         * Stall-watchdog threshold: no terminal result within this →
         * [ShieldedSubmitState.Stalled]. 40s (Brian's call): on slow
         * hardware (Galaxy S21) the Halo 2 proof + asset-lock islock
         * verification can legitimately run past this, but the notice now
         * reads unambiguously as still-in-progress (spinner, "Continue in
         * the background"), so firing early is informative, not alarming.
         * It stays a SOFT advisory: the spend continues past it; this is
         * not a cancel/timeout.
         */
        internal const val STALL_TIMEOUT_MS = 40L * 1000
    }

    /** Test seam: the spend blocks for a ~30s Halo 2 proof and must stay off main. */
    var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Test seam: whether the app is foregrounded (any activity STARTED) —
     * the BlockchainServiceImpl [ProcessLifecycleOwner] precedent. Not in
     * the foreground → outcomes go to a system notification.
     */
    var isAppInForeground: () -> Boolean = {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    /**
     * Test seam: the notification content intent — the existing
     * post-success route ([MainActivity] → More screen, optionally with
     * its "Transfer completed" toast argument).
     */
    var moreScreenIntent: (showTransferCompletedToast: Boolean) -> Intent? = { showToast ->
        MainActivity.createIntent(
            appContext,
            R.id.moreFragment,
            bundleOf(MoreFragment.ARG_SHOW_TRANSFER_COMPLETED_TOAST to showToast)
        )
    }

    /**
     * True while the transfer screen is the resumed foreground UI —
     * maintained by [ShieldedBalanceActivity]'s onResume/onPause. While
     * visible, the screen's own state machine surfaces outcomes and the
     * executor stays quiet.
     */
    @Volatile
    var transferUiVisible: Boolean = false

    private val _submitState = MutableStateFlow<ShieldedSubmitState>(ShieldedSubmitState.Idle)

    /** The in-flight/terminal state of the one allowed transfer operation. */
    val submitState: StateFlow<ShieldedSubmitState> = _submitState.asStateFlow()

    /**
     * Start the spend on the application scope. Returns false — and
     * submits NOTHING — unless the current state is Idle or the
     * provably-pre-broadcast NotSent (retry). The Idle→Proving transition
     * is atomic under [this], so two racing confirmations can never both
     * pass the gate.
     *
     * [isMaxSpend] — the user confirmed the full available balance
     * ("spend everything"): the exact fee is not knowable app-side in
     * either direction, so instead of failing the Max flow, ONE
     * fee-adjusted retry is made inside the same Proving operation when
     * the first attempt fails a provably-pre-broadcast selection:
     * - FromShielded: the Rust-computed note-selection fee — the failure
     *   reports the exact deficit, which the retry subtracts
     *   ([shieldedMaxFeeAdjustment]);
     * - ToShielded: the L1 asset-lock fee — the shortfall message carries
     *   no fee figure, so the retry withholds an ESTIMATED reserve sized
     *   from the wallet's spendable UTXO count ([assetLockMaxFeeReserve];
     *   over-reserve is lossless — the builder returns excess as change).
     * Safe either way: both failures are strictly pre-broadcast (nothing
     * submitted, selections released).
     */
    fun submit(direction: ShieldedTransferDirection, amount: Dash, isMaxSpend: Boolean = false): Boolean {
        synchronized(this) {
            val state = _submitState.value
            if (state != ShieldedSubmitState.Idle && state !is ShieldedSubmitState.NotSent) {
                log.warn("shielded transfer submit refused: an operation is {} — not re-submitting", state)
                return false
            }
            _submitState.value = ShieldedSubmitState.Proving
        }

        applicationScope.launch {
            // Stall watchdog: the spend can wedge inside an uncancellable
            // native frame (live incident: a Rust FFI deadlock — coroutine
            // cancellation can never interrupt a JNI call that doesn't
            // return), so a user must not be left on an eternal spinner
            // with no story. After STALL_TIMEOUT_MS without a terminal
            // result, Proving → Stalled (funds-honest: we do NOT know
            // whether anything broadcast — Stalled stays non-resubmittable
            // like an in-flight op) and the state is announced through the
            // normal outcome surfacing, exactly once. The wedged call is
            // deliberately NOT cancelled: if it eventually returns, its
            // real outcome below supersedes Stalled and is announced too.
            val watchdog = launch {
                delay(STALL_TIMEOUT_MS)
                val stalled = synchronized(this@ShieldedTransferExecutor) {
                    if (_submitState.value == ShieldedSubmitState.Proving) {
                        _submitState.value = ShieldedSubmitState.Stalled()
                        true
                    } else {
                        false
                    }
                }
                if (stalled) {
                    log.warn(
                        "shielded transfer has no outcome after {} ms — surfacing Stalled " +
                            "(the spend keeps running; a terminal result will supersede)",
                        STALL_TIMEOUT_MS
                    )
                    surfaceOutcome(ShieldedSubmitState.Stalled())
                }
            }
            var result = withContext(ioDispatcher) { attemptSpend(direction, amount) }
            // Max-spend fee convergence: the first attempt failed a
            // provably-pre-broadcast selection (nothing submitted, the
            // selection released), and a max spend selects everything
            // either way — so ONE fee-adjusted retry is safe. FromShielded
            // subtracts the exact deficit the note selection reported;
            // ToShielded withholds an estimated (lossless — excess returns
            // as change) L1 fee reserve, since the asset-lock shortfall
            // message carries no fee figure. One shot only: the retry
            // result never re-adjusts. Still inside Proving; the watchdog
            // spans both attempts.
            val firstResult = result
            if (isMaxSpend && firstResult is SdkWriteResult.NotBroadcast) {
                when (direction) {
                    ShieldedTransferDirection.FromShielded -> {
                        val adjustment = shieldedMaxFeeAdjustment(amount, firstResult)
                        if (adjustment != null) {
                            log.info(
                                "max withdraw auto-adjusting for shielded fee: requested {}, " +
                                    "deficit {} credits, retrying once with {}",
                                amount,
                                adjustment.deficitCredits,
                                adjustment.adjustedAmount
                            )
                            result = withContext(ioDispatcher) {
                                attemptSpend(direction, adjustment.adjustedAmount)
                            }
                        }
                    }
                    ShieldedTransferDirection.ToShielded -> {
                        if (isAssetLockSelectionShort(firstResult)) {
                            val utxoCount = try {
                                walletDataProvider.spendableUtxoCount()
                            } catch (e: Exception) {
                                log.warn(
                                    "max shield: spendable UTXO count unavailable — not auto-adjusting",
                                    e
                                )
                                null
                            }
                            val reserve = utxoCount?.let(::assetLockMaxFeeReserve)
                            val adjusted = reserve?.let { amount - it }
                            if (reserve != null && adjusted != null && adjusted.isPositive) {
                                log.info(
                                    "max shield auto-adjusting for L1 asset-lock fee: requested {}, " +
                                        "reserve {} duffs ({} UTXOs), retrying once with {}",
                                    amount,
                                    reserve.duffs,
                                    utxoCount,
                                    adjusted
                                )
                                result = withContext(ioDispatcher) { attemptSpend(direction, adjusted) }
                            }
                        }
                    }
                }
            }
            watchdog.cancel()
            val outcome = when (val spendResult = result) {
                is SdkWriteResult.Broadcast -> when (spendResult.value) {
                    ShieldFromWalletOutcome.SHIELD_PENDING_RETRY -> {
                        log.warn("wallet shield locked but pending — surfacing auto-retry state")
                        ShieldedSubmitState.LockedPendingShield
                    }
                    else -> ShieldedSubmitState.Success
                }
                is SdkWriteResult.NotBroadcast -> {
                    log.info("shielded transfer not sent: {}", spendResult.reason)
                    ShieldedSubmitState.NotSent(spendResult.reason)
                }
                is SdkWriteResult.Ambiguous -> {
                    log.warn("shielded transfer ambiguous — surfacing terminal state")
                    ShieldedSubmitState.MayHaveGoneThrough
                }
            }
            // Under the lock so the watchdog's Proving-check-and-set can
            // never clobber a just-landed terminal result; a terminal
            // outcome landing AFTER Stalled overwrites it (supersedes).
            synchronized(this@ShieldedTransferExecutor) {
                _submitState.value = outcome
            }
            surfaceOutcome(outcome)
        }
        return true
    }

    /** One spend attempt against the service — the ~30s Halo 2 proof + broadcast. */
    private suspend fun attemptSpend(
        direction: ShieldedTransferDirection,
        amount: Dash
    ): SdkWriteResult<*> = try {
        when (direction) {
            ShieldedTransferDirection.ToShielded ->
                shieldedBalanceService.shieldFromWallet(amount)
            ShieldedTransferDirection.FromShielded ->
                shieldedBalanceService.withdrawToCore(
                    walletDataProvider.freshReceiveAddressString(),
                    amount
                )
        }
    } catch (e: Exception) {
        // freshReceiveAddress failures happen strictly pre-broadcast
        SdkWriteResult.NotBroadcast("failed to prepare transfer", e)
    }

    /**
     * The user handled the result on screen — any terminal state resets
     * to Idle. Stalled only dismisses its overlay ([ShieldedSubmitState
     * .Stalled.acknowledged]): the underlying spend may still be wedged
     * in flight, so the state stays non-resubmittable until a real
     * terminal outcome supersedes it (or the process restarts).
     */
    fun acknowledge() {
        synchronized(this) {
            when (val state = _submitState.value) {
                ShieldedSubmitState.Proving -> Unit
                is ShieldedSubmitState.Stalled -> {
                    _submitState.value = state.copy(acknowledged = true)
                }
                else -> _submitState.value = ShieldedSubmitState.Idle
            }
        }
    }

    /**
     * Typing/direction changes clear an inline retry-safe error, exactly
     * as before. Only NotSent → Idle; anything else is untouched.
     */
    fun clearRetryableResult() {
        synchronized(this) {
            if (_submitState.value is ShieldedSubmitState.NotSent) {
                _submitState.value = ShieldedSubmitState.Idle
            }
        }
    }

    /**
     * A FRESH screen entry (new activity instance): don't resurrect an
     * already-surfaced Success (its navigation would fire spuriously) or
     * a stale inline NotSent — but an in-flight Proving and the
     * funds-critical MayHaveGoneThrough / LockedPendingShield / Stalled
     * states stay: the first two until the user acknowledges them,
     * Stalled until a real terminal outcome supersedes it.
     */
    fun clearForNewVisit() {
        synchronized(this) {
            val state = _submitState.value
            if (state == ShieldedSubmitState.Success || state is ShieldedSubmitState.NotSent) {
                _submitState.value = ShieldedSubmitState.Idle
            }
        }
    }

    /**
     * Announce a terminal outcome wherever the user is — see the class
     * KDoc. Never throws: surfacing is best-effort and must not disturb
     * the recorded state.
     */
    private fun surfaceOutcome(state: ShieldedSubmitState) {
        val content = shieldedOutcomeNotification(state) ?: return
        try {
            // The dialog copy promises "We will notify you when it's done",
            // and an in-app toast is too ephemeral to honor that (observed
            // live: completion landed during an activity-recreation gap and
            // the toast was never seen). So the system notification posts
            // whenever the user is NOT watching the transfer screen —
            // Android surfaces it fine while the app is foregrounded. Only
            // the visible transfer screen suppresses it: its own state
            // machine tells the story (success navigation / overlays).
            if (transferUiVisible && isAppInForeground()) {
                return // the visible transfer screen surfaces it itself
            }
            notificationService.showNotification(
                NOTIFICATION_TAG,
                appContext.getString(content.messageRes),
                title = appContext.getString(content.titleRes),
                intent = moreScreenIntent(content.showsTransferCompletedToast)
            )
            // The success story is fully told; a still-alive transfer
            // screen must not ALSO run its success navigation when the
            // user wanders back. Failure states stay sticky (inline retry
            // / must-acknowledge overlays on the next visit).
            if (isAppInForeground() && state == ShieldedSubmitState.Success) {
                synchronized(this) {
                    if (_submitState.value == ShieldedSubmitState.Success) {
                        _submitState.value = ShieldedSubmitState.Idle
                    }
                }
            }
        } catch (t: Throwable) {
            log.warn("failed to announce the shielded transfer outcome", t)
        }
    }
}
