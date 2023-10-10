/*
 * Copyright 2019 Dash Core Group
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.schildbach.wallet.ui.dashpay.widget

import android.content.Context
import android.graphics.drawable.AnimationDrawable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.HtmlCompat
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ContactRequestViewBinding


class ContactRequestPane(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {
    private var binding = ContactRequestViewBinding
        .inflate(LayoutInflater.from(context), this)

    init {
        orientation = VERTICAL
        setBackgroundColor(ResourcesCompat.getColor(resources, R.color.background_primary, null))
        applySendState()

        binding.payButton.setOnClickListener {
            onUserActionListener?.onPayClick()
        }
        binding.mainButton.setOnClickListener {
            applySendingState()
            onUserActionListener?.onSendContactRequestClick()
        }
        binding.disclaimerButton.setOnClickListener {
            applyDisclaimerSendingState()
            onUserActionListener?.onSendContactRequestClick()
        }
        binding.accept.setOnClickListener {
            applyAcceptingState()
            onUserActionListener?.onAcceptClick()
        }
        binding.ignore.setOnClickListener {
            onUserActionListener?.onIgnoreClick()
        }
        binding.contactHistoryDisclaimer.visibility = View.GONE
    }

    fun applySendState() {
        binding.contactHistoryDisclaimer.visibility = View.GONE
        binding.mainButton.visibility = View.VISIBLE
        binding.payButtonPane.visibility = View.GONE
        binding.contactRequestReceivedPane.visibility = View.GONE
        binding.mainButton.isClickable = true
        // this was originally Blue
        // binding.mainButton.setBackgroundResource(R.drawable.secondary_inverted_button)
        binding.mainButtonIcon.setImageResource(R.drawable.ic_add_contact_white)
        binding.mainButtonText.setText(R.string.send_contact_request)
        binding.mainButtonText.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_white, null))
    }

    fun applySendStateWithDisclaimer(username: String) {
        applySendState()
        binding.contactHistoryDisclaimer.visibility = View.VISIBLE
        binding.disclaimerButton.visibility = View.GONE
        var disclaimerText = resources.getString(R.string.contact_history_disclaimer)
        disclaimerText = disclaimerText.replace("%", username)
        binding.contactHistoryDisclaimerText.text = HtmlCompat.fromHtml(disclaimerText, HtmlCompat.FROM_HTML_MODE_COMPACT)
    }

    fun applySendingState() {
        binding.contactHistoryDisclaimer.visibility = View.GONE
        binding.mainButton.visibility = View.VISIBLE
        binding.payButtonPane.visibility = View.GONE
        binding.contactRequestReceivedPane.visibility = View.GONE
        binding.mainButton.isClickable = false
        binding.mainButton.setBackgroundResource(R.drawable.light_gray_button_background)
        binding.mainButtonIcon.setImageResource(R.drawable.ic_hourglass)
        (binding.mainButtonIcon.drawable as AnimationDrawable).start()
        binding.mainButtonText.setText(R.string.sending_contact_request)
        binding.mainButtonText.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_medium_gray, null))
    }

    fun applySendingStateWithDisclaimer(username: String?) {
        applySendingState()
        binding.contactHistoryDisclaimer.visibility = View.VISIBLE
        username?.also {
            var disclaimerText = resources.getString(R.string.contact_history_disclaimer_pending)
            disclaimerText = disclaimerText.replace("%", it)
            binding.contactHistoryDisclaimerText.text = HtmlCompat.fromHtml(disclaimerText, HtmlCompat.FROM_HTML_MODE_COMPACT)
        }
    }

    fun applySentState() {
        binding.contactHistoryDisclaimer.visibility = View.GONE
        binding.mainButton.visibility = View.VISIBLE
        binding.payButtonPane.visibility = View.GONE
        binding.contactRequestReceivedPane.visibility = View.GONE
        binding.mainButton.isClickable = false
        binding.mainButton.setBackgroundResource(R.drawable.light_gray_button_background)
        binding.mainButtonIcon.setImageResource(R.drawable.ic_pending_contact_request)
        binding.mainButtonText.setText(R.string.contact_request_pending)
        binding.mainButtonText.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_gray, null))
    }

    fun applySentStateWithDisclaimer(username: String) {
        applySentState()
        binding.contactHistoryDisclaimer.visibility = View.VISIBLE
        binding.disclaimerButton.visibility = View.GONE
        var disclaimerText = resources.getString(R.string.contact_history_disclaimer_pending)
        disclaimerText = disclaimerText.replace("%", username)
        binding.contactHistoryDisclaimerText.text = HtmlCompat.fromHtml(disclaimerText, HtmlCompat.FROM_HTML_MODE_COMPACT)
    }

    fun applyReceivedState(username: String) {
        binding.contactHistoryDisclaimer.visibility = View.GONE
        binding.mainButton.visibility = View.GONE
        binding.payButtonPane.visibility = View.VISIBLE
        binding.contactRequestReceivedPane.visibility = View.VISIBLE
        binding.requestReceivedPaneTitle.text = resources.getString(R.string.contact_request_received_title, username)
    }

    fun applyAcceptingState() {
        binding.contactHistoryDisclaimer.visibility = View.GONE
        binding.contactRequestReceivedPane.visibility = View.GONE
        applySendingState()
        binding.mainButtonText.setText(R.string.accepting_contact_request)
    }

    fun applyFriendsState() {
        binding.mainButton.visibility = View.GONE
        binding.payButtonPane.visibility = View.VISIBLE
        binding.contactRequestReceivedPane.visibility = View.GONE
    }

    fun applyDisclaimerState(username: String?) {
        binding.mainButton.visibility = View.GONE
        binding.payButtonPane.visibility = View.GONE
        binding.contactRequestReceivedPane.visibility = View.GONE
        binding.contactHistoryDisclaimer.visibility = View.VISIBLE
        binding.disclaimerButton.visibility = View.VISIBLE
        binding.disclaimerButton.isClickable = true
        binding.disclaimerButton.setBackgroundResource(R.drawable.blue_outline_button_bg)
        binding.disclaimerButtonText.setText(R.string.send_contact_request)
        binding.disclaimerButtonIcon.setImageResource(R.drawable.inverted_contact_icon)
        username?.also {
            var disclaimerText = resources.getString(R.string.contact_history_disclaimer)
            disclaimerText = disclaimerText.replace("%", it)
            binding.contactHistoryDisclaimerText.text =
                HtmlCompat.fromHtml(disclaimerText, HtmlCompat.FROM_HTML_MODE_COMPACT)
        }
    }

    fun applyDisclaimerSendingState() {
        applyDisclaimerState(null)
        binding.disclaimerButton.isClickable = false
        binding.disclaimerButtonText.setText(R.string.sending_contact_request)
        binding.disclaimerButton.setBackgroundResource(R.drawable.light_gray_button_background)
        binding.disclaimerButtonIcon.setImageResource(R.drawable.ic_hourglass)
        (binding.disclaimerButtonIcon.drawable as AnimationDrawable).start()
    }

    fun applyNetworkErrorState(isNetworkError: Boolean) {
        binding.mainButton.isEnabled = !isNetworkError
        binding.accept.isEnabled = !isNetworkError
    }

    private var onUserActionListener: OnUserActionListener? = null

    fun setOnUserActionListener(listener: OnUserActionListener) {
        onUserActionListener = listener
    }

    fun performAcceptClick() {
        binding.accept.post {
            binding.accept.performClick()
        }
    }

    interface OnUserActionListener {
        fun onSendContactRequestClick()
        fun onAcceptClick()
        fun onIgnoreClick()
        fun onPayClick()
    }
}
