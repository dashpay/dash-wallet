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

/**
 * Pure hello-card visibility gate (host-JVM unit-testable, following the
 * `usernameSubmitButtonState` helper pattern). The card shows for:
 *
 * - a creation in progress / complete / errored, until dismissed (the
 *   pre-existing gate); and
 * - a DUAL creation whose contested PRIMARY is still in VOTING but whose
 *   INSTANT secondary is already registered ([BlockchainIdentityBaseData
 *   .usernameSecondary] present): the instant name is usable immediately, so
 *   the welcome card must show for it while the vote runs (observed live: a
 *   dual creation had no welcome tile). [votingDualDismissed] is that card's
 *   own persisted dismissal — the state machine's DONE_AND_DISMISS cannot be
 *   used while the state is VOTING.
 *
 * A contested-only creation in VOTING (no secondary) still shows NO card —
 * nothing is usable yet; its status lives on the More screen's voting tile.
 * Restore paths land on DONE_AND_DISMISS and stay hidden, as before.
 */
internal fun helloCardEligible(
    blockchainIdentityData: BlockchainIdentityBaseData,
    votingDualDismissed: Boolean
): Boolean {
    val votingWithInstantUsername = blockchainIdentityData.votingInProgress &&
        !blockchainIdentityData.usernameSecondary.isNullOrEmpty() &&
        !votingDualDismissed
    return votingWithInstantUsername ||
        (
            (
                blockchainIdentityData.creationInProgress ||
                    blockchainIdentityData.creationComplete ||
                    blockchainIdentityData.creationError
                ) &&
                !blockchainIdentityData.creationCompleteDismissed
            )
}

/**
 * Pure "Join DashPay" tile visibility gate (host-JVM unit-testable, same
 * pattern as [helloCardEligible]).
 *
 * The tile is an ENTRY POINT into identity creation, so it must not be
 * offered while the L1 scan is still running: mid-sync the balance and
 * history are incomplete, and identity creation needs confirmed,
 * attributable funds — a user who taps through mid-restore is told they
 * cannot afford a username they can in fact afford.
 *
 * The sync factor used to live in `MainViewModel.combineLatestData()`, which
 * feeds `isAbleToCreateIdentity`; it is commented out there
 * (`/*isSynced &&*/`) along with its `_isBlockchainSynced` MediatorLiveData
 * source, so `canJoin` alone carries no sync information. The More screen
 * compensates locally (`MoreFragment.updateJoinDashPaySyncState`, driven by
 * the same `MainViewModel.syncStatus.isSynced`); this header did not, which
 * is the defect. [isSynced] here is fed from that same authoritative signal.
 *
 * Unlike the More screen — which greys the row and shows a "still syncing"
 * line, as the sibling accept-invitation row in this header also does — the
 * home tile is HIDDEN while unsynced: it is an unsolicited nudge rather than
 * a row the user navigated to, so a disabled nudge is just noise.
 */
internal fun joinDashPayEligible(
    creationState: IdentityCreationState?,
    canJoin: Boolean,
    isSynced: Boolean,
    hideJoinDashPayCard: Boolean
): Boolean {
    return creationState == IdentityCreationState.NONE &&
        canJoin &&
        isSynced &&
        !hideJoinDashPayCard
}

class HistoryHeaderAdapter(
    private val preferences: SharedPreferences
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    companion object {
        const val PREFS_FILE_NAME = "TransactionsAdapter.prefs"
        const val PREFS_KEY_HIDE_JOIN_DASHPAY_CARD = "hide_join_dashpay_card"

        /**
         * Persisted dismissal of the DUAL-creation welcome card shown while
         * the contested primary is in VOTING (see [helloCardEligible]) — a
         * SharedPreferences flag because the state machine's DONE_AND_DISMISS
         * must not be written while the state is VOTING.
         */
        const val PREFS_KEY_HIDE_VOTING_DUAL_HELLO_CARD = "hide_voting_dual_hello_card"
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

    /**
     * L1 scan completion, fed from `MainViewModel.syncStatus.isSynced`.
     * Gates BOTH the accept-invitation row (greyed + "still syncing") and,
     * since it is an entry point into identity creation, the Join DashPay
     * tile (hidden outright — see [joinDashPayEligible]). The setter
     * re-binds every tile, so the Join tile appears the moment the scan
     * finishes rather than waiting for an unrelated header update.
     */
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

    /**
     * Transient retry-status hint for the identity processing tile
     * (why the current step is taking longer than usual — e.g. "waiting
     * for the network to catch up"); null hides the line. Fed from
     * [IdentityCreationStatusHolder] by the fragment.
     */
    var statusHint: String? = null
        set(value) {
            field = value
            if (::binding.isInitialized) {
                bindBlockchainIdentity(blockchainIdentityData)
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

        // Secondary status line: only meaningful while the state machine is
        // actively working a step (a terminal/voting tile has nothing to
        // explain; the error tile has its own copy).
        val showStatusHint = statusHint != null &&
            blockchainIdentityData.creationStateErrorMessage == null &&
            blockchainIdentityData.creationState < IdentityCreationState.VOTING
        binding.identityCreation.statusHint.isVisible = showStatusHint
        binding.identityCreation.statusHint.text = statusHint

        if (blockchainIdentityData.creationStateErrorMessage != null) {
            val creationStateErrorMessage = blockchainIdentityData.creationStateErrorMessage!!
            if ((blockchainIdentityData.creationState == IdentityCreationState.USERNAME_REGISTERING ||
                blockchainIdentityData.creationState == IdentityCreationState.USERNAME_SECONDARY_REGISTERING) &&
                (creationStateErrorMessage.contains("Document transitions with duplicate unique properties") ||
                        creationStateErrorMessage.contains("DuplicateUniqueIndexError") == true ||
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
                val instantUsername = blockchainIdentityData.usernameSecondary
                if (!instantUsername.isNullOrEmpty()) {
                    // DUAL creation: the INSTANT secondary is registered and
                    // usable right now, so this is its welcome card — the
                    // contested primary's voting status lives on the More
                    // screen's voting tile (see helloCardEligible).
                    binding.identityCreation.title.text = binding.root.context.getString(
                        R.string.processing_done_title,
                        instantUsername
                    )
                    binding.identityCreation.subtitle.setText(R.string.processing_done_subtitle)
                } else {
                    binding.identityCreation.title.text = binding.root.context.getString(R.string.processing_done_title,
                        blockchainIdentityData.username)
                    binding.identityCreation.subtitle.setText(R.string.processing_voting_subtitle)
                }
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
        return helloCardEligible(
            blockchainIdentityData,
            votingDualDismissed = preferences.getBoolean(PREFS_KEY_HIDE_VOTING_DUAL_HELLO_CARD, false)
        )
    }

    /**
     * Persist + apply the dismissal of the VOTING-dual welcome card (the
     * DONE card's dismissal advances the state machine instead — see
     * [helloCardEligible]).
     */
    fun dismissVotingDualHelloCard() {
        preferences.edit().putBoolean(PREFS_KEY_HIDE_VOTING_DUAL_HELLO_CARD, true).apply()
        if (::binding.isInitialized) {
            bindBlockchainIdentity(blockchainIdentityData)
        }
    }

    private fun shouldShowJoinDashPay(canJoin: Boolean): Boolean {
        return joinDashPayEligible(
            creationState = blockchainIdentityData?.creationState,
            canJoin = canJoin,
            isSynced = isSynced,
            hideJoinDashPayCard = preferences.getBoolean(PREFS_KEY_HIDE_JOIN_DASHPAY_CARD, false)
        )
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