package de.schildbach.wallet.ui.dashpay

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import de.schildbach.wallet.data.OnContactItemClickListener
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.ui.UserAvatarPlaceholderDrawable
import de.schildbach.wallet_test.R

class FrequentContactViewHolder(inflater: LayoutInflater, parent: ViewGroup, val itemClickListener: OnContactItemClickListener?) :
        RecyclerView.ViewHolder(inflater.inflate(R.layout.frequent_contact_item, parent, false)) {

    private val avatar by lazy { itemView.findViewById<ImageView>(R.id.avatar) }
    private val displayName by lazy { itemView.findViewById<TextView>(R.id.displayName) }

    open fun bind(usernameSearchResult: UsernameSearchResult) {
        val defaultAvatar = UserAvatarPlaceholderDrawable.getDrawable(itemView.context,
                usernameSearchResult.username[0])

        val dashPayProfile = usernameSearchResult.dashPayProfile
        if (dashPayProfile.displayName.isEmpty()) {
            displayName.text = dashPayProfile.username
        } else {
            displayName.text = dashPayProfile.displayName
        }

        if (dashPayProfile.avatarUrl.isNotEmpty()) {
            Glide.with(avatar).load(dashPayProfile.avatarUrl).circleCrop()
                    .placeholder(defaultAvatar).into(avatar)
        } else {
            avatar.background = defaultAvatar
        }

        itemClickListener?.let { l ->
            this.itemView.setOnClickListener {
                l.onItemClicked(it, usernameSearchResult)
            }
        }
    }
}