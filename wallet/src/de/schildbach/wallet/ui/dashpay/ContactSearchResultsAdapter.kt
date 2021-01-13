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

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.work.WorkInfo
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.data.UsernameSortOrderBy
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.ContactViewHolder
import de.schildbach.wallet.ui.SearchUserActivity
import de.schildbach.wallet.util.PlatformUtils
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.contact_header_row.view.*
import kotlinx.android.synthetic.main.contact_request_header_row.view.*
import kotlinx.android.synthetic.main.contacts_list_layout.*
import kotlinx.android.synthetic.main.contacts_suggestions_header.view.*
import kotlinx.android.synthetic.main.no_contacts_results.view.*


class ContactSearchResultsAdapter(private val listener: Listener,
                                  private val onViewAllRequestsListener: OnViewAllRequestsListener) :
        RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var query = ""

    companion object {
        const val CONTACT_REQUEST_HEADER = 0
        const val CONTACT_HEADER = 2
        const val CONTACT = 3
        const val CONTACT_NO_RESULTS = 4
        const val CONTACTS_SUGGESTIONS_HEADER = 5
        const val CONTACT_SUGGESTION_ROW = 6
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
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            CONTACT_REQUEST_HEADER -> ContactRequestHeaderViewHolder(inflater, parent)
            CONTACT_HEADER -> ContactHeaderViewHolder(inflater, parent)
            CONTACT -> ContactViewHolder(inflater, parent, R.layout.dashpay_contact_row)
            CONTACT_NO_RESULTS -> ContactsNoResultsViewHolder(inflater, parent)
            CONTACTS_SUGGESTIONS_HEADER -> ContactsSuggestionsHeaderViewHolder(inflater, parent)
            CONTACT_SUGGESTION_ROW -> ContactViewHolder(inflater, parent,
                    R.layout.dashpay_contact_suggestion_row, isSuggestion = true)
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
            CONTACT_NO_RESULTS -> 3L
            CONTACTS_SUGGESTIONS_HEADER -> 4L
            CONTACT_SUGGESTION_ROW -> PlatformUtils.longHashFromEncodedString(item.usernameSearchResult!!.dashPayProfile.userId)
            else -> throw IllegalArgumentException("Invalid viewType ${item.viewType}")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = results[position]
        when (item.viewType) {
            CONTACT -> {
                val sendContactRequestWorkState = sendContactRequestWorkStateMap[item.usernameSearchResult!!.dashPayProfile.userId]
                (holder as ContactViewHolder).bind(item.usernameSearchResult, sendContactRequestWorkState, itemClickListener, listener)
            }
            CONTACT_REQUEST_HEADER -> (holder as ContactRequestHeaderViewHolder).bind(results[position].requestCount)
            CONTACT_HEADER -> (holder as ContactHeaderViewHolder).bind(item.sortOrder)
            CONTACT_NO_RESULTS -> (holder as ContactsNoResultsViewHolder).bind()
            CONTACTS_SUGGESTIONS_HEADER -> (holder as ContactsSuggestionsHeaderViewHolder).bind(query)
            CONTACT_SUGGESTION_ROW -> (holder as ContactViewHolder).bind(item.usernameSearchResult!!, null, itemClickListener, listener)
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

    inner class ContactsNoResultsViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
            RecyclerView.ViewHolder(inflater.inflate(R.layout.no_contacts_results, parent, false)) {

        fun bind() {
            itemView.search_for_user_suggestions.setOnClickListener {
                listener.onSearchUser()
            }
        }
    }

    inner class ContactsSuggestionsHeaderViewHolder(inflater: LayoutInflater, parent: ViewGroup) :
            RecyclerView.ViewHolder(inflater.inflate(R.layout.contacts_suggestions_header, parent, false)) {

        fun bind(query: String) {
            val suggestionsSubtitle = itemView.context.getString(R.string.users_that_matches) + " \"<b>$query</b>\" " +
                    itemView.context.getString(R.string.not_in_your_contacts)
            val suggestionsSubtitleTv = itemView.suggestions_subtitle
            suggestionsSubtitleTv.text = HtmlCompat.fromHtml(suggestionsSubtitle, HtmlCompat.FROM_HTML_MODE_COMPACT)
        }
    }

    interface Listener : ContactViewHolder.OnContactRequestButtonClickListener {
        fun onSortOrderChanged(direction: UsernameSortOrderBy)
        fun onSearchUser()
    }

    interface OnViewAllRequestsListener {
        fun onViewAllRequests()
    }

    fun searchContacts(direction: UsernameSortOrderBy) {
        listener.onSortOrderChanged(direction)
    }
}
