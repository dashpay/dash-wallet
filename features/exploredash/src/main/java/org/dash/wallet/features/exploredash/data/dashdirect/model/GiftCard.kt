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

package org.dash.wallet.features.exploredash.data.dashdirect.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.zxing.BarcodeFormat
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.util.Constants

@Entity(
    tableName = "gift_cards",
    indices = [
        Index(value = ["transactionId"], unique = true)
    ]
)
data class GiftCard(
    @PrimaryKey var id: String = "",
    var service: String = "",
    var merchantName: String = "",
    var transactionId: Sha256Hash = Sha256Hash.ZERO_HASH,
    var price: Long = 0,
    var currency: String = Constants.USD_CURRENCY,
    var discount: Double = 0.0,
    var number: String? = null,
    var pin: String? = null,
    var barcodeValue: String? = null,
    var barcodeFormat: BarcodeFormat? = null,
    var currentBalanceUrl: String? = null,
    var note: String? = null
)