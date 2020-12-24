/*
 * Copyright 2020 Dash Core Group
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
package de.schildbach.wallet.ui.dashpay

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.frequent_contact_item.view.*

class FrequentContactViewHolder(inflater: LayoutInflater, parent: ViewGroup, val itemClickListener: OnContactItemClickListener?) :
        RecyclerView.ViewHolder(inflater.inflate(R.layout.frequent_contact_item, parent, false)) {

    open fun bind(usernameSearchResult: UsernameSearchResult) {
        itemView.apply {

            val dashPayProfile = usernameSearchResult.dashPayProfile
            if (dashPayProfile.displayName.isEmpty()) {
                display_name.text = dashPayProfile.username
            } else {
                display_name.text = dashPayProfile.displayName
            }

            ProfilePictureDisplay.display(avatar, dashPayProfile)

            itemClickListener?.let { l ->
                setOnClickListener {
                    l.onItemClicked(it, usernameSearchResult)
                }
            }
        }
    }
}