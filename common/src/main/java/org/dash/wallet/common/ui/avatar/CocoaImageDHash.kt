/*
 * Copyright 2020 Dash Core Group.
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
package org.dash.wallet.common.ui.avatar

import android.graphics.Bitmap
import androidx.annotation.ColorInt
import java.math.BigInteger


// base on:
// https://github.com/ameingast/cocoaimagehashing/blob/5830cb0dcd4e4ce08f348991d78a4445fb44a93f/CocoaImageHashing/OSFastGraphics.m#L538
// related stuff:
// https://benhoyt.com/writings/duplicate-image-detection/
// https://www.hackerfactor.com/blog/index.php?/archives/529-Kind-of-Like-That.html
// https://github.com/tistaharahap/android-dhash/blob/master/src/com/bango/imagereco/Reco.java
// https://gist.github.com/jeancarlozapata/2530bb9f1108f3e58594f90fdb47011b

object CocoaImageDHash {

    private const val HASH_SIZE = 8
    private const val IMAGE_WIDTH = HASH_SIZE + 1
    private const val IMAGE_HEIGHT = HASH_SIZE + 1

    /***
     * filter - Whether or not bilinear filtering should be used when scaling the bitmap.
     * If this is true then bilinear filtering will be used when scaling which has better
     * image quality at the cost of worse performance. If this is false then nearest-neighbor
     * scaling is used instead which will have worse image quality but is faster. Recommended
     * default is to set filter to 'true' as the cost of bilinear filtering is typically
     * minimal and the improved image quality is significant.)
     */
    @JvmStatic
    fun of(srcBmp: Bitmap, filter: Boolean = true): BigInteger {
        val resizedBmp = Bitmap.createScaledBitmap(srcBmp, IMAGE_WIDTH, IMAGE_HEIGHT, filter)
        if (resizedBmp != srcBmp) {
            srcBmp.recycle()
        }
        return getHorizontalDifferences(resizedBmp)
    }

    private fun getHorizontalDifferences(bitmap: Bitmap): BigInteger {
        if (bitmap.width != IMAGE_WIDTH || bitmap.height != IMAGE_HEIGHT) {
            throw IllegalArgumentException()
        }
        var diffs = BigInteger.ZERO
        for (y in 0 until bitmap.height - 1) {
            var rowDiffs = BigInteger.ZERO
            for (x in 0 until bitmap.width - 1) {
                val argbLeft = bitmap.getPixel(x, y)
                val greyLeft = greyscale(argbLeft)
                val argbRight = bitmap.getPixel(x + 1, y)
                val greyRight = greyscale(argbRight)
                if (greyLeft > greyRight) {
                    rowDiffs = rowDiffs.setBit(x)
                }
            }
            diffs = diffs.shiftLeft(8).or(rowDiffs)
        }
        return diffs
    }

    @JvmStatic
    fun getDistance(hash1hex: String, hash2hex: String): Int {
        return getDistance(BigInteger(hash1hex, 16), BigInteger(hash2hex, 16))
    }

    @JvmStatic
    fun getDistance(hash1: BigInteger, hash2: BigInteger): Int {
        return hash1.xor(hash2).bitCount()
    }

    private fun greyscale(@ColorInt color: Int): Float {
        val alpha = ColorUtil.alpha(color)
        val red = ColorUtil.red(color) * alpha / 255
        val green = ColorUtil.green(color) * alpha / 255
        val blue = ColorUtil.blue(color) * alpha / 255
        return greyscale(red, green, blue)
    }

    private fun greyscale(red: Int, green: Int, blue: Int): Float {
        return (red + green + blue).toFloat() / 3.0f
    }
}