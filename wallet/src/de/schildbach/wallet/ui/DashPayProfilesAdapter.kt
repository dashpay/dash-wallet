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
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import de.schildbach.wallet_test.R
import org.dashevo.dpp.document.Document

class DashPayProfilesAdapter() : RecyclerView.Adapter<DashPayProfilesAdapter.ViewHolder>() {

    interface OnItemClickListener {
        fun onItemClicked(view: View, document: Document)
    }

    var itemClickListener: OnItemClickListener? = null
    var profiles: List<Document> = arrayListOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(LayoutInflater.from(parent.context), parent)
    }

    override fun getItemCount(): Int {
        return profiles.size
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(profiles[position])
    }

    inner class ViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
            RecyclerView.ViewHolder(inflater.inflate(R.layout.dashpay_profile_row, parent, false)) {

        private val avatar by lazy { itemView.findViewById<ImageView>(R.id.avatar) }
        private val publicMessage by lazy { itemView.findViewById<TextView>(R.id.publicMessage) }
        private val displayName by lazy { itemView.findViewById<TextView>(R.id.displayName) }

        fun bind(document: Document) {
            displayName.text = document.data["displayName"].toString()
            publicMessage.text = document.data["publicMessage"].toString()
            Glide.with(avatar).load(document.data["avatarUrl"]).circleCrop()
                    .placeholder(R.drawable.user5).into(avatar)

            itemClickListener?.let { l ->
                this.itemView.setOnClickListener {
                    l.onItemClicked(it, document)
                }
            }
        }

    }
}
