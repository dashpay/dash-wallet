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

package de.schildbach.wallet.service

import org.bitcoinj.core.MasternodeSync
import java.util.EnumSet

/**
 * Why the dashj L1 engine runs (or not) in this service instance, resolved
 * from the same two inputs [BlockchainServiceImpl] already keeps:
 * `dashjHeldByCutover` (the SDK owns L1) and `dashjSyncDiagnostic` (the Tools
 * › "dashj sync (diagnostic)" toggle).
 */
enum class DashjEngineRole {
    /** Pre-cutover: dashj owns L1. Everything runs as it always has. */
    PRIMARY,

    /** Cutover committed, toggle off: no dashj peergroup at all. */
    HELD,

    /**
     * Cutover committed, toggle on: the dashj engine runs ONLY so its balance
     * and transactions can be compared with the SDK's. It needs blocks and the
     * masternode/quorum lists, but no InstantSend or ChainLocks, and it should
     * persist the (large) wallet as rarely as is safe.
     */
    DIAGNOSTIC;

    val isDiagnostic: Boolean get() = this == DIAGNOSTIC

    companion object {
        @JvmStatic
        fun resolve(dashjHeldByCutover: Boolean, dashjSyncDiagnostic: Boolean): DashjEngineRole = when {
            !dashjHeldByCutover -> PRIMARY
            dashjSyncDiagnostic -> DIAGNOSTIC
            else -> HELD
        }
    }
}

/** The sync flags the diagnostic engine drops: no InstantSend or ChainLock requests. */
private val DIAGNOSTIC_EXCLUDED_SYNC_FLAGS: Set<MasternodeSync.SYNC_FLAGS> = EnumSet.of(
    MasternodeSync.SYNC_FLAGS.SYNC_INSTANTSENDLOCKS,
    MasternodeSync.SYNC_FLAGS.SYNC_CHAINLOCKS
)

/**
 * The dashj sync flags for [role], derived from [base] (the app's
 * `Constants.SYNC_FLAGS`, which IS dashj's static
 * `MasternodeSync.SYNC_DEFAULT_SPV` — `initDash` hands that very instance to
 * `MasternodeSync`).
 *
 * DIAGNOSTIC gets a COPY without `SYNC_INSTANTSENDLOCKS` and `SYNC_CHAINLOCKS`.
 * In dashj 22.0.5 those two flags gate everything IS/CL does on an SPV
 * client: the `getdata` for islock / clsig inventory
 * (InstantSendManager.java:320, ChainLocksHandler.java:678) and starting the
 * LLMQ background thread in `DashSystem.start()` (DashSystem.java:398-401) —
 * the only thing that drains and verifies queued islocks and recovered
 * signatures. Sporks, headers, blocks, the masternode list and quorums keep
 * their flags. Never mutates [base]: dashj's static is shared process-wide.
 *
 * Every other role gets [base] itself — the exact object `initDash` installed,
 * so PRIMARY and HELD are unchanged, and a later PRIMARY start in the same
 * process (ROLLBACK) puts the original set back.
 */
fun dashjSyncFlags(
    role: DashjEngineRole,
    base: EnumSet<MasternodeSync.SYNC_FLAGS>
): EnumSet<MasternodeSync.SYNC_FLAGS> =
    if (role.isDiagnostic) {
        EnumSet.copyOf(base).apply { removeAll(DIAGNOSTIC_EXCLUDED_SYNC_FLAGS) }
    } else {
        base
    }

/**
 * Installs the [role]'s sync flags on [masternodeSync] — call it while the
 * dashj peergroup is DOWN, before a new one is built and started: the IS/CL
 * inventory handlers read the flags per message, and `DashSystem.start()`
 * (run from `PeerGroup.startAsync()`) reads them once to decide on the LLMQ
 * thread. Going back from DIAGNOSTIC to full flags is safe the same way:
 * `DashSystem.close()` (run synchronously by every peergroup stop) interrupts
 * the LLMQ thread only if the IS flag is set, which it is not in DIAGNOSTIC,
 * where that thread never started.
 *
 * The VERIFY flags are left alone in every role, deliberately. They do not
 * decide WHETHER a lock is processed, only whether it is checked first. With
 * the sync flags dropped nothing requests islocks or clsigs, so honest peers
 * send none and nothing gets verified. A clsig a peer pushes unasked is still
 * handled on the peer thread (ChainLocksHandler.java:700), and without
 * `VERIFY_FLAGS.CHAINLOCK` dashj would ACCEPT it unverified as its best
 * chainlock (the else-path of ChainLocksHandler.java:227), which then limits
 * reorgs. Keeping verification on is the safe answer for that corner case and
 * costs nothing otherwise.
 *
 * @return true when the flags changed.
 */
fun applyDashjEngineSyncFlags(
    masternodeSync: MasternodeSync,
    role: DashjEngineRole,
    base: EnumSet<MasternodeSync.SYNC_FLAGS>
): Boolean {
    val wanted = dashjSyncFlags(role, base)
    if (masternodeSync.syncFlags == wanted) return false
    masternodeSync.syncFlags = wanted
    return true
}
