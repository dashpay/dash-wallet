package de.schildbach.wallet.ui.invite

import android.view.LayoutInflater
import android.view.ViewGroup
import de.schildbach.wallet.data.Invitation
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.invite_empty_history_row.view.*

open class InviteEmptyViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
        InvitesHistoryViewHolder(R.layout.invite_empty_history_row, inflater, parent) {

    fun bind(invitation: Invitation, filter: InvitesHistoryViewModel.Filter) {
        itemView.apply {
            empty_text.setText(when (filter) {
                InvitesHistoryViewModel.Filter.CLAIMED -> R.string.invite_history_empty_claimed
                InvitesHistoryViewModel.Filter.PENDING -> R.string.invite_history_empty_pending
                else -> R.string.invite_history_empty
            })
        }
    }
}