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

import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.database.entity.UsernameRequest
import de.schildbach.wallet.database.entity.UsernameVote
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.UsernameRequestGroupViewBinding
import de.schildbach.wallet_test.databinding.UsernameRequestViewBinding
import org.dash.wallet.common.ui.decorators.ListDividerDecorator
import org.dash.wallet.common.ui.applyStyle
import org.dash.wallet.common.ui.setRoundedRippleBackground
import org.dash.wallet.common.ui.setRoundedRippleBackgroundButtonStyle
import org.dashj.platform.sdk.platform.Names
import java.text.SimpleDateFormat

import java.util.Date
import java.util.Locale

//fun Button.setVoteThemeColors(
//    backgroundColor: ColorStateList,
//    textColor: ColorStateList
//) {
//    // Get the RippleDrawable background
//    val rippleDrawable = background as RippleDrawable
//
//    // Get the selector drawable (second layer)
//    val selectorDrawable = rippleDrawable.getDrawable(1) as StateListDrawable
//
//    // Update disabled state drawable
//    val disabledShape = (selectorDrawable.getStateDrawable(0) as GradientDrawable)
//    disabledShape.setColor(backgroundColor)
//
//    // Update enabled state drawable
//    val enabledShape = (selectorDrawable.getStateDrawable(1) as GradientDrawable)
//
//    enabledShape.setColor(backgroundColor)
//    setTextColor(textColor)
//}

fun Button.setVoteThemeColors(
    backgroundColor: Int,
    textColor: Int
) {
    try {
        val backgroundColor = context.getColor(backgroundColor)
        val rippleDrawable = background as RippleDrawable
        val backgroundDrawable = rippleDrawable.getDrawable(1).current as? GradientDrawable

        // Set background color directly on the background drawable
        backgroundDrawable?.setColor(backgroundColor)

        // Set text color
        setTextColor(context.getColor(textColor))
    } catch (e: Exception) {
        // Fallback
        background.setColorFilter(backgroundColor, PorterDuff.Mode.SRC_IN)
        setTextColor(textColor)
    }
}


class UsernameRequestGroupAdapter(
    private val usernameDetailsClickListener: (UsernameRequest) -> Unit,
    private val voteClickListener: (UsernameRequest) -> Unit
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
        val hasMoreThanOneRequest = item.requests.size > 1
        holder.bind(item, usernameDetailsClickListener, voteClickListener)
        holder.binding.root.setOnClickListener {
            // expand if there is more than 1 request, otherwise show details
            if (hasMoreThanOneRequest) {
                val index = currentList.indexOfFirst { it.username == item.username }
                currentList[index].isExpanded = !currentList[index].isExpanded
                notifyItemChanged(index)
            } else {
                usernameDetailsClickListener.invoke(item.requests.first())
            }
        }
    }
}

