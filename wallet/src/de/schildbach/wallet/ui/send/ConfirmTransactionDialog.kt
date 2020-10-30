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

package de.schildbach.wallet.ui.send


import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceManager
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import de.schildbach.wallet.ui.BaseBottomSheetDialogFragment
import de.schildbach.wallet.ui.SingleActionSharedViewModel
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.dialog_confirm_transaction.*


class ConfirmTransactionDialog : BaseBottomSheetDialogFragment() {

    companion object {

        private const val ARG_ADDRESS = "arg_address"
        private const val ARG_AMOUNT = "arg_amount"
        private const val ARG_AMOUNT_FIAT = "arg_amount_fiat"
        private const val ARG_FIAT_SYMBOL = "arg_fiat_symbol"
        private const val ARG_FEE = "arg_fee"
        private const val ARG_TOTAL = "arg_total"
        private const val ARG_BUTTON_TEXT = "arg_button_text"
        private const val ARG_PAYEE_NAME = "arg_payee_name"
        private const val ARG_PAYEE_VERIFIED_BY = "arg_payee_verified_by"
        private const val ARG_PAYEE_USERNAME = "arg_payee_username"
        private const val ARG_PAYEE_DISPLAYNAME = "arg_payee_displayname"
        private const val ARG_PAYEE_AVATAR_URL = "arg_payee_avatar_url"
        private const val ARG_PAYEE_PENDING_CONTACT_REQUEST = "arg_payee_contact_request"

        @JvmStatic
        fun createDialog(address: String, amount: String, amountFiat: String, fiatSymbol: String, fee: String, total: String,
                         payeeName: String? = null, payeeVerifiedBy: String? = null, buttonText: String? = null,
                         username: String? = null, displayName: String? = null, avatarUrl: String? = null,
                         pendingContactRequest: Boolean = false): DialogFragment {
            val dialog = ConfirmTransactionDialog()
            val bundle = Bundle()
            bundle.putString(ARG_ADDRESS, address)
            bundle.putString(ARG_AMOUNT, amount)
            bundle.putString(ARG_AMOUNT_FIAT, amountFiat)
            bundle.putString(ARG_FIAT_SYMBOL, fiatSymbol)
            bundle.putString(ARG_FEE, fee)
            bundle.putString(ARG_TOTAL, total)
            bundle.putString(ARG_PAYEE_NAME, payeeName)
            bundle.putString(ARG_PAYEE_VERIFIED_BY, payeeVerifiedBy)
            bundle.putString(ARG_BUTTON_TEXT, buttonText)
            if (displayName != null) {
                bundle.putString(ARG_PAYEE_DISPLAYNAME, displayName)
                bundle.putString(ARG_PAYEE_AVATAR_URL, avatarUrl)
                bundle.putString(ARG_PAYEE_USERNAME, username)
            }
            bundle.putBoolean(ARG_PAYEE_PENDING_CONTACT_REQUEST, pendingContactRequest)
            dialog.arguments = bundle
            return dialog
        }
    }

    private lateinit var sharedViewModel: SingleActionSharedViewModel

    private val autoAcceptPrefsKey by lazy {
        "auto_accept:$username"
    }

    private val prefs: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(activity)
    }

    private val username by lazy {
        arguments!!.getString(ARG_PAYEE_USERNAME)
    }

    private val pendingContactRequest by lazy {
        arguments!!.getBoolean(ARG_PAYEE_PENDING_CONTACT_REQUEST, false)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.dialog_confirm_transaction, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        maybeCleanUpPrefs()
        arguments!!.apply {
            input_value.text = getString(ARG_AMOUNT)
            fiat_symbol.text = getString(ARG_FIAT_SYMBOL)
            fiat_value.text = getString(ARG_AMOUNT_FIAT)
            transaction_fee.text = getString(ARG_FEE)
            total_amount.text = getString(ARG_TOTAL)
            val displayNameText = getString(ARG_PAYEE_DISPLAYNAME)
            val avatarUrl = getString(ARG_PAYEE_AVATAR_URL)
            val payeeName = getString(ARG_PAYEE_NAME)
            val payeeVerifiedBy = getString(ARG_PAYEE_VERIFIED_BY)
            if (payeeName != null && payeeVerifiedBy != null) {
                sendtouser.visibility = View.GONE
                confirm_auto_accept.visibility = View.GONE
                address.text = payeeName
                payee_secured_by.text = payeeVerifiedBy
                payee_verified_by_pane.visibility = View.VISIBLE
                val forceMarqueeOnClickListener = View.OnClickListener {
                    it.isSelected = false
                    it.isSelected = true
                }
                address.setOnClickListener(forceMarqueeOnClickListener)
                payee_secured_by.setOnClickListener(forceMarqueeOnClickListener)
            } else if (displayNameText != null) {
                sendtoaddress.visibility = View.GONE
                displayname.text = displayNameText

                ProfilePictureDisplay.display(avatar, avatarUrl!!, username!!)
                confirm_auto_accept.isChecked = autoAcceptLastValue
                if (pendingContactRequest) {
                    confirm_auto_accept.visibility = View.VISIBLE
                } else {
                    confirm_auto_accept.visibility = View.GONE
                }
            } else {
                sendtouser.visibility = View.GONE
                confirm_auto_accept.visibility = View.GONE
                address.ellipsize = TextUtils.TruncateAt.MIDDLE
                address.text = getString(ARG_ADDRESS)
            }
            getString(ARG_BUTTON_TEXT)?.run {
                confirm_payment.text = this
            }
        }
        collapse_button.setOnClickListener {
            dismiss()
        }
        confirm_payment.setOnClickListener {
            dismiss()
            autoAcceptLastValue = confirm_auto_accept.isChecked
            sharedViewModel.autoAcceptContactRequest = pendingContactRequest && confirm_auto_accept.isChecked
            sharedViewModel.clickConfirmButtonEvent.call(true)
        }
        dialog?.setOnShowListener { dialog ->
            // apply wrap_content height
            val d = dialog as BottomSheetDialog
            val bottomSheet = d.findViewById<FrameLayout>(R.id.design_bottom_sheet)
            val coordinatorLayout = bottomSheet!!.parent as CoordinatorLayout
            val bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
            bottomSheetBehavior.peekHeight = bottomSheet.height
            coordinatorLayout.parent.requestLayout()
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        sharedViewModel = activity?.run {
            ViewModelProvider(this)[SingleActionSharedViewModel::class.java]
        } ?: throw IllegalStateException("Invalid Activity")
    }

    private var autoAcceptLastValue: Boolean
        get() = if (username != null) {
            prefs.getBoolean(autoAcceptPrefsKey, true)
        } else {
            true
        }
        set(value) {
            prefs.edit().putBoolean(autoAcceptPrefsKey, value).apply()
        }

    private fun maybeCleanUpPrefs() {
        if (username != null && !pendingContactRequest && prefs.contains(autoAcceptPrefsKey)) {
            prefs.edit().remove(autoAcceptPrefsKey).apply()
        }
    }
}
