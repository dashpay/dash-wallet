package de.schildbach.wallet.ui.invite

import android.view.LayoutInflater
import android.view.ViewGroup
import de.schildbach.wallet.data.Invitation
import de.schildbach.wallet.ui.SingleLiveEvent
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.invite_history_header_row.view.*

open class InvitesHeaderViewHolder(inflater: LayoutInflater,
                                   val onFilterListener: OnFilterListener,
                                   parent: ViewGroup) :
        InvitesHistoryViewHolder(R.layout.invite_history_header_row, inflater, parent) {


    interface OnFilterListener {
        fun onFilter(filter: InvitesHistoryViewModel.Filter)
    }

    init {

    }

    fun bind(invitation: Invitation?,
             filter: InvitesHistoryViewModel.Filter,
             filterClick: SingleLiveEvent<InvitesHistoryViewModel.Filter>) {

        itemView.apply {
            val array = context.resources.getStringArray(R.array.invite_filter)
            invite_filter_text.text = array[filter.ordinal]
            invite_filter.setOnClickListener {
                filterClick.call(filter)
            }
        }
    }
}