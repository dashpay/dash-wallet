/*
 * Copyright 2022 Dash Core Group.
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

import android.content.SharedPreferences
import android.graphics.drawable.AnimationDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.data.InvitationLinkData
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.IdentityCreationState
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.HistoryHeaderViewBinding
import org.slf4j.LoggerFactory

class HistoryHeaderAdapter(
    private val preferences: SharedPreferences
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    companion object {
        const val PREFS_FILE_NAME = "TransactionsAdapter.prefs"
        const val PREFS_KEY_HIDE_JOIN_DASHPAY_CARD = "hide_join_dashpay_card"
        private val log = LoggerFactory.getLogger(HistoryHeaderAdapter::class.java)
    }

    private lateinit var binding: HistoryHeaderViewBinding
    private var onIdentityRetryClicked: (() -> Unit)? = null
    private var onIdentityClicked: (() -> Unit)? = null
    private var onJoinDashPayClicked: (() -> Unit)? = null
    private var onAcceptInviteCreateClicked: (() -> Unit)? = null
    private var onAcceptInviteHideClicked: (() -> Unit)? = null

    var canJoinDashPay: Boolean = false
        set(value) {
            field = value
            if (::binding.isInitialized) {
                bindCanJoinDashPay(value)
                bindBlockchainIdentity(blockchainIdentityData)
                bindInvitation(invitation, isSynced)
            }
        }

    var blockchainIdentityData: BlockchainIdentityBaseData? = null
        set(value) {
            field = value
            if (::binding.isInitialized) {
                bindBlockchainIdentity(value)
                bindCanJoinDashPay(canJoinDashPay)
                bindInvitation(invitation, isSynced)
            }
        }

    var invitation: InvitationLinkData? = null
        set(value) {
            field = value
            log.info("set invite = $value")
            if (::binding.isInitialized) {
                bindInvitation(value, isSynced)
                bindBlockchainIdentity(blockchainIdentityData)
                bindCanJoinDashPay(canJoinDashPay)
            }
        }

    var isSynced: Boolean = false
        set(value) {
            field = value
            log.info("set invite, synced = $value")
            if (::binding.isInitialized) {
                bindInvitation(invitation, value)
                bindBlockchainIdentity(blockchainIdentityData)
                bindCanJoinDashPay(canJoinDashPay)
            }
        }

    private fun bindInvitation(invitation: InvitationLinkData?, isSynced: Boolean) {
        if (blockchainIdentityData != null && !shouldShowAcceptInvitation(invitation, isSynced)) {
            binding.acceptInvitation.root.isVisible = false
            return
        }
        binding.acceptInvitation.root.isVisible = true
        binding.acceptInvitation.joinDashpayWait.isVisible = !isSynced
        binding.acceptInvitation.createButton.isEnabled = isSynced && invitation?.isValid == true

        binding.acceptInvitation.icon.setColorFilter(
            if (isSynced) {
                ContextCompat.getColor(binding.root.context, R.color.dash_blue)
            } else {
                ContextCompat.getColor(binding.root.context, R.color.gray)
            }
        )

        binding.acceptInvitation.createButton.setOnClickListener {
            onAcceptInviteCreateClicked?.invoke()
        }
        binding.acceptInvitation.hideButton.setOnClickListener {
            onAcceptInviteHideClicked?.invoke()
        }
    }

    override fun getItemCount() = 1


    override fun getItemViewType(position: Int) = R.layout.history_header_view

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        binding = HistoryHeaderViewBinding.inflate(inflater, parent, false)

        return object : RecyclerView.ViewHolder(binding.root) {}
    }

    override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
        bindBlockchainIdentity(blockchainIdentityData)
        bindCanJoinDashPay(canJoinDashPay)
        bindInvitation(invitation, isSynced)
    }

    fun setOnIdentityRetryClicked(listener: () -> Unit) {
        onIdentityRetryClicked = listener
    }

    fun setOnIdentityClicked(listener: () -> Unit) {
        onIdentityClicked = listener
    }

    fun setOnJoinDashPayClicked(listener: () -> Unit) {
        onJoinDashPayClicked = listener
    }

    fun setOnAcceptInviteCreateClicked(listener: () -> Unit) {
        onAcceptInviteCreateClicked = listener
    }
    fun setOnAcceptInviteHideClicked(listener: () -> Unit) {
        onAcceptInviteHideClicked = listener
    }

    private fun bindBlockchainIdentity(
        blockchainIdentityData: BlockchainIdentityBaseData?
    ) {
        if (blockchainIdentityData == null || !shouldShowHelloCard(blockchainIdentityData)) {
            binding.identityCreation.root.isVisible = false
            return
        }

        binding.identityCreation.root.isVisible = true
        binding.identityCreation.root.setOnClickListener { onIdentityClicked?.invoke() }

        if (blockchainIdentityData.creationStateErrorMessage != null) {
            val creationStateErrorMessage = blockchainIdentityData.creationStateErrorMessage!!
            if (blockchainIdentityData.creationState == IdentityCreationState.USERNAME_REGISTERING &&
                (creationStateErrorMessage.contains("Document transitions with duplicate unique properties") ||
                    creationStateErrorMessage.contains("Document Contest for vote_poll ContestedDocumentResourceVotePoll")) ||
                    creationStateErrorMessage.contains(Regex("does not have .* as a contender")) ||
                    creationStateErrorMessage.contains("missing domain document for ")
                ) {
                binding.identityCreation.title.text = binding.root.context.getString(R.string.processing_username_unavailable_title)
                binding.identityCreation.subtitle.visibility = View.VISIBLE
                binding.identityCreation.icon.setImageResource(R.drawable.ic_username_unavailable)
                binding.identityCreation.retryIcon.visibility = View.GONE
                binding.identityCreation.forwardArrow.visibility = View.VISIBLE
            } else {
                binding.identityCreation.title.text = binding.root.context.getString(R.string.processing_error_title)
                binding.identityCreation.subtitle.visibility = View.GONE
                binding.identityCreation.icon.setImageResource(R.drawable.ic_error)
                binding.identityCreation.retryIcon.visibility = View.VISIBLE
                binding.identityCreation.retryIcon.setOnClickListener { onIdentityRetryClicked?.invoke() }
                binding.identityCreation.forwardArrow.visibility = View.GONE
            }
        } else {
            binding.identityCreation.title.text = binding.root.context.getString(R.string.processing_home_title)
            binding.identityCreation.subtitle.visibility = View.VISIBLE
            binding.identityCreation.icon.setImageResource(R.drawable.identity_processing)
            (binding.identityCreation.icon.drawable as AnimationDrawable).start()

            if (blockchainIdentityData.creationState == IdentityCreationState.DONE) {
                binding.identityCreation.icon.visibility = View.GONE
            } else {
                binding.identityCreation.icon.visibility = View.VISIBLE
            }
            binding.identityCreation.retryIcon.visibility = View.GONE
            binding.identityCreation.forwardArrow.visibility = View.GONE
        }

        when (blockchainIdentityData.creationState) {
            IdentityCreationState.NONE,
            IdentityCreationState.UPGRADING_WALLET,
            IdentityCreationState.CREDIT_FUNDING_TX_CREATING,
            IdentityCreationState.CREDIT_FUNDING_TX_SENDING,
            IdentityCreationState.CREDIT_FUNDING_TX_SENT,
            IdentityCreationState.CREDIT_FUNDING_TX_CONFIRMED -> {
                binding.identityCreation.progress.visibility = View.VISIBLE
                binding.identityCreation.progress.progress = 25
                binding.identityCreation.subtitle.setText(R.string.processing_home_step_1)
            }
            IdentityCreationState.IDENTITY_REGISTERING,
            IdentityCreationState.IDENTITY_REGISTERED -> {
                binding.identityCreation.progress.progress = 50
                binding.identityCreation.subtitle.setText(
                    if (blockchainIdentityData.restoring)
                        R.string.processing_home_step_2_restoring else
                        R.string.processing_home_step_2)
            }
            IdentityCreationState.PREORDER_REGISTERING,
            IdentityCreationState.PREORDER_REGISTERED,
            IdentityCreationState.USERNAME_REGISTERING,
            IdentityCreationState.USERNAME_REGISTERED,
            IdentityCreationState.PREORDER_SECONDARY_REGISTERING,
            IdentityCreationState.PREORDER_SECONDARY_REGISTERED,
            IdentityCreationState.USERNAME_SECONDARY_REGISTERING,
            IdentityCreationState.USERNAME_SECONDARY_REGISTERED,
            IdentityCreationState.DASHPAY_PROFILE_CREATING,
            IdentityCreationState.DASHPAY_PROFILE_CREATED -> {
                binding.identityCreation.progress.progress = 75
                binding.identityCreation.subtitle.setText(
                    when {
                        blockchainIdentityData.creationStateErrorMessage != null -> R.string.processing_username_unavailable_subtitle
                        blockchainIdentityData.restoring -> R.string.processing_home_step_3_restoring
                        blockchainIdentityData.requestedUsername != null -> R.string.processing_home_step_3_requesting
                        else -> R.string.processing_home_step_3
                    }
                )
            }
            IdentityCreationState.REQUESTED_NAME_CHECKING,
            IdentityCreationState.REQUESTED_NAME_CHECKED,
            IdentityCreationState.REQUESTED_NAME_LINK_SAVING,
            IdentityCreationState.REQUESTED_NAME_LINK_SAVED -> {
                binding.identityCreation.progress.progress = 90
            }
            IdentityCreationState.VOTING -> {
                binding.identityCreation.icon.visibility = View.GONE
                binding.identityCreation.forwardArrow.visibility = View.VISIBLE
                binding.identityCreation.progress.visibility = View.GONE
                binding.identityCreation.title.text = binding.root.context.getString(R.string.processing_done_title,
                    blockchainIdentityData.username)
                binding.identityCreation.subtitle.setText(R.string.processing_voting_subtitle)
            }
            IdentityCreationState.DONE -> {
                binding.identityCreation.icon.visibility = View.GONE
                binding.identityCreation.forwardArrow.visibility = View.VISIBLE
                binding.identityCreation.progress.visibility = View.GONE
                binding.identityCreation.title.text = binding.root.context.getString(R.string.processing_done_title,
                    blockchainIdentityData.username)
                binding.identityCreation.subtitle.setText(R.string.processing_done_subtitle)
            }
            IdentityCreationState.DONE_AND_DISMISS -> {
                // nothing to do
            }
        }
    }

    private fun bindCanJoinDashPay(canJoin: Boolean) {
        if (!shouldShowJoinDashPay(canJoin)) {
            binding.joinDashpayBtn.root.isVisible = false
            return
        }

        binding.joinDashpayBtn.root.isVisible = true
        binding.joinDashpayBtn.root.setOnClickListener {
            preferences.edit().putBoolean(PREFS_KEY_HIDE_JOIN_DASHPAY_CARD, true).apply()
            onJoinDashPayClicked?.invoke()
        }
    }

    private fun shouldShowHelloCard(blockchainIdentityData: BlockchainIdentityBaseData): Boolean {
        return (blockchainIdentityData.creationInProgress ||
                blockchainIdentityData.creationComplete ||
                blockchainIdentityData.creationError) &&
                !blockchainIdentityData.creationCompleteDismissed
    }

    private fun shouldShowJoinDashPay(canJoin: Boolean): Boolean {
        val hideJoinDashPay = preferences.getBoolean(PREFS_KEY_HIDE_JOIN_DASHPAY_CARD, false)
        return blockchainIdentityData?.creationState == IdentityCreationState.NONE && canJoin && !hideJoinDashPay
    }

    private fun shouldShowAcceptInvitation(invitation: InvitationLinkData?, isSynced: Boolean): Boolean {
        return invitation != null && blockchainIdentityData?.creationInProgress == false
    }

    fun isEmpty(): Boolean {
        return !shouldShowAcceptInvitation(invitation, isSynced) &&
                !shouldShowJoinDashPay(canJoinDashPay) &&
                (blockchainIdentityData == null || !shouldShowHelloCard(blockchainIdentityData!!))
    }
}