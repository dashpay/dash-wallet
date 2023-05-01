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
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.invite_empty_history_row.view.*

open class InviteEmptyViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
        InvitesHistoryViewHolder(R.layout.invite_empty_history_row, inflater, parent) {

    fun bind(filter: InvitesHistoryViewModel.Filter) {
        itemView.apply {
            empty_text.setText(when (filter) {
                InvitesHistoryViewModel.Filter.CLAIMED -> R.string.invite_history_empty_claimed
                InvitesHistoryViewModel.Filter.PENDING -> R.string.invite_history_empty_pending
                else -> R.string.invite_history_empty
            })
        }
    }
}