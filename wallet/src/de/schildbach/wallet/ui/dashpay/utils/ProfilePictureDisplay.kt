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
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.ProfilePictureTransformation
import de.schildbach.wallet.ui.UserAvatarPlaceholderDrawable.Companion.getDrawable

class ProfilePictureDisplay {

    companion object {

        @JvmStatic
        fun display(avatarView: ImageView, dashPayProfile: DashPayProfile?, hideIfProfileNull: Boolean = false) {
            if (dashPayProfile != null) {
                avatarView.visibility = View.VISIBLE
                display(avatarView, dashPayProfile.avatarUrl, dashPayProfile.avatarHash, dashPayProfile.username)
            } else if (hideIfProfileNull) {
                avatarView.visibility = View.GONE
            }
        }

        @JvmStatic
        fun display(avatarView: ImageView, avatarUrlStr: String, avatarHash: ByteArray?, username: String) {
            val defaultAvatar: Drawable? = getDrawable(avatarView.context, username[0])
            if (avatarUrlStr.isNotEmpty()) {
                val avatarUrl = Uri.parse(avatarUrlStr)
                val zoomRectF = ProfilePictureHelper.extractZoomedRect(avatarUrl)
                val baseAvatarUrl = ProfilePictureHelper.removePicZoomParameter(avatarUrl)
                val context = avatarView.context.applicationContext
                Glide.with(context)
                        .load(baseAvatarUrl)
                        .listener(object : RequestListener<Drawable> {
                            override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>?, isFirstResource: Boolean): Boolean {
                                return false
                            }

                            override fun onResourceReady(resource: Drawable?, model: Any?, target: Target<Drawable>?,
                                                         dataSource: DataSource?, isFirstResource: Boolean): Boolean {
                                ProfilePictureHelper.avatarHash(context, baseAvatarUrl, avatarHash)
                                return false
                            }
                        })
                        .signature(ProfilePictureHelper.HashAndZoomSignature(avatarHash, zoomRectF))
                        .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                        .transform(ProfilePictureTransformation.create(avatarUrlStr))
                        .placeholder(defaultAvatar)
                        .transition(withCrossFade())
                        .into(avatarView)
            } else {
                displayDefault(avatarView, username)
            }
        }

        fun displayDefault(avatarView: ImageView, username: String) {
            val defaultAvatar: Drawable? = getDrawable(avatarView.context, username[0])
            avatarView.setImageDrawable(defaultAvatar)
        }
    }
}