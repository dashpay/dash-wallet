package de.schildbach.wallet.ui.dashpay

import android.graphics.drawable.AnimationDrawable
import android.view.View
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import de.schildbach.wallet.data.IdentityCreationState
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.identity_creation_state.view.*

class ProcessingIdentityViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

    init {
        (itemView.animated_icon.drawable as AnimationDrawable).start()
    }

    fun bind(identityCreationState: IdentityCreationState) {
        if (identityCreationState.error) {
            itemView.title.text = itemView.context.getString(R.string.processing_error_title)
            itemView.title.setTextColor(ResourcesCompat.getColor(itemView.resources, R.color.dash_red, null))
            itemView.animated_icon.visibility = View.GONE
            itemView.error_icon.visibility = View.VISIBLE
            itemView.retry_icon.visibility = View.VISIBLE
        } else {
            itemView.title.setTextColor(ResourcesCompat.getColor(itemView.resources, R.color.dash_blue, null))
            itemView.title.text = itemView.context.getString(R.string.processing_home_title)
            itemView.retry_icon.visibility = View.GONE
            if (identityCreationState.state == IdentityCreationState.State.PROCESSING_PAYMENT) {
                itemView.animated_icon.visibility = View.VISIBLE
                itemView.error_icon.visibility = View.INVISIBLE
            } else {
                itemView.animated_icon.visibility = View.GONE
                itemView.error_icon.visibility = View.GONE
            }
        }

        when (identityCreationState.state) {
            IdentityCreationState.State.PROCESSING_PAYMENT -> {
                itemView.forward_arrow.visibility = View.GONE
                itemView.progress.progress = 25
                itemView.subtitle.setText(
                        if (identityCreationState.error) R.string.processing_error_step_1
                        else R.string.processing_home_step_1
                )
            }
            IdentityCreationState.State.CREATING_IDENTITY -> {
                itemView.progress.progress = 50
                itemView.subtitle.setText(
                        if (identityCreationState.error) R.string.processing_error_step_2
                        else R.string.processing_home_step_2
                )
            }
            IdentityCreationState.State.REGISTERING_USERNAME -> {
                itemView.progress.progress = 75
                itemView.subtitle.setText(
                        if (identityCreationState.error) R.string.processing_error_step_3
                        else R.string.processing_home_step_3
                )
            }
            IdentityCreationState.State.DONE -> {
                itemView.forward_arrow.visibility = View.VISIBLE
                itemView.progress.progress = 100
                itemView.title.text = itemView.context.getString(R.string.processing_done_title,
                        identityCreationState.username)
                itemView.subtitle.setText(R.string.processing_done_subtitle)
            }
        }
    }
}