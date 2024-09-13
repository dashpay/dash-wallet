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
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.database.entity.UsernameVote
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.BlockUsernameRequestViewBinding
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

        binding.blockVotes.text = binding.root.context.getString(R.string.block_vote_count, option.lockVotes())
        binding.blockVotes.isVisible = option.lockVotes() != 0


        if (option.isExpanded) {
            val adapter = UsernameRequestAdapter(option.votes) {
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
            val listWithBlock = arrayListOf<UsernameRequest>()
            listWithBlock.addAll(option.requests)
            val firstItem = listWithBlock.first()
            listWithBlock.add(
                UsernameRequest(
                    "",
                    firstItem.username,
                    firstItem.normalizedLabel,
                    -1L,
                    "",
                    null,
                    0,
                    firstItem.lockVotes,
                    false
                )
            )
            adapter.submitList(listWithBlock)
        }
    }
}

class UsernameRequestAdapter(
    private val votes: List<UsernameVote>,
    private val clickListener: (UsernameRequest) -> Unit
) : ListAdapter<UsernameRequest, AbstractUsernameRequestViewHolder>(DiffCallback()) {

    class DiffCallback : DiffUtil.ItemCallback<UsernameRequest>() {
        override fun areItemsTheSame(oldItem: UsernameRequest, newItem: UsernameRequest): Boolean {
            return oldItem.requestId == newItem.requestId
        }

        override fun areContentsTheSame(oldItem: UsernameRequest, newItem: UsernameRequest): Boolean {
            return oldItem == newItem
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AbstractUsernameRequestViewHolder {
        if (viewType == 0) {
            val binding = UsernameRequestViewBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )

            return UsernameRequestViewHolder(binding)
        } else {
            val binding = BlockUsernameRequestViewBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return BlockViewHolder(binding)
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).requestId == "") {
            1
        } else {
            0
        }
    }

    override fun onBindViewHolder(holder: AbstractUsernameRequestViewHolder, position: Int) {
        val item = currentList[position]
        holder.bind(item, votes)

        holder.setOnClickListener(item) {
            clickListener.invoke(item)
        }
    }
}
abstract class AbstractUsernameRequestViewHolder(root: View): RecyclerView.ViewHolder(root) {
    abstract fun bind(request: UsernameRequest, votes: List<UsernameVote>)
    abstract fun setOnClickListener(usernameRequest: UsernameRequest, listener: (UsernameRequest) -> Unit)
}

class UsernameRequestViewHolder(
    val binding: UsernameRequestViewBinding
): AbstractUsernameRequestViewHolder(binding.root) {
    override fun bind(request: UsernameRequest, votes: List<UsernameVote>) {
        binding.dateRegistered.text = DateTimeFormatter.ofPattern("dd MMM yyyy · hh:mm a").format(
            LocalDateTime.ofEpochSecond(request.createdAt / 1000, 0, ZoneOffset.UTC)
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
        binding.linkBadge.isGone = request.link.isNullOrEmpty()
        binding.linkIncluded.isGone = request.link.isNullOrEmpty()

        val lastVote = votes.lastOrNull()
        binding.cancelApprovalButton.isVisible = lastVote != null && lastVote.identity == request.identity && lastVote.type == UsernameVote.APPROVE
    }

    override fun setOnClickListener(usernameRequest: UsernameRequest, listener: (UsernameRequest) -> Unit) {
        binding.root.setOnClickListener {
            listener.invoke(usernameRequest)
        }
    }
}

class BlockViewHolder(val binding: BlockUsernameRequestViewBinding)
    : AbstractUsernameRequestViewHolder(binding.root) {

    override fun bind(request: UsernameRequest, votes: List<UsernameVote>) {
        val lastVote = votes.lastOrNull()
        //binding.cancelBlockButton.isVisible = lastVote != null && lastVote.identity == request.identity && lastVote.type == UsernameVote.APPROVE
    }

    override fun setOnClickListener(usernameRequest: UsernameRequest, listener: (UsernameRequest) -> Unit) {
        binding.root.setOnClickListener {
            listener.invoke(usernameRequest)
        }
    }
}
