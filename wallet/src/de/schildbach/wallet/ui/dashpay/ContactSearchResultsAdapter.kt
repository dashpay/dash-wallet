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
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.ui.UserAvatarPlaceholderDrawable
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.contact_header_row.view.*
import kotlinx.android.synthetic.main.contact_request_header_row.view.*
import kotlinx.android.synthetic.main.contact_request_row.view.*
import org.dashevo.dpp.util.HashUtils
import java.math.BigInteger


class ContactSearchResultsAdapter(private val listener: Listener) : RecyclerView.Adapter<ContactSearchResultsAdapter.ViewHolder>() {

    companion object {
        const val CONTACT_REQUEST_HEADER = 0
        const val CONTACT_REQUEST = 1
        const val CONTACT_HEADER = 2
        const val CONTACT = 3
    }

    class ViewItem(val usernameSearchResult: UsernameSearchResult?, val viewType: Int, val sortOrder: Int = 0, val requestCount: Int = 0)

    interface OnItemClickListener {
        fun onItemClicked(view: View, usernameSearchResult: UsernameSearchResult)
    }

    init {
        setHasStableIds(true)
    }
    var itemClickListener: OnItemClickListener? = null
    var results: List<ViewItem> = arrayListOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return when (viewType) {
            CONTACT_REQUEST_HEADER -> ContactRequestHeaderViewHolder(LayoutInflater.from(parent.context), parent)
            CONTACT_REQUEST -> ContactRequestViewHolder(LayoutInflater.from(parent.context), parent)
            CONTACT_HEADER -> ContactHeaderViewHolder(LayoutInflater.from(parent.context), parent)
            CONTACT -> ContactViewHolder(LayoutInflater.from(parent.context), parent)
            else -> throw IllegalArgumentException("Invalid viewType $viewType")
        }
    }

    override fun getItemCount(): Int {
        return results.size
    }

    private fun getLongValue(s: String): Long {
        val byteArray = HashUtils.byteArrayFromString(s)
        val bigInteger = BigInteger(byteArray)
        return bigInteger.toLong()
    }

    override fun getItemId(position: Int): Long {
        return when (results[position].viewType) {
            CONTACT -> getLongValue(results[position].usernameSearchResult!!.toContactRequest!!.toUserId)
            CONTACT_REQUEST -> getLongValue(results[position].usernameSearchResult!!.fromContactRequest!!.userId)
            CONTACT_REQUEST_HEADER -> 1L
            CONTACT_HEADER -> 2L
            else -> throw IllegalArgumentException("Invalid viewType ${results[position].viewType}")
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        when (results[position].viewType) {
            CONTACT, CONTACT_REQUEST -> holder.bind(results[position].usernameSearchResult!!)
            CONTACT_REQUEST_HEADER -> (holder as ContactRequestHeaderViewHolder).bind(results[position].requestCount)
            CONTACT_HEADER -> (holder as ContactHeaderViewHolder).bind(results[position].sortOrder)
            else -> throw IllegalArgumentException("Invalid viewType ${results[position].viewType}")
        }
    }
    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any?>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.itemView.alpha = 1f
        }
    }

    override fun getItemViewType(position: Int): Int {
        return results[position].viewType
    }

    open inner class ViewHolder(resId: Int, inflater: LayoutInflater, parent: ViewGroup) :
            RecyclerView.ViewHolder(inflater.inflate(resId, parent, false)) {

        private val avatar by lazy { itemView.findViewById<ImageView>(R.id.avatar) }
        private val username by lazy { itemView.findViewById<TextView>(R.id.username) }
        private val displayName by lazy { itemView.findViewById<TextView>(R.id.displayName) }

        open fun bind(usernameSearchResult: UsernameSearchResult) {
            val defaultAvatar = UserAvatarPlaceholderDrawable.getDrawable(itemView.context,
                    usernameSearchResult.username[0])

            val dashPayProfile = usernameSearchResult.dashPayProfile
            if (dashPayProfile.displayName.isEmpty()) {
                displayName.text = dashPayProfile.username
                username.text = ""
            } else {
                displayName.text = dashPayProfile.displayName
                username.text = usernameSearchResult.username
            }

            if(dashPayProfile.avatarUrl.isNotEmpty()) {
                Glide.with(avatar).load(dashPayProfile.avatarUrl).circleCrop()
                        .placeholder(defaultAvatar).into(avatar)
            } else {
                avatar.background = defaultAvatar
            }

            itemClickListener?.let { l ->
                this.itemView.setOnClickListener {
                    l.onItemClicked(it, usernameSearchResult)
                }
            }
        }
    }


    inner class ContactViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
            ViewHolder(R.layout.contact_row, inflater, parent) {

        override fun bind(usernameSearchResult: UsernameSearchResult) {
            super.bind(usernameSearchResult)
            // background color alternates based on first letter
            val color = if (usernameSearchResult.dashPayProfile.username[0].toLowerCase().toInt() % 2 != 0)
                R.color.white
            else
                R.color.dash_lighter_gray
            itemView.setBackgroundColor(itemView.resources.getColor(color))
        }
    }

    inner class ContactRequestViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
            ViewHolder(R.layout.contact_request_row, inflater, parent) {

        override fun bind(usernameSearchResult: UsernameSearchResult) {
            super.bind(usernameSearchResult)
            itemView.apply {
                accept_contact_request.setOnClickListener {
                    //TODO: this contact request should be accepted
                }

                hide_contract_request.setOnClickListener {
                    //TODO: this contact request should be hidden
                }
            }
        }
    }

    inner class ContactHeaderViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
            ViewHolder(R.layout.contact_header_row, inflater, parent) {

        var direction = UsernameSortOrderBy.DISPLAY_NAME
        fun bind(sortOrder: Int) {
            var firstTime = true
            itemView.apply {

                val adapter = ArrayAdapter.createFromResource(sort_filter.context, R.array.contacts_sort, R.layout.custom_spinner_item)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                sort_filter.adapter = adapter
                sort_filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View, position: Int, id: Long) {
                        if (firstTime) {
                            // don't process this the first time (during loading)
                            firstTime = false
                            return
                        }
                        when (position) {
                            0 -> direction = UsernameSortOrderBy.DISPLAY_NAME
                            1 -> direction = UsernameSortOrderBy.USERNAME
                            2 -> direction = UsernameSortOrderBy.DATE_ADDED
                            3 -> direction = UsernameSortOrderBy.LAST_ACTIVITY
                            else -> UsernameSortOrderBy.DISPLAY_NAME
                        }
                        firstTime = false
                        //searchContacts(direction)

                    }

                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
                sort_filter.setSelection(direction.ordinal, false)
            }
        }
    }

    inner class ContactRequestHeaderViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
            ViewHolder(R.layout.contact_request_header_row, inflater, parent) {

        fun bind(requestCount: Int) {
            itemView.apply {
                contact_request_header.text = resources.getString(R.string.contacts_contact_requests_count, requestCount)

                view_all_contacts.setOnClickListener {
                    //TODO: Show View All Contact Request Activity
                    listener.onViewAllRequests()
                }
            }
        }
    }

    interface Listener {
        fun onSortOrderChanged(direction: UsernameSortOrderBy)
        fun onViewAllRequests()
    }

    fun searchContacts(direction: UsernameSortOrderBy) {
        listener.onSortOrderChanged(direction)
    }
}
