/*
 * Copyright 2024 Dash Core Group.
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.dash.wallet.integrations.maya.payments.parsers

import org.bitcoinj.core.Coin
import org.bitcoinj.core.NetworkParameters
import org.bitcoinj.script.ScriptBuilder
import org.dash.wallet.common.data.PaymentIntent
import org.dash.wallet.common.payments.parsers.PaymentIntentParser

abstract class MayaPaymentIntentParser(
    currency: String,
    uriPrefix: String,
    val asset: String,
    val shortAsset: String? = null,
    params: NetworkParameters?
) :
    PaymentIntentParser(
        currency,
        uriPrefix,
        params
    ) {
    fun createPaymentIntent(inputStr: String): PaymentIntent {
        val destinationAddress = if (inputStr.lowercase().startsWith(uriPrefix.lowercase() + ":")) {
            // val destinationAddress = if (inputStr.lowercase().startsWith(uriPrefix.lowercase())) {
            inputStr.substring(uriPrefix.length + 1)
        } else {
            inputStr
        }
        val metadataAsset = shortAsset ?: asset
        val metadata = "=:$metadataAsset:$destinationAddress"
        if (metadata.length > 80) {
            throw IllegalArgumentException("metadata is too long ($metadata[${metadata.length}] > 80 bytes).  Is there a shorter asset code?")
        }
        val outputScript = ScriptBuilder.createOpReturnScript(metadata.toByteArray())
        return PaymentIntent(
            null, "maya DASH pool", null,
            arrayOf(PaymentIntent.Output(Coin.ZERO, outputScript)),
            "maya swap to $currency", null, null, null, null,
            null, null, null
        )
    }
}
