/*
 * Copyright 2020 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import com.bumptech.glide.load.MultiTransformation
import com.bumptech.glide.load.Transformation
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.CircleCrop
import com.bumptech.glide.util.Util
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class ProfilePictureTransformation(private val zoomedRect: RectF) : BitmapTransformation() {

    companion object {
        private val log = LoggerFactory.getLogger(ProfilePictureTransformation::class.java)
        private val ID = ProfilePictureTransformation::class.java.canonicalName
        private val ID_BYTES = ID!!.toByteArray(StandardCharsets.UTF_8)
        private const val TARGET_WIDTH = 300f
        private const val TARGET_HEIGHT = 300f
        fun create(profilePicUrl: String?): Transformation<Bitmap> {
            val uri = Uri.parse(profilePicUrl)
            val zoomedRect = extractZoomedRect(uri)
            return if (zoomedRect != null) {
                create(zoomedRect)
            } else {
                CircleCrop()
            }
        }

        fun extractZoomedRect(profilePicUri: Uri?): RectF? {
            val zoomedRectParam = profilePicUri?.getQueryParameter("dashpay-profile-pic-zoom")
            zoomedRectParam?.also {
                val zoomedRectStr = it.split(",")
                if (zoomedRectStr.size == 4) {
                    return RectF(
                            zoomedRectStr[0].toFloat(), zoomedRectStr[1].toFloat(),
                            zoomedRectStr[2].toFloat(), zoomedRectStr[3].toFloat()
                    )
                }
            }
            return null
        }

        fun create(zoomedRect: RectF): MultiTransformation<Bitmap> {
            return MultiTransformation(ProfilePictureTransformation(zoomedRect), CircleCrop())
        }
    }

    override fun transform(pool: BitmapPool, originalBitmap: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val resultWidth = Math.round(originalBitmap.width * (zoomedRect.right - zoomedRect.left))
        val resultHeight = Math.round(originalBitmap.height * (zoomedRect.bottom - zoomedRect.top))
        val zoomX = TARGET_WIDTH / resultWidth
        val zoomY = TARGET_HEIGHT / resultHeight
        log.info("zoomX: {}, zoomY: {}", zoomX, zoomY)
        val matrix = Matrix()
        matrix.setScale(zoomX, zoomY)
        val x = Math.round(zoomedRect.left * originalBitmap.width)
        val y = Math.round(zoomedRect.top * originalBitmap.height)
        val resultBitmap = Bitmap.createBitmap(originalBitmap, x, y, resultWidth, resultHeight, matrix, true)
        log.info("originalBitmap: {}x{}, resultBitmap: {}x{}, transform: [{}, {}]",
                originalBitmap.width, originalBitmap.height,
                resultBitmap.width, resultBitmap.height,
                x, y)
        return resultBitmap
    }

    override fun equals(other: Any?): Boolean {
        if (other is ProfilePictureTransformation) {
            return other.zoomedRect == zoomedRect
        }
        return false
    }

    override fun hashCode(): Int {
        return Util.hashCode(ID.hashCode(), zoomedRect.hashCode())
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID_BYTES)
        messageDigest.update(ByteBuffer.allocate(4).putFloat(zoomedRect.left).array())
        messageDigest.update(ByteBuffer.allocate(4).putFloat(zoomedRect.top).array())
        messageDigest.update(ByteBuffer.allocate(4).putFloat(zoomedRect.right).array())
        messageDigest.update(ByteBuffer.allocate(4).putFloat(zoomedRect.bottom).array())
    }
}