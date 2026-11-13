/*
 * Copyright (c) 2026. Dash Core Group.
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


package org.dash.wallet.features.exploredash

import com.google.zxing.BarcodeFormat
import org.dash.wallet.features.exploredash.data.dashspend.ctx.model.GiftCardResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GiftCardResponseBarcodeFormatTest {
    private fun response(barcodeType: String?) =
        GiftCardResponse(id = "gift-card", status = "fulfilled", barcodeType = barcodeType)

    @Test
    fun mapsEveryBarcodeTypeTheApiReturnsToItsZxingFormat() {
        val expected = mapOf(
            "code39" to BarcodeFormat.CODE_39,
            "code93" to BarcodeFormat.CODE_93,
            "code128" to BarcodeFormat.CODE_128,
            "pdf417" to BarcodeFormat.PDF_417,
            "aztec" to BarcodeFormat.AZTEC,
            "qr" to BarcodeFormat.QR_CODE
        )

        expected.forEach { (barcodeType, format) ->
            assertEquals(barcodeType, format, response(barcodeType).barcodeFormat())
        }
    }

    @Test
    fun leavesTheFormatUnsetForAMissingOrUnknownBarcodeType() {
        listOf(null, "", "ean13", "QR", "code-128").forEach { barcodeType ->
            assertNull("barcodeType=$barcodeType", response(barcodeType).barcodeFormat())
        }
    }
}
