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
package org.dash.wallet.common.ui.avatar

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import coil.size.Size
import coil.transform.Transformation

/**
 * Coil transformation that crops a bitmap to the normalized zoom rect encoded in a
 * dashpay profile picture URL, then scales the result to 300x300. Mirrors the Glide
 * transform in [ProfilePictureTransformation] so cached avatars look identical.
 */
class ProfilePictureZoomTransformation(private val zoomedRect: RectF) : Transformation {

    override val cacheKey: String =
        "ProfilePictureZoomTransformation(${zoomedRect.left},${zoomedRect.top}," +
            "${zoomedRect.right},${zoomedRect.bottom})"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val cropWidth = Math.round(input.width * (zoomedRect.right - zoomedRect.left))
        val cropHeight = Math.round(input.height * (zoomedRect.bottom - zoomedRect.top))
        if (cropWidth <= 0 || cropHeight <= 0) return input
        val zoomX = TARGET / cropWidth
        val zoomY = TARGET / cropHeight
        val matrix = Matrix().apply { setScale(zoomX, zoomY) }
        val x = Math.round(zoomedRect.left * input.width)
        val y = Math.round(zoomedRect.top * input.height)
        return Bitmap.createBitmap(input, x, y, cropWidth, cropHeight, matrix, true)
    }

    private companion object {
        const val TARGET = 300f
    }
}