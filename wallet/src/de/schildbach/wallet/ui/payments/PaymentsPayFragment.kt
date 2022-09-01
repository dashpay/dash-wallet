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

package de.schildbach.wallet.ui.payments

import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.os.bundleOf
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.ui.InputParser
import de.schildbach.wallet.ui.scan.ScanActivity
import de.schildbach.wallet.ui.send.SendCoinsInternalActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_payments_pay.*
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.FirebaseAnalyticsServiceImpl
import org.dash.wallet.common.ui.BaseLockScreenFragment

class PaymentsPayFragment : BaseLockScreenFragment() {

    companion object {

        private const val REQUEST_CODE_SCAN = 0

        @JvmStatic
        fun newInstance() = PaymentsPayFragment()
    }

    private val analytics = FirebaseAnalyticsServiceImpl.getInstance()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_payments_pay, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        //Make the whole row clickable
        pay_by_qr_button.setOnClickListener {
            handleScan(it)
            analytics.logEvent(AnalyticsConstants.SendReceive.SCAN_TO_SEND, bundleOf())
        }
        pay_to_address.setOnClickListener {
            handlePaste(true)
            analytics.logEvent(AnalyticsConstants.SendReceive.SEND_TO_ADDRESS, bundleOf())
        }
        handlePaste(false)
    }

    override fun onResume() {
        super.onResume()
        requireView().viewTreeObserver?.addOnWindowFocusChangeListener(onWindowFocusChangeListener)
        getClipboardManager().addPrimaryClipChangedListener(onPrimaryClipChangedListener)
    }

    override fun onPause() {
        super.onPause()
        requireView().viewTreeObserver?.removeOnWindowFocusChangeListener(onWindowFocusChangeListener)
        getClipboardManager().removePrimaryClipChangedListener(onPrimaryClipChangedListener)
    }

    private fun getClipboardManager(): ClipboardManager {
        return context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }

    private val onWindowFocusChangeListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (hasFocus) {
            handlePaste(false)
        }
    }

    private val onPrimaryClipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        handlePaste(false)
    }

    private fun handleScan(clickView: View) {
        ScanActivity.startForResult(this, activity, REQUEST_CODE_SCAN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode == REQUEST_CODE_SCAN && resultCode == Activity.RESULT_OK) {
            val input = intent?.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
            input?.let { handleString(it, true, R.string.button_scan) }
        } else {
            super.onActivityResult(requestCode, resultCode, intent)
        }
    }

    private fun handlePaste(fireAction: Boolean) {
        val input = clipboardData()
        if (input != null) {
            handleString(input, fireAction, R.string.payments_pay_to_clipboard_title)
        }
    }

    private fun clipboardData(): String? {
        val clipboardManager = getClipboardManager()
        if (clipboardManager.hasPrimaryClip()) {
            clipboardManager.primaryClip?.run {
                return when {
                    description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST) -> getItemAt(0).uri?.toString()
                    description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) -> getItemAt(0).text?.toString()
                    description.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML) -> getItemAt(0).text?.toString()
                    else -> null
                }
            }
        }
        return null
    }

    private fun handleString(input: String, fireAction: Boolean, errorDialogTitleResId: Int) {
        object : InputParser.StringInputParser(input, true) {

            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                if (fireAction) {
                    SendCoinsInternalActivity.start(context, paymentIntent, true)
                } else {
                    manageStateOfPayToAddressButton(paymentIntent)
                }
            }

            override fun error(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                if (fireAction) {
                    alertDialog = baseAlertDialogBuilder.apply {
                        title = getString(errorDialogTitleResId)
                        message = getString(messageResId, *messageArgs)
                        neutralText = getString(R.string.button_dismiss)
                    }.buildAlertDialog()
                    alertDialog.show()
                } else {
                    manageStateOfPayToAddressButton(null)
                }
            }

            override fun handlePrivateKey(key: PrefixedChecksummedBytes) {
                // ignore
            }

            @Throws(VerificationException::class)
            override fun handleDirectTransaction(tx: Transaction) {
                // ignore
            }
        }.parse()
    }

    private fun manageStateOfPayToAddressButton(paymentIntent: PaymentIntent?) {
        if (paymentIntent != null) {
            when {
                paymentIntent.hasAddress() -> {
                    pay_to_address.setActive(true)
                    pay_to_address.setSubTitle(paymentIntent.address.toBase58())
                    return
                }
                paymentIntent.hasPaymentRequestUrl() -> {
                    val host = Uri.parse(paymentIntent.paymentRequestUrl).host
                    if (host != null) {
                        pay_to_address.setActive(true)
                        pay_to_address.setSubTitle(host)
                        return
                    }
                }
            }
        }
        pay_to_address.setActive(false)
        pay_to_address.setSubTitle(R.string.payments_pay_to_clipboard_sub_title)
    }
}