class UsernameRequestGroupViewHolder(
    val binding: UsernameRequestGroupViewBinding
): RecyclerView.ViewHolder(binding.root) {
    fun bind(option: UsernameRequestGroupView, usernameClickListener: (UsernameRequest) -> Unit, voteClickListener: (UsernameRequest) -> Unit) {
        val hasMoreThanOneRequest = option.requests.size > 1
        binding.username.text = option.username
        binding.requestsAmount.isVisible = hasMoreThanOneRequest
        binding.requestsAmount.text = binding.root.resources.getString(R.string.n_requests, option.requests.size)
        binding.requestsList.isVisible = option.isExpanded
        binding.chevron.isVisible = hasMoreThanOneRequest
        binding.chevron.rotation = if (option.isExpanded) 90f else 270f
        binding.linkBadge.isVisible = !hasMoreThanOneRequest
        binding.linkIncluded.isVisible = !hasMoreThanOneRequest

        //binding.blockVotes.text = binding.root.context.getString(R.string.block_vote_count, option.lockVotes())
        //binding.blockVotes.isVisible = option.lockVotes() != 0
        val context = binding.root.context
        binding.blocksButton.text = context.getString(R.string.two_lines_number_text, option.lockVotes(), context.resources.getQuantityString(R.plurals.block_vote_button, option.lockVotes()))
        binding.blocksButton.setOnClickListener {
            usernameClickListener.invoke(UsernameRequest.block(option.username, Names.normalizeString(option.username)))
        }
        binding.approvalsButton.isVisible = !hasMoreThanOneRequest
        binding.approvalsButton.text = context.getString(R.string.two_lines_number_text, option.requests.maxOf { it.votes }, context.resources.getQuantityString(R.plurals.approval_button, option.requests.maxOf { it.votes }))
        binding.approvalsButton.setOnClickListener {
            // vote for the first request, which is the only request
            usernameClickListener.invoke(option.requests.first())
        }

        // change the button colors based on our vote(s)
        val lastVote = option.votes.lastOrNull()
        when (lastVote?.type) {
            UsernameVote.APPROVE -> {
                binding.blocksButton.text = context.getString(R.string.two_lines_number_text, option.lockVotes(), context.resources.getQuantityString(R.plurals.block_vote_button, option.lockVotes()))

                //binding.approvalsButton.applyStyle(R.style.VoteButton_Blue)
                //binding.blocksButton.applyStyle(R.style.VoteButton_LightRed)

                binding.blocksButton.setVoteThemeColors(R.color.red_0_05, R.color.red)
                binding.approvalsButton.setVoteThemeColors(R.color.dash_blue, R.color.dash_white)
            }
            UsernameVote.LOCK, UsernameVote.ABSTAIN -> {
                binding.blocksButton.text = context.getString(R.string.two_lines_number_text, option.lockVotes(), context.getString(R.string.unblock_button))
                //binding.blocksButton.setRoundedRippleBackgroundButtonStyle(R.style.VoteButton_Red)
                //binding.blocksButton.applyStyle(R.style.VoteButton_Red)
                //binding.approvalsButton.applyStyle(R.style.VoteButton_LightBlue)
                binding.blocksButton.setVoteThemeColors(R.color.red, R.color.dash_white)
                binding.approvalsButton.setVoteThemeColors(R.color.dash_blue_0_05, R.color.dash_blue)
            }
            else -> {
                binding.blocksButton.text = context.getString(R.string.two_lines_number_text, option.lockVotes(), context.resources.getQuantityString(R.plurals.block_vote_button, option.lockVotes()))
                //binding.blocksButton.applyStyle(R.style.VoteButton_LightRed)
                //binding.approvalsButton.applyStyle(R.style.VoteButton_LightBlue)
                binding.blocksButton.setVoteThemeColors(R.color.red_0_05, R.color.red)
                binding.approvalsButton.setVoteThemeColors(R.color.dash_blue_0_05, R.color.dash_blue)
            }
        }
//        option.votes.lastOrNull()?.let { vote ->
//            when (vote.type) {
//                UsernameVote.LOCK, UsernameVote.ABSTAIN -> {
//                    binding.blocksButton.setRoundedBackground(R.style.Button_Vote_Red)
//                    binding.approvalsButton.setRoundedBackground(R.style.Button_Vote_LightBlue)
//                }
//                UsernameVote.APPROVE -> {
//                    binding.blocksButton.setRoundedBackground(R.style.Button_Vote_LightRed)
//                    binding.approvalsButton.setRoundedBackground(R.style.Button_Vote_Blue)
//                }
//            }
//        } ?: {
//            binding.blocksButton.setRoundedBackground(R.style.Button_Vote_LightRed)
//            binding.approvalsButton.setRoundedBackground(R.style.Button_Vote_LightBlue)
//        }
        if (option.isExpanded) {
            val adapter = UsernameRequestAdapter(
                option.votes,
                { request -> usernameClickListener.invoke(request) },
                { request -> voteClickListener.invoke(request) }
            )
            binding.requestsList.adapter = adapter
            val divider = ContextCompat.getDrawable(binding.root.context, R.drawable.list_divider)!!
            val decorator = ListDividerDecorator(
                divider,
                showAfterLast = false,
                marginStart = binding.root.resources.getDimensionPixelOffset(R.dimen.divider_margin_horizontal),
                marginEnd = 0
            )
            binding.requestsList.addItemDecoration(decorator)
//            val listWithBlock = arrayListOf<UsernameRequest>()
//            listWithBlock.addAll(option.requests)
//            val firstItem = listWithBlock.first()
//            listWithBlock.add(
//                UsernameRequest(
//                    "",
//                    firstItem.username,
//                    firstItem.normalizedLabel,
//                    -1L,
//                    "",
//                    null,
//                    0,
//                    firstItem.lockVotes,
//                    false
//                )
//            )
            adapter.submitList(option.requests)
        }
    }
}

