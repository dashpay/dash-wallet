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

/**
 * Whether the SDK's deferred DashPay contact-account-build queue can be
 * drained right now — what a queued count that has stopped moving MEANS
 * ([deferredBuildsSettled]). The app cannot see why an individual entry is
 * queued, but it does know whether the drain that would build it has run.
 */
enum class AccountBuildDrainState {
    /**
     * No drain has been attempted for this wallet in this process yet, so a
     * count that is not moving says nothing about whether it is stuck. The
     * restore shape: the SDK queues the builds during the scan, but the app
     * recovers the identity — and only then drains — after the scan.
     */
    NOT_ATTEMPTED,

    /**
     * The last drain could not start because the Keystore-held seed was
     * unreadable while the device was LOCKED (QA phone 2, 12.0.0-qa28: 17 ×
     * "contact-crypto drain deferred … mnemonic resolver failed with code 3"
     * between 04:47 and 14:17, all while the app sat in the background). A
     * known, temporary cause: the queue cannot move, so its standing still is
     * no evidence it is stuck, and the drain resumes once the device unlocks.
     */
    BLOCKED_DEVICE_LOCKED,

    /**
     * The last drain ran (or had nothing to do, or can never run on a
     * watch-only wallet), or it failed while the device was UNLOCKED — no
     * known temporary cause. A count that stays put after this is the
     * "stuck, not draining" evidence [deferredBuildsSettled] settles on.
     */
    RAN
}

/**
 * How long after the device is first seen unlocked a lock-blocked drain keeps
 * counting as [AccountBuildDrainState.BLOCKED_DEVICE_LOCKED] without a new
 * drain attempt. Every contact pass (15 s ticker in the foreground) triggers
 * one, throttled to a minute, so five minutes is ample; past it, the block is
 * stale and the queue falls back to the ordinary pinned-count rule, so a
 * missing retry can never hold the balance not-final indefinitely.
 */
internal const val ACCOUNT_BUILD_UNLOCK_GRACE_MS = 5 * 60_000L

/**
 * Records the outcome of each contact-account-build drain per SDK wallet and
 * answers [state] for the balance pipeline. Thread-safe; in-memory only (a new
 * process starts at [AccountBuildDrainState.NOT_ATTEMPTED] and the binder
 * drains again). Pure apart from the injected [deviceLocked] probe and
 * [elapsedMs] clock — host-testable.
 */
internal class AccountBuildDrainTracker(
    private val deviceLocked: () -> Boolean,
    private val elapsedMs: () -> Long,
    private val unlockGraceMs: Long = ACCOUNT_BUILD_UNLOCK_GRACE_MS
) {
    private class Outcome(val blockedByLock: Boolean) {
        /** When the device was first seen unlocked after a lock-blocked drain; null while still locked. */
        var unlockedSinceMs: Long? = null
    }

    private val outcomes = HashMap<String, Outcome>()

    /** A drain pass ended unblocked: scheduled, nothing queued, or watch-only. */
    @Synchronized
    fun onDrainRan(walletIdHex: String) {
        outcomes[walletIdHex] = Outcome(blockedByLock = false)
    }

    /**
     * The drain could not start (seed verify / Keystore). Returns whether the
     * device was locked, i.e. whether this is the known temporary cause.
     */
    @Synchronized
    fun onDrainDeferred(walletIdHex: String): Boolean {
        val locked = deviceLocked()
        outcomes[walletIdHex] = Outcome(blockedByLock = locked)
        return locked
    }

    /** The wallet was removed; a re-created one starts at [AccountBuildDrainState.NOT_ATTEMPTED]. */
    @Synchronized
    fun forget(walletIdHex: String) {
        outcomes.remove(walletIdHex)
    }

    @Synchronized
    fun state(walletIdHex: String): AccountBuildDrainState {
        val outcome = outcomes[walletIdHex] ?: return AccountBuildDrainState.NOT_ATTEMPTED
        if (!outcome.blockedByLock) return AccountBuildDrainState.RAN
        if (deviceLocked()) {
            outcome.unlockedSinceMs = null
            return AccountBuildDrainState.BLOCKED_DEVICE_LOCKED
        }
        val now = elapsedMs()
        val unlockedSince = outcome.unlockedSinceMs ?: now.also { outcome.unlockedSinceMs = it }
        return if (now - unlockedSince < unlockGraceMs) {
            AccountBuildDrainState.BLOCKED_DEVICE_LOCKED
        } else {
            AccountBuildDrainState.RAN
        }
    }
}
