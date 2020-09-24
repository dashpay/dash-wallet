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
import android.view.View
import android.widget.LinearLayout
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.HtmlCompat
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.contact_request_view.view.*


class ContactRequestPane(context: Context, attrs: AttributeSet) : LinearLayout(context, attrs) {

    init {
        inflate(context, R.layout.contact_request_view, this)
        orientation = VERTICAL
        setBackgroundColor(ResourcesCompat.getColor(resources, R.color.bg_less_bright, null))
        applySendState()

        pay_button.setOnClickListener {
            onUserActionListener?.onPayClick()
        }
        main_button.setOnClickListener {
            applySendingState()
            onUserActionListener?.onSendContactRequestClick()
        }
        disclaimer_button.setOnClickListener {
            applyDisclaimerSendingState()
            onUserActionListener?.onSendContactRequestClick()
        }
        accept.setOnClickListener {
            applyAcceptingState()
            onUserActionListener?.onAcceptClick()
        }
        ignore.setOnClickListener {
            onUserActionListener?.onIgnoreClick()
        }
        contact_history_disclaimer.visibility = View.GONE
    }

    fun applySendState() {
        contact_history_disclaimer.visibility = View.GONE
        main_button.visibility = View.VISIBLE
        pay_button_pane.visibility = View.GONE
        contact_request_received_pane.visibility = View.GONE
        main_button.isClickable = true
        main_button.setBackgroundResource(R.drawable.blue_button_background)
        main_button_icon.setImageResource(R.drawable.ic_add_contact_white)
        main_button_text.setText(R.string.send_contact_request)
        main_button_text.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_white, null))
    }

    private fun applySendingState() {
        contact_history_disclaimer.visibility = View.GONE
        main_button.visibility = View.VISIBLE
        pay_button_pane.visibility = View.GONE
        contact_request_received_pane.visibility = View.GONE
        main_button.isClickable = false
        main_button.setBackgroundResource(R.drawable.light_gray_button_background)
        main_button_icon.setImageResource(R.drawable.ic_hourglass)
        (main_button_icon.drawable as AnimationDrawable).start()
        main_button_text.setText(R.string.sending_contact_request)
        main_button_text.setTextColor(ResourcesCompat.getColor(resources, R.color.medium_gray, null))
    }

    fun applySentState() {
        contact_history_disclaimer.visibility = View.GONE
        main_button.visibility = View.VISIBLE
        pay_button_pane.visibility = View.GONE
        contact_request_received_pane.visibility = View.GONE
        main_button.isClickable = false
        main_button.background = null
        main_button_icon.setImageResource(R.drawable.ic_pending_contact_request)
        main_button_text.setText(R.string.contact_request_pending)
        main_button_text.setTextColor(ResourcesCompat.getColor(resources, R.color.dash_golden, null))
    }

    fun applySentStateWithDisclaimer(username: String) {
        applySentState()
        contact_history_disclaimer.visibility = View.VISIBLE
        disclaimer_button.visibility = View.GONE
        var disclaimerText = resources.getString(R.string.contact_history_disclaimer_pending)
        disclaimerText = disclaimerText.replace("%", username)
        contact_history_disclaimer_text.text = HtmlCompat.fromHtml(disclaimerText, HtmlCompat.FROM_HTML_MODE_COMPACT)
    }

    fun applyReceivedState(username: String) {
        contact_history_disclaimer.visibility = View.GONE
        main_button.visibility = View.GONE
        pay_button_pane.visibility = View.VISIBLE
        contact_request_received_pane.visibility = View.VISIBLE
        request_received_pane_title.text = resources.getString(R.string.contact_request_received_title, username)
    }

    private fun applyAcceptingState() {
        contact_history_disclaimer.visibility = View.GONE
        contact_request_received_pane.visibility = View.GONE
        applySendingState()
        main_button_text.setText(R.string.accepting_contact_request)
    }

    fun applyFriendsState() {
        main_button.visibility = View.GONE
        pay_button_pane.visibility = View.VISIBLE
        contact_request_received_pane.visibility = View.GONE
    }

    fun applyDisclaimerState(username: String?) {
        main_button.visibility = View.GONE
        pay_button_pane.visibility = View.GONE
        contact_request_received_pane.visibility = View.GONE
        contact_history_disclaimer.visibility = View.VISIBLE
        disclaimer_button.visibility = View.VISIBLE
        disclaimer_button.isClickable = true
        disclaimer_button.setBackgroundResource(R.drawable.blue_outline_button_bg)
        disclaimer_button_text.setText(R.string.send_contact_request)
        disclaimer_button_icon.setImageResource(R.drawable.inverted_contact_icon)
        username?.also {
            var disclaimerText = resources.getString(R.string.contact_history_disclaimer)
            disclaimerText = disclaimerText.replace("%", it)
            contact_history_disclaimer_text.text = HtmlCompat.fromHtml(disclaimerText, HtmlCompat.FROM_HTML_MODE_COMPACT)
        }
    }

    private fun applyDisclaimerSendingState() {
        applyDisclaimerState(null)
        disclaimer_button.isClickable = false
        disclaimer_button_text.setText(R.string.sending_contact_request)
        disclaimer_button.setBackgroundResource(R.drawable.light_gray_button_background)
        disclaimer_button_icon.setImageResource(R.drawable.ic_hourglass)
        (disclaimer_button_icon.drawable as AnimationDrawable).start()
    }

    private var onUserActionListener: OnUserActionListener? = null

    fun setOnUserActionListener(listener: OnUserActionListener) {
        onUserActionListener = listener
    }

    fun performAcceptClick() {
        accept.post {
            accept.performClick()
        }
    }

    interface OnUserActionListener {
        fun onSendContactRequestClick()
        fun onAcceptClick()
        fun onIgnoreClick()
        fun onPayClick()
    }
}
