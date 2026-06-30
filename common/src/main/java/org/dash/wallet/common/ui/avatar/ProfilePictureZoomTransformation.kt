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
        // A malformed/empty zoom rect (right<=left or bottom<=top) would otherwise produce a 1px
        // sliver, since the cropWidth/cropHeight coercion floors at 1. Reject it up front.
        if (zoomedRect.right <= zoomedRect.left || zoomedRect.bottom <= zoomedRect.top) {
            return input
        }
        val x = Math.round(zoomedRect.left * input.width).coerceIn(0, input.width - 1)
        val y = Math.round(zoomedRect.top * input.height).coerceIn(0, input.height - 1)
        val cropWidth = Math.round(input.width * (zoomedRect.right - zoomedRect.left))
            .coerceIn(1, input.width - x)
        val cropHeight = Math.round(input.height * (zoomedRect.bottom - zoomedRect.top))
            .coerceIn(1, input.height - y)
        if (cropWidth <= 0 || cropHeight <= 0) return input
        val zoomX = TARGET / cropWidth
        val zoomY = TARGET / cropHeight
        val matrix = Matrix().apply { setScale(zoomX, zoomY) }
        return Bitmap.createBitmap(input, x, y, cropWidth, cropHeight, matrix, true)
    }

    private companion object {
        const val TARGET = 300f
    }
}