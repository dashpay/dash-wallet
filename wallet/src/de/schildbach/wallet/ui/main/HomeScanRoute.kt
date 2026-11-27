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

package de.schildbach.wallet.ui.main

import de.schildbach.wallet.ui.payments.send.AdvancedScanAction
import de.schildbach.wallet.ui.payments.send.AdvancedSendViewModel
import org.dash.wallet.common.payments.parsers.AddressNetwork

/** Where a QR scanned from the Home "Scan QR" shortcut goes. */
sealed class HomeScanRoute {
    /** The legacy Home handling: DashConnect, then `StringInputParser` (BIP70, sweep, raw tx, Core address). */
    object Legacy : HomeScanRoute()

    /** Advanced mode: a destination of this network (Core, Platform, Shielded, identity), pre-filled. */
    data class ExternalSend(val input: String) : HomeScanRoute()

    /** Advanced mode: an address of another Dash network. */
    object WrongNetwork : HomeScanRoute()
}

/**
 * Routes a Home scan. With [advancedMode] off (also while the setting is unknown) everything
 * keeps the legacy path. With it on, the Advanced Send tab's [AdvancedSendViewModel.scanActionFor]
 * classifies: plain destinations go to the external send flow, a wrong-network address shows
 * that error, and everything else (DashConnect, BIP70/AnyPay requests, private keys, raw
 * transactions, unrecognised input) keeps the legacy path, which reports its own errors.
 */
fun homeScanRoute(
    input: String,
    advancedMode: Boolean,
    network: AddressNetwork,
    supportsConnect: Boolean,
    isDashConnect: (String) -> Boolean
): HomeScanRoute {
    if (!advancedMode) return HomeScanRoute.Legacy
    return when (val action = AdvancedSendViewModel.scanActionFor(input, network, supportsConnect, isDashConnect)) {
        is AdvancedScanAction.ExternalSend -> HomeScanRoute.ExternalSend(action.input)
        is AdvancedScanAction.Error -> if (action.wrongNetwork) HomeScanRoute.WrongNetwork else HomeScanRoute.Legacy
        is AdvancedScanAction.DashConnect, is AdvancedScanAction.PaymentRequest -> HomeScanRoute.Legacy
    }
}
