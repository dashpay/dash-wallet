package de.schildbach.wallet.ui.dashpay

import android.graphics.drawable.AnimationDrawable
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.data.IdentityCreationState
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.identity_creation_state.view.*

class ProcessingIdentityViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    fun bind(identityCreationState: IdentityCreationState) {
        if (identityCreationState.error) {
            if (identityCreationState.state == IdentityCreationState.State.USERNAME_REGISTERING) {
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
                itemView.forward_arrow.visibility = View.GONE
            }
        } else {
            itemView.title.text = itemView.context.getString(R.string.processing_home_title)
            itemView.subtitle.visibility = View.VISIBLE
            itemView.icon.setImageResource(R.drawable.identity_processing)
            (itemView.icon.drawable as AnimationDrawable).start()
            if (identityCreationState.state == IdentityCreationState.State.USERNAME_REGISTERED) {
                itemView.icon.visibility = View.GONE
            } else {
                itemView.icon.visibility = View.VISIBLE
            }
            itemView.retry_icon.visibility = View.GONE
            itemView.forward_arrow.visibility = View.GONE
        }

        when (identityCreationState.state) {
            IdentityCreationState.State.UPGRADING_WALLET,
            IdentityCreationState.State.CREDIT_FUNDING_TX_CREATING,
            IdentityCreationState.State.CREDIT_FUNDING_TX_SENDING,
            IdentityCreationState.State.CREDIT_FUNDING_TX_SENT,
            IdentityCreationState.State.CREDIT_FUNDING_TX_CONFIRMED -> {
                itemView.progress.visibility = View.VISIBLE
                itemView.progress.progress = 20
                itemView.subtitle.setText(R.string.processing_home_step_1)
            }
            IdentityCreationState.State.IDENTITY_REGISTERING,
            IdentityCreationState.State.IDENTITY_REGISTERED -> {
                itemView.progress.progress = 40
                itemView.subtitle.setText(R.string.processing_home_step_2)
            }
            IdentityCreationState.State.PREORDER_REGISTERING,
            IdentityCreationState.State.PREORDER_REGISTERED,
            IdentityCreationState.State.USERNAME_REGISTERING -> {
                itemView.progress.progress = 60
                itemView.subtitle.setText(
                        if (identityCreationState.error) R.string.processing_username_unavailable_subtitle
                        else R.string.processing_home_step_3
                )
            }
            IdentityCreationState.State.DASHPAY_PROFILE_CREATING -> {
                itemView.progress.progress = 80
                itemView.subtitle.setText(R.string.processing_home_step_4)
            }
            IdentityCreationState.State.DASHPAY_PROFILE_CREATED -> {
                itemView.icon.visibility = View.GONE
                itemView.forward_arrow.visibility = View.VISIBLE
                itemView.progress.visibility = View.GONE
                itemView.title.text = itemView.context.getString(R.string.processing_done_title,
                        identityCreationState.username)
                itemView.subtitle.setText(R.string.processing_done_subtitle)
            }
        }
    }
}