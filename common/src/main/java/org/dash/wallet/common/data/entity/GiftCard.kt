/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.common.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.zxing.BarcodeFormat
import org.dash.wallet.common.data.TxId

@Entity(tableName = "gift_cards", primaryKeys = ["txId", "index"])
data class GiftCard(
    var txId: TxId,
    var merchantName: String = "",
    var price: Double = 0.0,
    var number: String? = null,
    var pin: String? = null,
    var barcodeValue: String? = null,
    var barcodeFormat: BarcodeFormat? = null,
    var merchantUrl: String? = null, // holds claimLink or redeemUrl
    var note: String? = null, // holds order number
    var index: Int = 0,
    var redeemUrlChallenge: String? = null
) {
    companion object {
        /**
         * Builds a GiftCard from a hex transaction id.
         */
        fun fromHex(
            txId: String,
            merchantName: String = "",
            price: Double = 0.0,
            number: String? = null,
            pin: String? = null,
            barcodeValue: String? = null,
            barcodeFormat: BarcodeFormat? = null,
            merchantUrl: String? = null,
            note: String? = null,
            index: Int = 0,
            redeemUrlChallenge: String? = null
        ) = GiftCard(
            TxId.wrap(txId), merchantName, price, number, pin, barcodeValue,
            barcodeFormat, merchantUrl, note, index, redeemUrlChallenge
        )
    }

    /** The transaction id as a hex string; dashj-free accessor. */
    val txIdHex: String get() = txId.toString()

    /**
     * Neutral (dashj-free) variant of [copy]: duplicates the card (txId always preserved)
     * with the given field overrides, for modules that must not depend on dashj.
     */
    fun copyCard(
        merchantName: String = this.merchantName,
        price: Double = this.price,
        number: String? = this.number,
        pin: String? = this.pin,
        barcodeValue: String? = this.barcodeValue,
        barcodeFormat: BarcodeFormat? = this.barcodeFormat,
        merchantUrl: String? = this.merchantUrl,
        note: String? = this.note,
        index: Int = this.index,
        redeemUrlChallenge: String? = this.redeemUrlChallenge
    ) = copy(
        txId = txId,
        merchantName = merchantName,
        price = price,
        number = number,
        pin = pin,
        barcodeValue = barcodeValue,
        barcodeFormat = barcodeFormat,
        merchantUrl = merchantUrl,
        note = note,
        index = index,
        redeemUrlChallenge = redeemUrlChallenge
    )
}
