package de.schildbach.wallet.ui.dashpay.utils

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import com.bumptech.glide.Glide
import de.schildbach.wallet.data.DashPayProfile
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
                Glide.with(avatarView.context).load(avatarUrl).circleCrop()
                        .placeholder(defaultAvatar).into(avatarView)
            } else {
                avatarView.background = defaultAvatar
            }
        }
    }
}