class UsernameRequestAdapter(
    private var votes: List<UsernameVote>,
    private val clickListener: (UsernameRequest) -> Unit,
    private val voteClickListener: (UsernameRequest) -> Unit
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
            error("invalid viewType: $viewType")
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

        holder.setOnClickListener(item, {
                clickListener.invoke(item)
            }, {
                voteClickListener.invoke(item)
            }
        )
    }

    fun updateVotes(votes: List<UsernameVote>) {
        this.votes = votes
        currentList
            .indexOfFirst { it.normalizedLabel == votes.first().username }
            ?.let {
                notifyItemChanged(it)
            }
    }
}
abstract class AbstractUsernameRequestViewHolder(root: View): RecyclerView.ViewHolder(root) {
    abstract fun bind(request: UsernameRequest, votes: List<UsernameVote>)
    abstract fun setOnClickListener(usernameRequest: UsernameRequest, listener: (UsernameRequest) -> Unit, voteClickListener: (UsernameRequest) -> Unit)
    //abstract fun setOnVoteClickListener(usernameRequest: UsernameRequest, listener: (UsernameRequest) -> Unit)

}

class UsernameRequestViewHolder(
    val binding: UsernameRequestViewBinding
): AbstractUsernameRequestViewHolder(binding.root) {
    override fun bind(request: UsernameRequest, votes: List<UsernameVote>) {
        val context = binding.root.context
        val dateFormat = SimpleDateFormat("dd MMM yyyy · hh:mm a", Locale.getDefault())
        binding.dateRegistered.text = dateFormat.format(Date(request.createdAt))
//            DateTimeFormatter.ofPattern("dd MMM yyyy · hh:mm a").format(
//            LocalDateTime.ofEpochSecond(request.createdAt / 1000, 0, ZoneOffset.UTC)
//        )

        binding.approvalsButton.text = context.getString(R.string.two_lines_number_text, request.votes, context.resources.getQuantityString(R.plurals.approval_button, request.votes))
//        binding.approvalsButton.setOnClickListener {
//            // vote for the first request, which is the only request
//            usernameClickListener.invoke(option.requests.first())
//        }
//        votes.lastOrNull()?.let { vote ->
//            when (vote.type) {
//                UsernameVote.LOCK, UsernameVote.ABSTAIN -> {
//                    binding.approvalsButton.applyStyle(R.style.VoteButton_LightBlue)
//                }
//                UsernameVote.APPROVE -> {
//                    binding.approvalsButton.applyStyle(R.style.VoteButton_Blue)
//                }
//            }
//        } ?: {
//            binding.approvalsButton.applyStyle(R.style.VoteButton_LightBlue)
//        }
        val lastVote = votes.lastOrNull()
        when (lastVote?.type) {
            UsernameVote.APPROVE -> {
                binding.approvalsButton.setVoteThemeColors(R.color.dash_blue, R.color.dash_white)
            }
            UsernameVote.LOCK, UsernameVote.ABSTAIN -> {
                binding.approvalsButton.setVoteThemeColors(R.color.dash_blue_0_05, R.color.dash_blue)
            }
            else -> {
                binding.approvalsButton.setVoteThemeColors(R.color.dash_blue_0_05, R.color.dash_blue)
            }
        }


//        binding.voteAmount.setRoundedBackground(
//            if (request.isApproved) {
//                R.style.BlueBadgeTheme
//            } else {
//                R.style.InactiveBadgeTheme
//            }
//        )
//
//        binding.voteAmount.setTextColor(
//            binding.voteAmount.resources.getColor(
//                if (request.isApproved) {
//                    R.color.white
//                } else {
//                    R.color.content_tertiary
//                },
//                null
//            )
//        )

        //binding.voteAmount.text = request.votes.toString()
        binding.linkBadge.isGone = request.link.isNullOrEmpty()
        binding.linkIncluded.isGone = request.link.isNullOrEmpty()

        //val lastVote = votes.lastOrNull()
        //binding.cancelApprovalButton.isVisible = lastVote != null && lastVote.identity == request.identity && lastVote.type == UsernameVote.APPROVE
    }

    override fun setOnClickListener(usernameRequest: UsernameRequest, listener: (UsernameRequest) -> Unit, voteClickListener: (UsernameRequest) -> Unit) {
        binding.root.setOnClickListener {
            listener.invoke(usernameRequest)
        }
        binding.approvalsButton.setOnClickListener {
            voteClickListener.invoke(usernameRequest)
        }
    }
}

//class BlockViewHolder(val binding: BlockUsernameRequestViewBinding)
//    : AbstractUsernameRequestViewHolder(binding.root) {
//
//    override fun bind(request: UsernameRequest, votes: List<UsernameVote>) {
//        val lastVote = votes.lastOrNull()
//        //binding.cancelBlockButton.isVisible = lastVote != null && lastVote.identity == request.identity && lastVote.type == UsernameVote.APPROVE
//    }
//
//    override fun setOnClickListener(usernameRequest: UsernameRequest, listener: (UsernameRequest) -> Unit) {
//        binding.root.setOnClickListener {
//            listener.invoke(usernameRequest)
//        }
//    }
//}
