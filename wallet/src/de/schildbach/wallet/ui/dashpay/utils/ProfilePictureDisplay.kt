package de.schildbach.wallet.ui.dashpay.utils

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
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
                display(avatarView, dashPayProfile.avatarUrl, dashPayProfile.username)
            } else if (hideIfProfileNull) {
                avatarView.visibility = View.GONE
            }
        }

        @JvmStatic
        fun display(avatarView: ImageView, avatarUrl: String, username: String) {
            val defaultAvatar: Drawable? = getDrawable(avatarView.context, username[0])
            if (avatarUrl.isNotEmpty()) {
                Glide.with(avatarView.context)
                        .load(avatarUrl)
                        .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                        .transform(ProfilePictureTransformation.create(avatarUrl))
                        .placeholder(defaultAvatar)
                        .transition(withCrossFade())
                        .into(avatarView)
            } else {
                avatarView.background = defaultAvatar
            }
        }
    }
}