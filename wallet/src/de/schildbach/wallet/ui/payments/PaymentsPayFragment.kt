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
import android.view.View
import android.view.ViewTreeObserver
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.ui.util.InputParser
import de.schildbach.wallet.ui.scan.ScanActivity
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.FragmentPaymentsPayBinding
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.ui.viewBinding
import javax.inject.Inject

@AndroidEntryPoint
class PaymentsPayFragment : Fragment(R.layout.fragment_payments_pay) {

    companion object {

        private const val REQUEST_CODE_SCAN = 0

        @JvmStatic
        fun newInstance() = PaymentsPayFragment()
    }

    @Inject lateinit var analytics: AnalyticsService
    private val binding by viewBinding(FragmentPaymentsPayBinding::bind)

    private val onWindowFocusChangeListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
        if (hasFocus) {
            handlePaste(false)
        }
    }

    private val onPrimaryClipChangedListener = ClipboardManager.OnPrimaryClipChangedListener {
        handlePaste(false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Make the whole row clickable
        binding.payByQrButton.setOnClickListener {
            handleScan(it)
            analytics.logEvent(AnalyticsConstants.SendReceive.SCAN_TO_SEND, bundleOf())
        }
        binding.payToAddress.setOnClickListener {
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
                if (this@PaymentsPayFragment.isAdded) {
                    if (fireAction) {
                        SendCoinsActivity.start(requireContext(), paymentIntent)
                    } else {
                        manageStateOfPayToAddressButton(paymentIntent)
                    }
                }
            }

            override fun error(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                if (this@PaymentsPayFragment.isAdded) {
                    if (fireAction) {
                        AdaptiveDialog.create(
                            R.drawable.ic_error,
                            getString(errorDialogTitleResId),
                            getString(messageResId, *messageArgs),
                            getString(R.string.button_dismiss)
                        ).show(requireActivity())
                    } else {
                        manageStateOfPayToAddressButton(null)
                    }
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
        try {
            if (paymentIntent != null) {
                when {
                    paymentIntent.hasAddress() -> {
                        binding.payToAddress.setActive(true)
                        binding.payToAddress.setSubTitle(paymentIntent.address.toBase58())
                        return
                    }
                    paymentIntent.hasPaymentRequestUrl() -> {
                        val host = Uri.parse(paymentIntent.paymentRequestUrl).host
                        if (host != null) {
                            binding.payToAddress.setActive(true)
                            binding.payToAddress.setSubTitle(host)
                            return
                        }
                    }
                }
            }
            binding.payToAddress.setActive(false)
            binding.payToAddress.setSubTitle(R.string.payments_pay_to_clipboard_sub_title)
        } catch (ex: IllegalStateException) {
            // TODO: StringInputParser is triggering callbacks after the view is destroyed,
            // this should be fixed with some refactoring of the StringInputParser
        }
    }
}
