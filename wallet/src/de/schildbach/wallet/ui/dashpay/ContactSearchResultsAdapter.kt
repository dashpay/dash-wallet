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

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.ContactViewHolder
import de.schildbach.wallet.util.PlatformUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.contact_header_row.view.*
import kotlinx.android.synthetic.main.contact_request_header_row.view.*


class ContactSearchResultsAdapter(private val listener: Listener,
                                  private val onViewAllRequestsListener: OnViewAllRequestsListener) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val CONTACT_REQUEST_HEADER = 0
        const val CONTACT_HEADER = 2
        const val CONTACT = 3
    }

    class ViewItem(val usernameSearchResult: UsernameSearchResult?, val viewType: Int, val sortOrder: Int = 0, val requestCount: Int = 0)

    init {
        setHasStableIds(true)
    }

    var itemClickListener: ContactViewHolder.OnItemClickListener? = null
    var results: ArrayList<ViewItem> = arrayListOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            CONTACT_REQUEST_HEADER -> ContactRequestHeaderViewHolder(LayoutInflater.from(parent.context), parent)
            CONTACT_HEADER -> ContactHeaderViewHolder(LayoutInflater.from(parent.context), parent)
            CONTACT -> ContactViewHolder(LayoutInflater.from(parent.context), parent)
            else -> throw IllegalArgumentException("Invalid viewType $viewType")
        }
    }

    override fun getItemCount(): Int {
        return results.size
    }

    var sendContactRequestWorkStateMap: Map<String, Resource<WorkInfo>> = mapOf()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getItemId(position: Int): Long {
        val item = results[position]
        return when (item.viewType) {
            CONTACT -> {
                if (item.usernameSearchResult!!.type == UsernameSearchResult.Type.CONTACT_ESTABLISHED) {
                    PlatformUtils.longHashFromEncodedString(item.usernameSearchResult.toContactRequest!!.toUserId)
                } else {
                    PlatformUtils.longHashFromEncodedString(item.usernameSearchResult.fromContactRequest!!.userId)
                }
            }
            CONTACT_REQUEST_HEADER -> 1L
            CONTACT_HEADER -> 2L
            else -> throw IllegalArgumentException("Invalid viewType ${item.viewType}")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = results[position]
        when (item.viewType) {
            CONTACT -> {
                val sendContactRequestWorkState = sendContactRequestWorkStateMap[item.usernameSearchResult!!.dashPayProfile.userId]
                (holder as ContactViewHolder).apply {
                    bind(item.usernameSearchResult, sendContactRequestWorkState, itemClickListener, listener)
                    if (item.usernameSearchResult.isPendingRequest) {
                        setMarginsDp(20, 3, 20, 3)
                        setBackgroundResource(R.drawable.selectable_round_corners)
                    } else {
                        setMarginsDp(0, 0, 0, 0)
                        setBackgroundColor(Color.TRANSPARENT)
                        setForegroundResource(R.drawable.selectable_background_dark)
                    }
                }
            }
            CONTACT_REQUEST_HEADER -> (holder as ContactRequestHeaderViewHolder).bind(results[position].requestCount)
            CONTACT_HEADER -> (holder as ContactHeaderViewHolder).bind(item.sortOrder)
            else -> throw IllegalArgumentException("Invalid viewType ${item.viewType}")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: List<Any?>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            holder.itemView.alpha = 1f
        }
    }

    override fun getItemViewType(position: Int): Int {
        return results[position].viewType
    }

    inner class ContactHeaderViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
            RecyclerView.ViewHolder(inflater.inflate(R.layout.contact_header_row, parent, false)) {

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
            RecyclerView.ViewHolder(inflater.inflate(R.layout.contact_request_header_row, parent, false)) {

        fun bind(requestCount: Int) {
            itemView.apply {
                contact_request_header.text = resources.getString(R.string.contacts_contact_requests_count, requestCount)

                view_all_contacts.setOnClickListener {
                    onViewAllRequestsListener.onViewAllRequests()
                }
            }
        }
    }

    interface Listener : ContactViewHolder.OnContactRequestButtonClickListener {
        fun onSortOrderChanged(direction: UsernameSortOrderBy)
    }

    interface OnViewAllRequestsListener {
        fun onViewAllRequests()
    }

    fun searchContacts(direction: UsernameSortOrderBy) {
        listener.onSortOrderChanged(direction)
    }
}
