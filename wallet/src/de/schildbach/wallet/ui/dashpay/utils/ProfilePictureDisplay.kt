package de.schildbach.wallet.ui.dashpay.utils

import android.graphics.drawable.Drawable
import android.text.TextUtils
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
                val defaultAvatar: Drawable? = getDrawable(avatarView.context, dashPayProfile.username[0])
                val avatarUrl = dashPayProfile.avatarUrl
                if (!TextUtils.isEmpty(avatarUrl)) {
                    Glide.with(avatarView).load(avatarUrl).circleCrop()
                            .placeholder(defaultAvatar).into(avatarView)
                } else {
                    avatarView.background = defaultAvatar
                }
            } else if (hideIfProfileNull) {
                avatarView.visibility = View.GONE
            }
        }
    }
}