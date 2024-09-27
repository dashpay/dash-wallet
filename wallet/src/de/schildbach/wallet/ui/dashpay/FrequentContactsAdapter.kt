/*
 * Copyright 2020 Dash Core Group.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.dashpay

import android.view.LayoutInflater
import android.view.ViewGroup
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
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.util.PlatformUtils
import de.schildbach.wallet_test.databinding.FrequentContactItemBinding

class FrequentContactsAdapter() :
        RecyclerView.Adapter<FrequentContactViewHolder>() {

    init {
        setHasStableIds(true)
    }

    var itemClickListener: OnContactItemClickListener? = null
    var results: ArrayList<UsernameSearchResult> = arrayListOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FrequentContactViewHolder {
        val binding = FrequentContactItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FrequentContactViewHolder(binding, itemClickListener)
    }

    override fun getItemCount(): Int {
        return results.size
    }

    override fun getItemId(position: Int): Long {
        return PlatformUtils.longHashFromEncodedString(results[position].fromContactRequest!!.userId)
    }

    override fun onBindViewHolder(holder: FrequentContactViewHolder, position: Int) {
        holder.bind(results[position])
    }
}
