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

import de.schildbach.wallet.util.toDashjCoin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.core.Transaction
import org.bitcoinj.script.Script
import org.bitcoinj.wallet.SendRequest
import org.dash.wallet.common.data.PaymentIntent

/**
 * The former `PaymentIntent.toSendRequest` (removed from the now dashj-free common class),
 * unchanged: builds a dashj [SendRequest] paying the intent's outputs.
 */
fun PaymentIntent.toSendRequest(params: NetworkParameters): SendRequest {
    val transaction = Transaction(params)
    for (output in outputs!!) {
        transaction.addOutput(output.amount.toDashjCoin(), Script(output.scriptData))
    }
    return SendRequest.forTx(transaction)
}
