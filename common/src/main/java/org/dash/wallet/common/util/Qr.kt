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

package org.dash.wallet.common.util

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.BitmapDrawable
import android.util.Size
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.dash.wallet.common.R
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.*
import java.util.zip.GZIPInputStream

object Qr {
    private val log = LoggerFactory.getLogger(Qr::class.java)

    fun scanQRImage(bitmap: Bitmap): String? {
        return scanBarcode(bitmap, BarcodeFormat.QR_CODE)?.first
    }

    /**
     * Scan barcode directly from bitmap
     * https://stackoverflow.com/a/32135865/795721
     */
    fun scanBarcode(bitmap: Bitmap, knownFormat: BarcodeFormat? = null): Pair<String, BarcodeFormat>? {
        val intArray = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source: LuminanceSource = RGBLuminanceSource(bitmap.width, bitmap.height, intArray)
        val reader = MultiFormatReader()
        val hints = if (knownFormat == null) {
            mapOf()
        } else {
            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(knownFormat))
        }

        return try {
            val result = reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            log.info("successfully decoded barcode from bitmap")
            Pair(result.text, result.barcodeFormat)
        } catch (e: ReaderException) {
            try {
                // Invert and check for a code
                val invertedSource = source.invert()
                val invertedBitmap = BinaryBitmap(HybridBinarizer(invertedSource))
                val invertedResult = reader.decode(invertedBitmap, hints)
                log.info("successfully decoded inverted barcode from bitmap")
                Pair(invertedResult.text, invertedResult.barcodeFormat)
            } catch (ex: ReaderException) {
                log.warn("error decoding barcode", e)
                null
            }
        }
    }

    fun qrBitmap(content: String): Bitmap? {
        val hints = mapOf(
            EncodeHintType.MARGIN to 0,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H
        )
        return bitmap(content, BarcodeFormat.QR_CODE, hints = hints)
    }

    fun bitmap(
        content: String,
        format: BarcodeFormat,
        size: Size = Size(0, 0),
        hints: Map<EncodeHintType, Any?> = mapOf()
    ): Bitmap? {
        return try {
            val result = MultiFormatWriter().encode(content, format, size.width, size.height, hints)
            val width = result.width
            val height = result.height
            val pixels = ByteArray(width * height)

            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = (if (result[x, y]) -1 else 1).toByte()
                }
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
            bitmap
        } catch (x: WriterException) {
            log.error("problem creating barcode", x)
            null
        }
    }

    fun themeAwareDrawable(content: String, resources: Resources): BitmapDrawable {
        val bitmap = qrBitmap(content)
        val qrCodeBitmap = BitmapDrawable(resources, bitmap)
        qrCodeBitmap.isFilterBitmap = false
        qrCodeBitmap.colorFilter = PorterDuffColorFilter(
            resources.getColor(R.color.content_primary, null),
            PorterDuff.Mode.SRC_IN
        )

        return qrCodeBitmap
    }

    @Throws(IOException::class)
    fun decodeDecompressBinary(content: String): ByteArray? {
        val useCompression = content[0] == 'Z'
        val bytes = Base43.decode(content.substring(1))
        var inputStream: InputStream = ByteArrayInputStream(bytes)

        if (useCompression) {
            inputStream = GZIPInputStream(inputStream)
        }

        ByteArrayOutputStream().use { outputStream ->
            val buf = ByteArray(4096)
            var read: Int

            while (-1 != inputStream.read(buf).also { read = it }) {
                outputStream.write(buf, 0, read)
            }

            return outputStream.toByteArray()
        }
    }
}
