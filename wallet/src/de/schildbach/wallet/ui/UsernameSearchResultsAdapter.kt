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

package de.schildbach.wallet.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.livedata.Resource

class UsernameSearchResultsAdapter(private val onContactRequestButtonClickListener: ContactViewHolder.OnContactRequestButtonClickListener) : RecyclerView.Adapter<ContactViewHolder>() {

    var itemClickListener: ContactViewHolder.OnItemClickListener? = null
    var results: List<UsernameSearchResult> = arrayListOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    var pending: Map<String, Resource<WorkInfo>> = mapOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContactViewHolder {
        return ContactViewHolder(LayoutInflater.from(parent.context), parent)
    }

    override fun getItemCount(): Int {
        return results.size
    }

    override fun onBindViewHolder(holder: ContactViewHolder, position: Int) {
        val item = results[position]
        val state = pending[item.dashPayProfile.userId]
        holder.bind(results[position], state, itemClickListener, onContactRequestButtonClickListener)
    }
}
