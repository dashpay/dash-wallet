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

import org.dashfoundation.dashsdk.security.KeystoreDeviceLockedException

/**
 * Why the SDK wallet bind is not established right now — the "SDK setup
 * pending" state of docs/upgrade-memory-and-sync-plan.md (Phase 1a item 3).
 *
 * Under the no-fallback cutover policy a failed bind never hands L1 back to
 * dashj, so the failure has to be CLASSIFIED and SURFACED instead of
 * counted toward a rollback. The SDK's master key is lock-bound
 * (`setUnlockedDeviceRequired`), so the common case is a denial while the
 * device is locked, which heals on unlock. The case that needs the user is
 * a denial while the device reports UNLOCKED: PR #1555's walletB (HONOR
 * PTP-N49) produced 7 of those out of 16 denials and none healed on
 * unlock.
 *
 * @property needsUser true when retrying alone will not fix it and the
 *   user has to see an error surface (restart the phone, or turn on the
 *   Tools › dashj sync diagnostic as the manual escape).
 */
enum class SdkBindBlocker(val needsUser: Boolean) {
    /** Keystore denied the lock-bound key while the device is locked. Heals on unlock. */
    DEVICE_LOCKED(false),

    /**
     * Keystore denied while the device reports unlocked, fewer than
     * [KEYSTORE_PROBLEM_AFTER_UNLOCKED_DENIALS] times in a row. Often clears
     * within seconds (Keystore2 briefly out of step with the keyguard).
     */
    KEYSTORE_DENIED_UNLOCKED(false),

    /** Repeated denials with the device unlocked: this phone's keystore is refusing the SDK key. */
    KEYSTORE_PROBLEM(true),

    /** A non-keystore failure, retried on the ladder. */
    OTHER(false),

    /** Non-keystore failures kept coming past the ladder's fast steps. */
    SETUP_FAILED(true)
}

/**
 * Thrown by [SdkWalletBinder] INSTEAD of attempting the bind when the device
 * is provably locked (`KeyguardManager.isDeviceLocked`). The SDK's master
 * alias is lock-bound, so the attempt could only end in a keystore denial —
 * after paying the scrypt key derivation and a keystore round trip, on a
 * background start that is already under the 10 s bind-application ANR
 * budget. Classified exactly like the denial it pre-empts
 * ([SdkBindBlocker.DEVICE_LOCKED]); the unlock receiver and the foreground
 * edge retry the pass.
 */
class SdkBindDeferredWhileLockedException : IllegalStateException(
    "SDK bind deferred: the device is locked, so the lock-bound master alias would be denied"
)

/** One failed bind pass, as published by [SdkWalletBinder.lastBindFailure]. */
data class SdkBindFailure(
    val cause: Throwable,
    /** [SdkWalletBinder.consecutiveBindFailures] after this pass. */
    val consecutiveFailures: Int,
    val atMs: Long
)

/** Consecutive keystore denials with the device UNLOCKED before [SdkBindBlocker.KEYSTORE_PROBLEM]. */
internal const val KEYSTORE_PROBLEM_AFTER_UNLOCKED_DENIALS = 3

/**
 * Consecutive non-keystore failures before [SdkBindBlocker.SETUP_FAILED].
 * The retry ladder is 5/15/30/60 s then hourly, so five failures means the
 * fast steps are spent.
 */
internal const val SETUP_FAILED_AFTER_OTHER_FAILURES = 5

private const val MAX_CAUSE_DEPTH = 8

private val keystoreDenialMessage = Regex(
    "lock-bound alias|UserNotAuthenticatedException|Keystore denied|KeystoreDeviceLockedException",
    RegexOption.IGNORE_CASE
)

private inline fun <T> Throwable.walkCauses(block: (Throwable) -> T?): T? {
    var t: Throwable? = this
    var depth = 0
    while (t != null && depth++ < MAX_CAUSE_DEPTH) {
        block(t)?.let { return it }
        t = t.cause
    }
    return null
}

/**
 * Whether [t] (or a cause) is the Android keystore refusing the SDK's
 * lock-bound master key. The SDK throws a typed
 * [KeystoreDeviceLockedException]; the message match is the fallback for
 * the same denial arriving wrapped or re-thrown as a plain exception.
 */
fun isKeystoreDenial(t: Throwable): Boolean =
    t.walkCauses { c ->
        if (c is KeystoreDeviceLockedException || c is SdkBindDeferredWhileLockedException) true
        else if (c.message?.let(keystoreDenialMessage::containsMatchIn) == true) true
        else null
    } ?: false

/**
 * What the SDK's own lock-state sample said at denial time, when the typed
 * exception is present; null otherwise.
 */
fun keystoreDenialReportsDeviceLocked(t: Throwable): Boolean? =
    t.walkCauses { c ->
        when (c) {
            is SdkBindDeferredWhileLockedException -> true
            is KeystoreDeviceLockedException -> c.deviceReportsLocked
            else -> null
        }
    }

/**
 * Classify one failed bind pass. Pure.
 *
 * @param deviceProvablyLocked `KeyguardManager.isDeviceLocked` at
 *   classification time.
 * @param unlockedDenialStreak consecutive keystore denials seen with the
 *   device unlocked, INCLUDING this one if it is one.
 * @param otherFailureStreak consecutive non-keystore failures, including
 *   this one if it is one.
 */
fun classifyBindFailure(
    cause: Throwable,
    deviceProvablyLocked: Boolean,
    unlockedDenialStreak: Int,
    otherFailureStreak: Int
): SdkBindBlocker {
    if (isKeystoreDenial(cause)) {
        val locked = deviceProvablyLocked || keystoreDenialReportsDeviceLocked(cause) == true
        return when {
            locked -> SdkBindBlocker.DEVICE_LOCKED
            unlockedDenialStreak >= KEYSTORE_PROBLEM_AFTER_UNLOCKED_DENIALS -> SdkBindBlocker.KEYSTORE_PROBLEM
            else -> SdkBindBlocker.KEYSTORE_DENIED_UNLOCKED
        }
    }
    return if (otherFailureStreak >= SETUP_FAILED_AFTER_OTHER_FAILURES) {
        SdkBindBlocker.SETUP_FAILED
    } else {
        SdkBindBlocker.OTHER
    }
}
