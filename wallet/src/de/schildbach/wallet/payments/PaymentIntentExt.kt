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

/**
 * The invoice identity a payment record has always been keyed on: hex of the SHA-256 of the
 * serialized BIP70 PaymentRequest. Changes whenever the merchant re-serves the request (a new
 * time, expiry or signature), so it is matched together with [orderIdentity].
 */
val PaymentIntent.paymentRequestId: String?
    get() = paymentRequestHash?.let { de.schildbach.wallet.Constants.HEX.encode(it) }

/**
 * A stable identity for the ORDER a BIP70 request is for, which a refreshed request for the same
 * order keeps.
 *
 * Primarily the merchant's own order identifier: BIP70 `PaymentDetails.merchant_data`
 * ([PaymentIntent.payeeData]), keyed together with the request's payment_url, its outputs (script
 * and amount, in a canonical order) and, when the request was signed and verified, the merchant
 * identity that signed it. Two orders served from one endpoint for the same amount differ in
 * their merchant data, so they are told apart.
 *
 * When the request carries no merchant data, the url-and-outputs heuristic
 * ([legacyOrderIdentity]): the payment_url (where its Payment is posted, which names the order),
 * the outputs and the verified merchant. The request's time, expiry, memo and signature are left
 * out on purpose: those are what a refresh changes.
 *
 * Null when there is nothing stable to key on (no payment_url, or no outputs).
 */
fun PaymentIntent.orderIdentity(): String? {
    val merchantData = payeeData?.takeIf { it.isNotEmpty() } ?: return legacyOrderIdentity()
    return orderHash("bip70-order-v2") { field ->
        field(de.schildbach.wallet.Constants.HEX.encode(merchantData))
    }
}

/**
 * The url-and-outputs heuristic identity of this request's order, ignoring its merchant data: what
 * [orderIdentity] is when there is none, and what records were stamped with before merchant data
 * was keyed on. Matched against stored records alongside [orderIdentity], so such a record still
 * blocks a refresh of its order that now carries merchant data.
 */
fun PaymentIntent.legacyOrderIdentity(): String? = orderHash("bip70-order-v1") { }

private fun PaymentIntent.orderHash(version: String, extra: ((String) -> Unit) -> Unit): String? {
    val url = paymentUrl?.takeIf { it.isNotEmpty() } ?: return null
    val outs = outputs?.takeIf { it.isNotEmpty() } ?: return null
    val canonical = outs
        .map { de.schildbach.wallet.Constants.HEX.encode(it.scriptData ?: ByteArray(0)) to it.amount.value }
        .sortedWith(compareBy({ it.first }, { it.second }))
    val bytes = java.io.ByteArrayOutputStream()
    java.io.DataOutputStream(bytes).use { out ->
        // length-prefixed fields, so no two different orders can serialize alike
        fun field(value: String) {
            val raw = value.toByteArray(Charsets.UTF_8)
            out.writeInt(raw.size)
            out.write(raw)
        }
        field(version)
        field(url)
        field(payeeName?.takeIf { payeeVerifiedBy != null } ?: "")
        out.writeInt(canonical.size)
        canonical.forEach { (script, amount) ->
            field(script)
            out.writeLong(amount)
        }
        extra(::field)
    }
    return org.bitcoinj.core.Sha256Hash.of(bytes.toByteArray()).toString()
}
