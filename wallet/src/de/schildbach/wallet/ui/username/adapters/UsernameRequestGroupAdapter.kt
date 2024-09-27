/*
 * Copyright 2023 Dash Core Group.
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
package de.schildbach.wallet.ui.username.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.UsernameRequestGroupViewBinding
import de.schildbach.wallet_test.databinding.UsernameRequestViewBinding
import org.dash.wallet.common.ui.decorators.ListDividerDecorator
import org.dash.wallet.common.ui.setRoundedBackground
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class UsernameRequestGroupAdapter(
    private val usernameClickListener: (UsernameRequest) -> Unit
): ListAdapter<UsernameRequestGroupView, UsernameRequestGroupViewHolder>(
    DiffCallback()
) {
    class DiffCallback : DiffUtil.ItemCallback<UsernameRequestGroupView>() {
        override fun areItemsTheSame(oldItem: UsernameRequestGroupView, newItem: UsernameRequestGroupView): Boolean {
            return oldItem.username == newItem.username
        }

        override fun areContentsTheSame(oldItem: UsernameRequestGroupView, newItem: UsernameRequestGroupView): Boolean {
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsernameRequestGroupViewHolder {
        val binding = UsernameRequestGroupViewBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )

        return UsernameRequestGroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UsernameRequestGroupViewHolder, position: Int) {
        val item = currentList[position]
        holder.bind(item, usernameClickListener)
        holder.binding.root.setOnClickListener {
            val index = currentList.indexOfFirst { it.username == item.username }
            currentList[index].isExpanded = !currentList[index].isExpanded
            notifyItemChanged(index)
        }
    }
}

class UsernameRequestGroupViewHolder(
    val binding: UsernameRequestGroupViewBinding
): RecyclerView.ViewHolder(binding.root) {
    fun bind(option: UsernameRequestGroupView, usernameClickListener: (UsernameRequest) -> Unit) {
        binding.username.text = option.username
        binding.requestsAmount.text = binding.root.resources.getString(R.string.n_requests, option.requests.size)
        binding.requestsList.isVisible = option.isExpanded
        binding.chevron.rotation = if (option.isExpanded) 90f else 270f

        if (option.isExpanded) {
            val adapter = UsernameRequestAdapter {
                usernameClickListener.invoke(it)
            }
            binding.requestsList.adapter = adapter
            val divider = ContextCompat.getDrawable(binding.root.context, R.drawable.list_divider)!!
            val decorator = ListDividerDecorator(
                divider,
                showAfterLast = false,
                marginStart = binding.root.resources.getDimensionPixelOffset(R.dimen.divider_margin_horizontal),
                marginEnd = 0
            )
            binding.requestsList.addItemDecoration(decorator)
            adapter.submitList(option.requests)
        }
    }
}

class UsernameRequestAdapter(
    private val clickListener: (UsernameRequest) -> Unit
) : ListAdapter<UsernameRequest, UsernameRequestViewHolder>(DiffCallback()) {

    class DiffCallback : DiffUtil.ItemCallback<UsernameRequest>() {
        override fun areItemsTheSame(oldItem: UsernameRequest, newItem: UsernameRequest): Boolean {
            return oldItem.requestId == newItem.requestId
        }

        override fun areContentsTheSame(oldItem: UsernameRequest, newItem: UsernameRequest): Boolean {
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UsernameRequestViewHolder {
        val binding = UsernameRequestViewBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )

        return UsernameRequestViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UsernameRequestViewHolder, position: Int) {
        val item = currentList[position]
        holder.bind(item)
        holder.binding.root.setOnClickListener {
            clickListener.invoke(item)
        }
    }
}

class UsernameRequestViewHolder(
    val binding: UsernameRequestViewBinding
): RecyclerView.ViewHolder(binding.root) {
    fun bind(request: UsernameRequest) {
        binding.dateRegistered.text = DateTimeFormatter.ofPattern("dd MMM yyyy · hh:mm a").format(
            LocalDateTime.ofEpochSecond(request.createdAt, 0, ZoneOffset.UTC)
        )

        binding.voteAmount.setRoundedBackground(
            if (request.isApproved) {
                R.style.BlueBadgeTheme
            } else {
                R.style.InactiveBadgeTheme
            }
        )

        binding.voteAmount.setTextColor(
            binding.voteAmount.resources.getColor(
                if (request.isApproved) {
                    R.color.white
                } else {
                    R.color.content_tertiary
                },
                null
            )
        )

        binding.voteAmount.text = request.votes.toString()
        binding.linkBadge.isVisible = !request.link.isNullOrEmpty()
    }
}
