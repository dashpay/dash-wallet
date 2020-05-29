package de.schildbach.wallet.ui.dashpay

import android.graphics.drawable.AnimationDrawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.data.BlockchainIdentityBaseData
import de.schildbach.wallet.data.BlockchainIdentityData.CreationState
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.identity_creation_state.view.*

class ProcessingIdentityViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    fun bind(blockchainIdentityData: BlockchainIdentityBaseData, onRetryClickListener: View.OnClickListener) {
        if (blockchainIdentityData.creationStateErrorMessage != null) {
            if (blockchainIdentityData.creationState == CreationState.USERNAME_REGISTERING) {
                itemView.title.text = itemView.context.getString(R.string.processing_username_unavailable_title)
                itemView.subtitle.visibility = View.VISIBLE
                itemView.icon.setImageResource(R.drawable.ic_username_unavailable)
                itemView.retry_icon.visibility = View.GONE
                itemView.forward_arrow.visibility = View.VISIBLE
            } else {
                itemView.title.text = itemView.context.getString(R.string.processing_error_title)
                itemView.subtitle.visibility = View.GONE
                itemView.icon.setImageResource(R.drawable.ic_error)
                itemView.retry_icon.visibility = View.VISIBLE
                itemView.retry_icon.setOnClickListener(onRetryClickListener)
                itemView.forward_arrow.visibility = View.GONE
            }
        } else {
            itemView.title.text = itemView.context.getString(R.string.processing_home_title)
            itemView.subtitle.visibility = View.VISIBLE
            itemView.icon.setImageResource(R.drawable.identity_processing)
            (itemView.icon.drawable as AnimationDrawable).start()
            if (blockchainIdentityData.creationState == CreationState.DONE) {
                itemView.icon.visibility = View.GONE
            } else {
                itemView.icon.visibility = View.VISIBLE
            }
            itemView.retry_icon.visibility = View.GONE
            itemView.forward_arrow.visibility = View.GONE
        }

        when (blockchainIdentityData.creationState) {
            CreationState.NONE,
            CreationState.UPGRADING_WALLET,
            CreationState.CREDIT_FUNDING_TX_CREATING,
            CreationState.CREDIT_FUNDING_TX_SENDING,
            CreationState.CREDIT_FUNDING_TX_SENT,
            CreationState.CREDIT_FUNDING_TX_CONFIRMED -> {
                itemView.progress.visibility = View.VISIBLE
                itemView.progress.progress = 25
                itemView.subtitle.setText(R.string.processing_home_step_1)
            }
            CreationState.IDENTITY_REGISTERING,
            CreationState.IDENTITY_REGISTERED -> {
                itemView.progress.progress = 50
                itemView.subtitle.setText(R.string.processing_home_step_2)
            }
            CreationState.PREORDER_REGISTERING,
            CreationState.PREORDER_REGISTERED,
            CreationState.USERNAME_REGISTERING,
            CreationState.USERNAME_REGISTERED,
            CreationState.DASHPAY_PROFILE_CREATING,
            CreationState.DASHPAY_PROFILE_CREATED -> {
                itemView.progress.progress = 75
                itemView.subtitle.setText(
                        if (blockchainIdentityData.creationStateErrorMessage != null) R.string.processing_username_unavailable_subtitle
                        else R.string.processing_home_step_3
                )
            }
            CreationState.DONE -> {
                itemView.icon.visibility = View.GONE
                itemView.forward_arrow.visibility = View.VISIBLE
                itemView.progress.visibility = View.GONE
                itemView.title.text = itemView.context.getString(R.string.processing_done_title,
                        blockchainIdentityData.username)
                itemView.subtitle.setText(R.string.processing_done_subtitle)
            }
        }
    }
}