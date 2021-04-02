package de.schildbach.wallet.ui.invite

import android.view.LayoutInflater
import android.view.ViewGroup
import de.schildbach.wallet.data.Invitation
import de.schildbach.wallet.util.WalletUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.invite_history_row.view.*

open class InviteViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
        InvitesHistoryViewHolder(R.layout.invite_history_row, inflater, parent) {

    fun bind(invitation: Invitation?, vararg args: Any?) {
        itemView.apply {
            val id = args[0] as Int
            memo.text = if (invitation!!.memo.isNotEmpty()) {
                invitation.memo
            } else {
                itemView.context.getString(R.string.invitation_created_title) + " " + id // is this a good way or should it be "Invitation %d"
            }
            state_icon.setImageResource(if (invitation.acceptedAt != 0L) R.drawable.ic_claimed_invite else R.drawable.ic_pending_invite)
            date.text = WalletUtils.formatDate(invitation.sentAt)
        }
    }
}