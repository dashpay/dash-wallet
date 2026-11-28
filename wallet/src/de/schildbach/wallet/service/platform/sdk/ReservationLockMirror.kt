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

import de.schildbach.wallet.Constants
import de.schildbach.wallet.data.WalletData
import javax.inject.Inject
import javax.inject.Singleton
import org.bitcoinj.core.Context
import org.bitcoinj.core.Transaction

/**
 * TRANSITION-ONLY (delete with Phase 2, #1521): mirrors an SDK deferred
 * payment's engine-side UTXO reservation into the foundation dashj wallet's
 * app locks ([org.bitcoinj.wallet.Wallet.lockOutput]), so dashj-side
 * spenders with their own coin selection and no view of the SDK reservation
 * — manual sends, the background CoinJoin mixer (the original reason
 * lockOutput exists) — cannot double-select the reserved outpoints while
 * the deferred payment is in flight.
 *
 * This class exists so SDK-routed senders (Maya swaps; BIP70 keeps its
 * private twin in `SendCoinsTaskRunner` for now) stay free of dashj types:
 * ALL the dashj here is transition bookkeeping that dies wholesale when the
 * dashj engine is retired. Best-effort by contract — callers must treat a
 * throw as non-fatal (the engine reservation, not this mirror, is the real
 * double-select backstop).
 */
@Singleton
class ReservationLockMirror @Inject constructor(
    private val walletData: WalletData
) {
    /** Lock (or unlock) every outpoint [payment]'s signed tx spends. */
    fun setLocks(payment: SdkDeferredPayment, locked: Boolean) {
        val wallet = walletData.wallet ?: return
        Context.propagate(wallet.context)
        val tx = Transaction(Constants.NETWORK_PARAMETERS, payment.rawTxBytes)
        for (input in tx.inputs) {
            val outpoint = input.outpoint
            if (locked) {
                wallet.lockOutput(outpoint)
            } else {
                wallet.unlockOutput(outpoint)
            }
        }
    }
}
