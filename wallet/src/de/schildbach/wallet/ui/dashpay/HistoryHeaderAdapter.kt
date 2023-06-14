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
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.database.entity.BlockchainIdentityBaseData
import de.schildbach.wallet.database.entity.BlockchainIdentityData
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.HistoryHeaderViewBinding

class HistoryHeaderAdapter(
    private val preferences: SharedPreferences
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    companion object {
        const val PREFS_FILE_NAME = "TransactionsAdapter.prefs"
        const val PREFS_KEY_HIDE_JOIN_DASHPAY_CARD = "hide_join_dashpay_card"
    }

    private lateinit var binding: HistoryHeaderViewBinding
    private var onIdentityRetryClicked: (() -> Unit)? = null
    private var onIdentityClicked: (() -> Unit)? = null
    private var onJoinDashPayClicked: (() -> Unit)? = null

    var canJoinDashPay: Boolean = false
        set(value) {
            field = value
            if (::binding.isInitialized) {
                bindCanJoinDashPay(value)
                bindBlockchainIdentity(blockchainIdentityData)
            }
        }

    var blockchainIdentityData: BlockchainIdentityBaseData? = null
        set(value) {
            field = value
            if (::binding.isInitialized) {
                bindBlockchainIdentity(value)
                bindCanJoinDashPay(canJoinDashPay)
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
            if (blockchainIdentityData.creationState == BlockchainIdentityData.CreationState.USERNAME_REGISTERING) {
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

            if (blockchainIdentityData.creationState == BlockchainIdentityData.CreationState.DONE) {
                binding.identityCreation.icon.visibility = View.GONE
            } else {
                binding.identityCreation.icon.visibility = View.VISIBLE
            }
            binding.identityCreation.retryIcon.visibility = View.GONE
            binding.identityCreation.forwardArrow.visibility = View.GONE
        }

        when (blockchainIdentityData.creationState) {
            BlockchainIdentityData.CreationState.NONE,
            BlockchainIdentityData.CreationState.UPGRADING_WALLET,
            BlockchainIdentityData.CreationState.MIXING_FUNDS,
            BlockchainIdentityData.CreationState.CREDIT_FUNDING_TX_CREATING,
            BlockchainIdentityData.CreationState.CREDIT_FUNDING_TX_SENDING,
            BlockchainIdentityData.CreationState.CREDIT_FUNDING_TX_SENT,
            BlockchainIdentityData.CreationState.CREDIT_FUNDING_TX_CONFIRMED -> {
                binding.identityCreation.progress.visibility = View.VISIBLE
                binding.identityCreation.progress.progress = 25
                binding.identityCreation.subtitle.setText(R.string.processing_home_step_1)
            }
            BlockchainIdentityData.CreationState.IDENTITY_REGISTERING,
            BlockchainIdentityData.CreationState.IDENTITY_REGISTERED -> {
                binding.identityCreation.progress.progress = 50
                binding.identityCreation.subtitle.setText(
                    if (blockchainIdentityData.restoring)
                        R.string.processing_home_step_2_restoring else
                        R.string.processing_home_step_2)
            }
            BlockchainIdentityData.CreationState.PREORDER_REGISTERING,
            BlockchainIdentityData.CreationState.PREORDER_REGISTERED,
            BlockchainIdentityData.CreationState.USERNAME_REGISTERING,
            BlockchainIdentityData.CreationState.USERNAME_REGISTERED,
            BlockchainIdentityData.CreationState.DASHPAY_PROFILE_CREATING,
            BlockchainIdentityData.CreationState.DASHPAY_PROFILE_CREATED -> {
                binding.identityCreation.progress.progress = 75
                binding.identityCreation.subtitle.setText(
                    when {
                        blockchainIdentityData.creationStateErrorMessage != null -> R.string.processing_username_unavailable_subtitle
                        blockchainIdentityData.restoring -> R.string.processing_home_step_3_restoring
                        else -> R.string.processing_home_step_3
                    }
                )
            }
            BlockchainIdentityData.CreationState.DONE -> {
                binding.identityCreation.icon.visibility = View.GONE
                binding.identityCreation.forwardArrow.visibility = View.VISIBLE
                binding.identityCreation.progress.visibility = View.GONE
                binding.identityCreation.title.text = binding.root.context.getString(R.string.processing_done_title,
                    blockchainIdentityData.username)
                binding.identityCreation.subtitle.setText(R.string.processing_done_subtitle)
            }
            BlockchainIdentityData.CreationState.DONE_AND_DISMISS -> {
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
        return blockchainIdentityData == null && canJoin && !hideJoinDashPay
    }
}