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
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import de.schildbach.wallet.data.DashPayProfile
import de.schildbach.wallet.ui.ProfilePictureTransformation
import de.schildbach.wallet.ui.UserAvatarPlaceholderDrawable.Companion.getDrawable
import org.bitcoinj.core.Sha256Hash

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
        fun display(avatarView: ImageView, avatarUrl: String, avatarHash: ByteArray?, username: String) {
            val defaultAvatar: Drawable? = getDrawable(avatarView.context, username[0])
            if (avatarUrl.isNotEmpty()) {
                Glide.with(avatarView.context.applicationContext)
                        .load(removePicZoomParameter(avatarUrl))
                        .signature(ObjectKey(if (avatarHash != null) Sha256Hash.wrap(avatarHash).toStringBase58() else ""))
                        .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                        .transform(ProfilePictureTransformation.create(avatarUrl))
                        .placeholder(defaultAvatar)
                        .transition(withCrossFade())
                        .into(avatarView)
            } else {
                displayDefault(avatarView, username)
            }
        }

        @JvmStatic
        fun display(avatarView: ImageView, avatarLocalUri: Uri, lastModified: Long, username: String) {
            val defaultAvatar: Drawable? = getDrawable(avatarView.context, username[0])
            if (avatarLocalUri.encodedPath!!.isNotEmpty()) {
                Glide.with(avatarView.context.applicationContext)
                        .load(avatarLocalUri)
                        .signature(ObjectKey(lastModified))
                        .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                        .placeholder(defaultAvatar)
                        .transition(withCrossFade())
                        .circleCrop()
                        .into(avatarView)
            } else {
                displayDefault(avatarView, username)
            }
        }

        fun displayDefault(avatarView: ImageView, username: String) {
            val defaultAvatar: Drawable? = getDrawable(avatarView.context, username[0])
            avatarView.setImageDrawable(defaultAvatar)
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
}