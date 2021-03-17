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

import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.ProfilePictureTransformation
import de.schildbach.wallet.ui.UserAvatarPlaceholderDrawable.Companion.getDrawable

class ProfilePictureDisplay {

    companion object {

        @JvmStatic
        fun display(avatarView: ImageView, dashPayProfile: DashPayProfile?, hideIfProfileNull: Boolean = false) {
            display(avatarView, dashPayProfile, hideIfProfileNull, false, null)
        }

        @JvmStatic
        fun display(avatarView: ImageView, dashPayProfile: DashPayProfile?, hideIfProfileNull: Boolean = false, disableTransition: Boolean, listener: OnResourceReadyListener?) {
            if (dashPayProfile != null) {
                avatarView.visibility = View.VISIBLE
                display(avatarView, dashPayProfile.avatarUrl, dashPayProfile.avatarHash, dashPayProfile.username, disableTransition, listener)
            } else if (hideIfProfileNull) {
                avatarView.visibility = View.GONE
            }
        }

        @JvmStatic
        fun display(avatarView: ImageView, avatarUrlStr: String, avatarHash: ByteArray?, username: String) {
            display(avatarView, avatarUrlStr, avatarHash, username, false, null)
        }

        @JvmStatic
        fun display(avatarView: ImageView, avatarUrlStr: String, avatarHash: ByteArray?, username: String,
                    disableTransition: Boolean, listener: OnResourceReadyListener?) {
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
                            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
                                listener?.onResourceReady(null)
                                return false
                            }

                            override fun onResourceReady(resource: Drawable?, model: Any?, target: Target<Drawable>?,
                                                         dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                                ProfilePictureHelper.avatarHashAndFingerprint(context, baseAvatarUrl, avatarHash)
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
                                    ?.apply(transition(withCrossFade()))
                        }.into(avatarView)
            } else {
                displayDefault(avatarView, username, fontSize, listener)
            }
        }

        @JvmStatic
        fun displayDefault(avatarView: ImageView, username: String, listener: OnResourceReadyListener? = null) {
            displayDefault(avatarView, username, calcFontSize(avatarView), listener)
        }

        private fun displayDefault(avatarView: ImageView, username: String, fontSize: Int, listener: OnResourceReadyListener?) {
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
                newUriBuilder.appendQueryParameter(param,
                        if (param == key) {
                            continue
                        } else {
                            uri.getQueryParameter(param)
                        })
            }
            return newUriBuilder.build()
        }
    }

    interface OnResourceReadyListener {
        fun onResourceReady(resource: Drawable?)
    }
}