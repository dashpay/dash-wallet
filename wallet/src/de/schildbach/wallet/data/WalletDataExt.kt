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

package de.schildbach.wallet.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Address
import org.bitcoinj.core.Context

/**
 * Issue a fresh receive address OFF the calling thread.
 *
 * dashj's `freshReceiveAddress()` issues a key and then forces a SYNCHRONOUS
 * full-wallet save, and that save re-serializes every DashPay friend key
 * chain — measured 1.2s of `[main]` at 215 friend chains (13.7ms on a tiny
 * wallet), i.e. a guaranteed jank/ANR when called from the main thread. This
 * helper imposes [Dispatchers.IO] at the seam (the same
 * `withContext(IO)` + `Context.propagate` pattern
 * [de.schildbach.wallet.ui.payments.PaymentsViewModel.getFreshAddress]
 * established), so main-thread callers — LiveData observers, click
 * listeners, dialog callbacks — can simply `launch` and call this.
 */
suspend fun WalletData.freshReceiveAddressOffMain(): Address = withContext(Dispatchers.IO) {
    @Suppress("DEPRECATION")
    wallet?.let { Context.propagate(it.context) }
    freshReceiveAddress()
}

/** Base58 variant of [freshReceiveAddressOffMain] — same off-main contract. */
suspend fun WalletData.freshReceiveAddressStringOffMain(): String =
    freshReceiveAddressOffMain().toBase58()
