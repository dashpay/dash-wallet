package de.schildbach.wallet.ui.invite

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.data.*
import de.schildbach.wallet.ui.SingleLiveEvent
import de.schildbach.wallet.util.PlatformUtils
import org.bitcoinj.core.Sha256Hash
import java.util.ArrayList

class InvitesAdapter(private val itemClickListener: OnItemClickListener, private val filterClick: SingleLiveEvent<InvitesHistoryViewModel.Filter>)
    : RecyclerView.Adapter<InvitesHistoryViewHolder>(),
        InvitesHeaderViewHolder.OnFilterListener {

    companion object {
        const val INVITE_HEADER = 1
        const val INVITE = 2
        const val EMPTY_HISTORY = 3
        val headerId: Sha256Hash = Sha256Hash.of(Sha256Hash.ZERO_HASH.bytes)
        val emptyId: Sha256Hash = Sha256Hash.of(headerId.bytes)
        val headerInvite = Invitation(headerId.toStringBase58(), headerId, 0)
        val emptyInvite = Invitation(emptyId.toStringBase58(), emptyId, 0)
    }

    private var filter = InvitesHistoryViewModel.Filter.ALL

    interface OnItemClickListener {
        fun onItemClicked(view: View, invitation: Invitation)
    }

    var history: List<Invitation> = arrayListOf()
        set(value) {
            field = value
            filteredResults.clear()
            filteredResults.add(headerInvite)
            if (value.isNotEmpty()) {
                filteredResults.addAll(value)
            } else {
                filteredResults.add(emptyInvite)
            }
            notifyDataSetChanged()
        }
    var filteredResults: MutableList<Invitation> = arrayListOf()

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InvitesHistoryViewHolder {
        return when (viewType) {
            INVITE_HEADER -> InvitesHeaderViewHolder(LayoutInflater.from(parent.context), this, parent)
            INVITE -> InviteViewHolder(LayoutInflater.from(parent.context), parent)
            EMPTY_HISTORY -> InviteEmptyViewHolder(LayoutInflater.from(parent.context), parent)
            else -> throw IllegalArgumentException("Invalid viewType $viewType")
        }
    }

    override fun getItemCount(): Int {
        return filteredResults.size
    }

    fun getItem(position: Int): Invitation {
        return filteredResults[position]
    }

    override fun getItemId(position: Int): Long {
        return PlatformUtils.longHashFromEncodedString(getItem(position).userId)
    }

    override fun onBindViewHolder(holder: InvitesHistoryViewHolder, position: Int) {
        val invite = getItem(position)
        when (getItemViewType(position)) {
            INVITE_HEADER -> {
                (holder as InvitesHeaderViewHolder).bind(null, filter, filterClick)
            }
            INVITE -> {
                (holder as InviteViewHolder).bind(invite, position)
            }
            EMPTY_HISTORY -> {
                (holder as InviteEmptyViewHolder).bind(invite, filter)
            }
            else -> throw IllegalArgumentException("Invalid viewType ${getItemViewType(position)}")
        }
        holder.itemView.setOnClickListener {
            itemClickListener.run {
                onItemClicked(it, invite)
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = filteredResults[position]
        return when {
            (item.userId == headerId.toStringBase58()) -> INVITE_HEADER
            (item.userId == emptyId.toStringBase58()) -> EMPTY_HISTORY
            else ->  INVITE
        }
    }

    override fun onFilter(filter: InvitesHistoryViewModel.Filter) {
        this.filter = filter
        filter()
    }

    private fun filter() {
        val resultTransactions: MutableList<Invitation> = ArrayList()
        //add header
        resultTransactions.add(headerInvite)
        for (invite in history) {
            when (invite.userId) {
                /*headerId.toStringBase58() -> {
                    // always keep header
                    resultTransactions.add(invite)
                }*/
                else -> when (filter) {
                    InvitesHistoryViewModel.Filter.ALL -> resultTransactions.add(invite)
                    InvitesHistoryViewModel.Filter.PENDING -> {
                        if (invite.acceptedAt == 0L) {
                            resultTransactions.add(invite)
                        }
                    }
                    InvitesHistoryViewModel.Filter.CLAIMED -> {
                        if (invite.acceptedAt != 0L) {
                            resultTransactions.add(invite)
                        }
                    }
                }
            }
        }
        filteredResults.clear()
        filteredResults.addAll(resultTransactions)
        if (filteredResults.size == 1)
            filteredResults.add(emptyInvite)

        notifyDataSetChanged()
    }
}