/*
 * Copyright 2021 Dash Core Group
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

package de.schildbach.wallet.ui.invite

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.ui.util.SingleLiveEvent
import de.schildbach.wallet_test.databinding.InviteEmptyHistoryRowBinding
import de.schildbach.wallet_test.databinding.InviteHistoryCreateInviteRowBinding
import de.schildbach.wallet_test.databinding.InviteHistoryHeaderRowBinding
import de.schildbach.wallet_test.databinding.InviteHistoryRowBinding
import java.util.ArrayList

class InvitesAdapter(private val itemClickListener: OnItemClickListener,
                     private val filterClick: SingleLiveEvent<InvitesHistoryViewModel.Filter>
)
    : RecyclerView.Adapter<InvitesHistoryViewHolder>(),
        InvitesHeaderViewHolder.OnFilterListener {

    companion object {
        const val INVITE_HEADER = 1
        const val INVITE = 2
        const val EMPTY_HISTORY = 3
        const val INVITE_CREATE = 4
        val headerInvite = InvitationItem(INVITE_HEADER)
        val emptyInvite = InvitationItem(EMPTY_HISTORY)
        val createInvite = InvitationItem(INVITE_CREATE)
    }

    private var filter = InvitesHistoryViewModel.Filter.ALL
    private var showCreateInviteItem = false

    interface OnItemClickListener {
        fun onItemClicked(view: View, invitationItem: InvitationItem)
    }

    var history: List<InvitationItem> = arrayListOf()
        set(value) {
            field = value
            filteredResults.clear()
            if (showCreateInviteItem) {
                filteredResults.add(createInvite)
            }
            filteredResults.add(headerInvite)
            if (value.isNotEmpty()) {
                filteredResults.addAll(value)
            } else {
                filteredResults.add(emptyInvite)
            }
            notifyDataSetChanged()
        }
    var filteredResults: MutableList<InvitationItem> = arrayListOf()

    init {
        setHasStableIds(true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InvitesHistoryViewHolder {
            val inflator = LayoutInflater.from(parent.context)
        return when (viewType) {
            INVITE_HEADER -> {
                val binding = InviteHistoryHeaderRowBinding.inflate(inflator, parent, false)
                InvitesHeaderViewHolder(binding, this)
            }
            INVITE -> {
                val binding = InviteHistoryRowBinding.inflate(inflator, parent, false)
                InviteViewHolder(binding)
            }
            EMPTY_HISTORY -> {
                val binding = InviteEmptyHistoryRowBinding.inflate(inflator, parent, false)
                InviteEmptyViewHolder(binding)
            }
            INVITE_CREATE -> {
                val binding = InviteHistoryCreateInviteRowBinding.inflate(inflator, parent, false)
                CreateInviteViewHolder(binding)
            }
            else -> throw IllegalArgumentException("Invalid viewType $viewType")
        }
    }

    override fun getItemCount(): Int {
        return filteredResults.size
    }

    fun getItem(position: Int): InvitationItem {
        return filteredResults[position]
    }

    override fun getItemId(position: Int): Long {
        return getItem(position).id
    }

    override fun onBindViewHolder(holder: InvitesHistoryViewHolder, position: Int) {
        val inviteItem = getItem(position)
        when (getItemViewType(position)) {
            INVITE_HEADER -> {
                (holder as InvitesHeaderViewHolder).bind(null, filter, filterClick)
            }
            INVITE -> {
                (holder as InviteViewHolder).bind(inviteItem.invitation, inviteItem.uniqueIndex)
            }
            EMPTY_HISTORY -> {
                (holder as InviteEmptyViewHolder).bind(filter)
            }
            INVITE_CREATE -> {
                (holder as CreateInviteViewHolder).bind()
            }
            else -> throw IllegalArgumentException("Invalid viewType ${getItemViewType(position)}")
        }
        holder.itemView.setOnClickListener {
            itemClickListener.run {
                onItemClicked(it, inviteItem)
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        val item = filteredResults[position]
        return item.type
    }

    override fun onFilter(filter: InvitesHistoryViewModel.Filter) {
        this.filter = filter
        filter()
    }

    private fun filter() {
        val resultTransactions: MutableList<InvitationItem> = ArrayList()
        //add header
        if (showCreateInviteItem) {
            resultTransactions.add(createInvite)
        }
        resultTransactions.add(headerInvite)
        for (inviteItem in history) {
            if (inviteItem.type == INVITE) {
                when (filter) {
                    InvitesHistoryViewModel.Filter.ALL -> resultTransactions.add(inviteItem)
                    InvitesHistoryViewModel.Filter.PENDING -> {
                        if (inviteItem.invitation!!.acceptedAt == 0L) {
                            resultTransactions.add(inviteItem)
                        }
                    }
                    InvitesHistoryViewModel.Filter.CLAIMED -> {
                        if (inviteItem.invitation!!.acceptedAt != 0L) {
                            resultTransactions.add(inviteItem)
                        }
                    }
                }
            }
        }
        filteredResults.clear()
        filteredResults.addAll(resultTransactions)
        if (filteredResults.size == 1) {
            filteredResults.add(emptyInvite)
        }

        notifyDataSetChanged()
    }

    fun showCreateInvite(it: Boolean) {
        showCreateInviteItem = it
        filter()
    }
}