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
package de.schildbach.wallet.ui.send

import de.schildbach.wallet.payments.SendEngineNotSyncedException
import de.schildbach.wallet.payments.SendNotSdkRoutableException
import de.schildbach.wallet.payments.SendSignerLockedException

/**
 * How [SendCoinsFragment.showFailureDialog] renders a failed send. Pure
 * classification so the mapping is host-testable: the fragment resolves each
 * kind to its string resource.
 *
 * The invariant (on-device bug 11.10.44): a send failure may only be blamed
 * on syncing when it provably IS the sync gate — a fully-synced self-send
 * that failed for routability was shown as "wallet is not fully synced".
 */
enum class SendFailureKind {
    /** The SDK engine's L1 funding gate is closed (scan not caught up) — the one honest "not synced" case. */
    NOT_SYNCED,

    /** The payment shape is not SDK-routable post-cutover (multi-recipient, custom selection, …). */
    NOT_SUPPORTED,

    /**
     * The SDK engine could not SIGN the assembled transaction (Keystore
     * mnemonic locked / auth window expired). Nothing was broadcast and the
     * reservation was released — the identical send works after an unlock,
     * so the copy says "unlock and try again", never "payment failed".
     */
    SIGNER_LOCKED,

    /**
     * Any other [IllegalStateException] from the send path: internal
     * machinery — show a generic failure, NEVER the raw exception text.
     */
    GENERIC_INTERNAL,

    /** Everything else keeps the original verbatim rendering (`exception.toString()`). */
    VERBATIM
}

/** The pure type-based mapping — see [SendFailureKind]. */
fun classifySendFailure(exception: Exception): SendFailureKind = when (exception) {
    is SendEngineNotSyncedException -> SendFailureKind.NOT_SYNCED
    is SendNotSdkRoutableException -> SendFailureKind.NOT_SUPPORTED
    // Before the generic IllegalStateException arm: SendSignerLockedException
    // IS an IllegalStateException and must keep its own honest copy.
    is SendSignerLockedException -> SendFailureKind.SIGNER_LOCKED
    is IllegalStateException -> SendFailureKind.GENERIC_INTERNAL
    else -> SendFailureKind.VERBATIM
}
