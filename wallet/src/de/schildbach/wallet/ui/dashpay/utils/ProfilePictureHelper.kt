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

package de.schildbach.wallet.ui.dashpay.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.util.Util
import com.google.common.base.Stopwatch
import org.bitcoinj.core.Sha256Hash
import org.dashevo.dpp.toBase64
import org.slf4j.LoggerFactory
import java.io.File
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*


class ProfilePictureHelper {

    companion object {

        private val log = LoggerFactory.getLogger(ProfilePictureHelper::class.java)

        private const val ZOOM_PARAM_KEY = "dashpay-profile-pic-zoom"

        fun avatarHashAndFingerprint(context: Context, pictureUrl: Uri, profileAvatarHash: ByteArray?, listener: OnResourceReadyListener? = null) {
            val watch = Stopwatch.createStarted()
            Glide.with(context)
                    .asFile()
                    .load(pictureUrl)
                    .into(object : CustomTarget<File>() {

                        override fun onResourceReady(resource: File, transition: Transition<in File>?) {
                            val serverAvatarHash = Sha256Hash.of(resource)
                            watch.stop()
                            log.debug("server avatarHash: '{}', took {}", serverAvatarHash.bytes.toBase64(), watch)
                            if (profileAvatarHash != null && !(profileAvatarHash contentEquals serverAvatarHash.bytes)) {
                                val profileAvatarHashBase58 = Sha256Hash.wrap(profileAvatarHash).toStringBase58()
                                log.info("server avatarHash ({}) doesn't match the profile avatarHash ({})", serverAvatarHash.toStringBase58(), profileAvatarHashBase58)
                            }
                            val avatarFingerprint = CocoaImageDHash.of(BitmapFactory.decodeFile(resource.path))
                            listener?.apply {
                                onResourceReady(serverAvatarHash, avatarFingerprint)
                            }
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {
                            listener?.apply {
                                onResourceReady(null, null)
                            }
                        }
                    })
        }

        fun setPicZoomParameter(uri: Uri, newValue: String): Uri {
            return setUriParameter(uri, ZOOM_PARAM_KEY, newValue)
        }

        private fun setUriParameter(uri: Uri, key: String, newValue: String): Uri {
            val newUriBuilder = uri.buildUpon()
            if (uri.getQueryParameter(key) == null) {
                newUriBuilder.appendQueryParameter(key, newValue)
            } else {
                newUriBuilder.clearQuery()
                for (param in uri.queryParameterNames) {
                    newUriBuilder.appendQueryParameter(param,
                            if (param == key) newValue else uri.getQueryParameter(param))
                }
            }
            return newUriBuilder.build()
        }

        fun extractZoomedRect(profilePicUri: Uri?): RectF? {
            val zoomedRectParam = profilePicUri?.getQueryParameter(ZOOM_PARAM_KEY)
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

        fun removePicZoomParameter(url: String): Uri {
            return removePicZoomParameter(Uri.parse(url))
        }

        fun removePicZoomParameter(uri: Uri): Uri {
            return removeParameter(uri, ZOOM_PARAM_KEY)
        }

        @Suppress("SameParameterValue")
        private fun removeParameter(uri: Uri, key: String): Uri {
            val newUriBuilder = uri.buildUpon().clearQuery()
            for (param in uri.queryParameterNames) {
                newUriBuilder.appendQueryParameter(param,
                        if (param == key) {
                            continue
                        } else {
                            uri.getQueryParameter(param)
                        })
            }
            return newUriBuilder.build()
        }

        /**
         * Converts BigInteger to an 8 byte array removing the sign bit
         * more info: https://github.com/icon-project/icon-sdk-java/issues/8
         *
         * this handles cases where value is 9 or more bytes by using the
         * least significant 8 bytes.  It handles cases were value is less
         * than 8 bytes by padding the byte array with leading zeros.
         */
        fun toByteArray(value: BigInteger): ByteArray {
            val array = value.toByteArray()
            return if (array.size >= 8) {
                val srcPos = array.size - 8
                Arrays.copyOfRange(array, srcPos, array.size)
            } else {
                val bytes = ByteArray(8)
                System.arraycopy(array, 0, bytes, 8 - array.size, array.size)
                bytes
            }
        }
    }

    class Loader : RequestListener<Drawable> {
        override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?,
                                  isFirstResource: Boolean): Boolean {
            return false
        }

        override fun onResourceReady(resource: Drawable?, model: Any?, target: Target<Drawable>?,
                                     dataSource: DataSource?, isFirstResource: Boolean): Boolean {
            return false
        }
    }

    class HashAndZoomSignature(private val hash: ByteArray?, private val zoomedRect: RectF?) : Key {

        private val ID = HashAndZoomSignature::class.java.canonicalName
        private val ID_BYTES = ID!!.toByteArray(StandardCharsets.UTF_8)

        override fun equals(other: Any?): Boolean {
            return (other is HashAndZoomSignature) && hashEquals(other) && zoomEquals(other)
        }

        private fun hashEquals(other: HashAndZoomSignature): Boolean {
            return if (hash != null) {
                return hash contentEquals other.hash
            } else {
                other.hash == null
            }
        }

        private fun zoomEquals(other: HashAndZoomSignature): Boolean {
            return if (zoomedRect != null) {
                return zoomedRect == other.zoomedRect
            } else {
                other.zoomedRect == null
            }
        }

        override fun hashCode(): Int {
            val hashHashCode = hash?.hashCode() ?: 0
            val zoomedRectHashCode = zoomedRect?.hashCode() ?: 0
            return Util.hashCode(ID.hashCode(), Util.hashCode(hashHashCode, zoomedRectHashCode))
        }

        override fun updateDiskCacheKey(messageDigest: MessageDigest) {
            messageDigest.update(ID_BYTES)
            hash?.apply {
                messageDigest.update(this)
            }
            zoomedRect?.apply {
                messageDigest.update(ByteBuffer.allocate(4).putFloat(left).array())
                messageDigest.update(ByteBuffer.allocate(4).putFloat(top).array())
                messageDigest.update(ByteBuffer.allocate(4).putFloat(right).array())
                messageDigest.update(ByteBuffer.allocate(4).putFloat(bottom).array())
            }
        }
    }

    interface OnResourceReadyListener {
        fun onResourceReady(avatarHash: Sha256Hash?, avatarFingerprint: BigInteger?)
    }
}