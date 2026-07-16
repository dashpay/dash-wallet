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

package org.dash.wallet.common.payments.parsers

import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.script.ScriptBuilder
import org.bitcoinj.script.ScriptPattern
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.money.toCoin

// ---------------------------------------------------------------------------------------------
// Neutral (dashj-free) construction and inspection helpers for PaymentIntent, for feature and
// integration modules that must not import bitcoinj Script/Address types. They delegate to dashj
// internally so the produced intents/outputs are identical to hand-built ones.
// ---------------------------------------------------------------------------------------------

object PaymentIntents {

    /**
     * Payment intent with a single zero-value OP_RETURN output carrying [memoData]
     * (e.g. Maya swap memos). Mirrors `PaymentIntent.Output(Coin.ZERO,
     * ScriptBuilder.createOpReturnScript(memoData))`.
     */
    fun forOpReturnMemo(payeeName: String?, memoData: ByteArray, memo: String?): PaymentIntent {
        val outputScript = ScriptBuilder.createOpReturnScript(memoData)
        return PaymentIntent(
            null, payeeName, null,
            arrayOf(PaymentIntent.Output(Coin.ZERO, outputScript)),
            memo, null, null, null, null,
            null, null, null
        )
    }
}

/**
 * Copy of this intent with a pay-to-address output of [amount] to base58 [address] appended
 * (the network is inferred from the address version byte, mirroring `Address.fromBase58(null, address)`).
 */
fun PaymentIntent.withOutputAdded(amount: Dash, address: String): PaymentIntent {
    val outputList = (outputs ?: emptyArray()).toMutableList()
    outputList.add(
        PaymentIntent.Output(amount.toCoin(), ScriptBuilder.createOutputScript(Address.fromBase58(null, address)))
    )
    return PaymentIntent(
        standard,
        payeeName,
        payeeVerifiedBy,
        outputList.toTypedArray(),
        memo,
        paymentUrl,
        payeeData,
        paymentRequestUrl,
        paymentRequestHash,
        null,
        null,
        null
    )
}

/**
 * UTF-8 payload of this output's OP_RETURN script (mirrors reading `script.chunks[1].data`),
 * or null if the output is not an OP_RETURN carrying data.
 */
val PaymentIntent.Output.opReturnMessage: String?
    get() = if (ScriptPattern.isOpReturn(script) && script.chunks.size > 1) {
        script.chunks[1].data?.let { String(it) }
    } else {
        null
    }
