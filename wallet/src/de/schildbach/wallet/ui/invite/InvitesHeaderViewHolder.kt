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
import android.view.ViewGroup
import de.schildbach.wallet.database.entity.Invitation
import de.schildbach.wallet.ui.util.SingleLiveEvent
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.invite_history_header_row.view.*

open class InvitesHeaderViewHolder(inflater: LayoutInflater,
                                   val onFilterListener: OnFilterListener,
                                   parent: ViewGroup) :
        InvitesHistoryViewHolder(R.layout.invite_history_header_row, inflater, parent) {


    interface OnFilterListener {
        fun onFilter(filter: InvitesHistoryViewModel.Filter)
    }

    fun bind(invitation: Invitation?,
             filter: InvitesHistoryViewModel.Filter,
             filterClick: SingleLiveEvent<InvitesHistoryViewModel.Filter>
    ) {

        itemView.apply {
            val array = context.resources.getStringArray(R.array.invite_filter)
            invite_filter_text.text = array[filter.ordinal]
            invite_filter.setOnClickListener {
                filterClick.postValue(filter)
            }
        }
    }
}