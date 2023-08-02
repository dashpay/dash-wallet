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

import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import org.dash.wallet.common.ui.avatar.UserAvatarPlaceholderDrawable.Companion.getDrawable

class ProfilePictureDisplay {

    companion object {

        @JvmStatic
        fun display(avatarView: ImageView, avatarUrlStr: String, avatarHash: ByteArray?, username: String) {
            display(avatarView, avatarUrlStr, avatarHash, username, false, null)
        }

        @JvmStatic
        fun display(
            avatarView: ImageView,
            avatarUrlStr: String,
            avatarHash: ByteArray?,
            username: String,
            disableTransition: Boolean,
            listener: OnResourceReadyListener?
        ) {
            val fontSize = calcFontSize(avatarView)
            if (avatarUrlStr.isNotEmpty()) {
                val defaultAvatar: Drawable? = getDrawable(avatarView.context, username[0], fontSize)
                val avatarUrl = Uri.parse(avatarUrlStr)
                val zoomRectF = ProfilePictureHelper.extractZoomedRect(avatarUrl)
                val baseAvatarUrl = ProfilePictureHelper.removePicZoomParameter(avatarUrl)
                val context = avatarView.context.applicationContext
                Glide.with(context)
                    .load(baseAvatarUrl)
                    .listener(object : RequestListener<Drawable> {
                        override fun onLoadFailed(
                            e: GlideException?,
                            model: Any?,
                            target: Target<Drawable>?,
                            isFirstResource: Boolean
                        ): Boolean {
                            listener?.onResourceReady(null)
                            return false
                        }

                        override fun onResourceReady(
                            resource: Drawable?,
                            model: Any?,
                            target: Target<Drawable>?,
                            dataSource: DataSource?,
                            isFirstResource: Boolean
                        ): Boolean {
                            ProfilePictureHelper.avatarHashAndFingerprint(
                                context,
                                baseAvatarUrl,
                                avatarHash
                            )
                            listener?.onResourceReady(resource)
                            return false
                        }
                    })
                    .signature(ObjectKey(zoomRectF.hashCode()))
                    .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                    .transform(ProfilePictureTransformation.create(avatarUrlStr))
                    .placeholder(defaultAvatar)
                    .apply {
                        takeIf { !disableTransition }
                            ?.apply(transition(DrawableTransitionOptions.withCrossFade()))
                    }.into(avatarView)
            } else {
                displayDefault(avatarView, username, fontSize, listener)
            }
        }

        @JvmStatic
        fun displayDefault(avatarView: ImageView, username: String, listener: OnResourceReadyListener? = null) {
            displayDefault(avatarView, username, calcFontSize(avatarView), listener)
        }

        private fun displayDefault(
            avatarView: ImageView,
            username: String,
            fontSize: Int,
            listener: OnResourceReadyListener?
        ) {
            val defaultAvatar: Drawable? = getDrawable(avatarView.context, username[0], fontSize)
            avatarView.setImageDrawable(defaultAvatar)
            listener?.onResourceReady(defaultAvatar)
        }

        const val FONT_SIZE_RATIO: Float = 30f / 64f

        private fun calcFontSize(avatarView: ImageView): Int {
            return (avatarView.layoutParams.width * FONT_SIZE_RATIO).toInt()
        }

        fun removePicZoomParameter(url: String): Uri {
            return removeParameter(Uri.parse(url), "dashpay-profile-pic-zoom")
        }

        @Suppress("SameParameterValue")
        private fun removeParameter(uri: Uri, key: String): Uri {
            val newUriBuilder = uri.buildUpon().clearQuery()
            for (param in uri.queryParameterNames) {
                newUriBuilder.appendQueryParameter(
                    param,
                    if (param == key) {
                        continue
                    } else {
                        uri.getQueryParameter(param)
                    }
                )
            }
            return newUriBuilder.build()
        }
    }

    interface OnResourceReadyListener {
        fun onResourceReady(resource: Drawable?)
    }
}
