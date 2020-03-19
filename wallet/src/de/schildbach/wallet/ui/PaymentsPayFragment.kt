package de.schildbach.wallet.ui

import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.fragment.app.Fragment
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.ui.scan.ScanActivity
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_payments_pay.*
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException

class PaymentsPayFragment : Fragment() {

    companion object {

        private const val REQUEST_CODE_SCAN = 0

        @JvmStatic
        fun newInstance() = PaymentsPayFragment()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_payments_pay, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pay_by_qr_button.setOnButtonClickListener(View.OnClickListener {
            handleScan(it)
        })
        pay_to_address.setOnButtonClickListener(View.OnClickListener {
            handlePaste(true)
        })
        //Make the whole row clickable
        pay_by_qr_button.setOnClickListener { handleScan(it) }
        pay_to_address.setOnClickListener { handlePaste(true) }
        handlePaste(false)
    }

    override fun onResume() {
        super.onResume()
        view!!.viewTreeObserver?.addOnWindowFocusChangeListener(onWindowFocusChangeListener)
        getClipboardManager().addPrimaryClipChangedListener(onPrimaryClipChangedListener)
    }

    override fun onPause() {
        super.onPause()
        view!!.viewTreeObserver?.removeOnWindowFocusChangeListener(onWindowFocusChangeListener)
        getClipboardManager().removePrimaryClipChangedListener(onPrimaryClipChangedListener)
    }

    private fun getClipboardManager(): ClipboardManager {
        return context?.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;
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
            val input = intent!!.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
            handleString(input, true, R.string.button_scan)
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
                    else -> null
                }
            }
        }
        return null
    }

    private fun handleString(input: String, fireAction: Boolean, errorDialogTitleResId: Int) {
        object : InputParser.StringInputParser(input) {

            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                if (fireAction) {
                    SendCoinsActivity.start(context, paymentIntent, true)
                } else {
                    manageStateOfPayToAddressButton(paymentIntent)
                }
            }

            override fun error(messageResId: Int, vararg messageArgs: Any) {
                if (fireAction) {
                    dialog(context, null, errorDialogTitleResId, messageResId, *messageArgs)
                } else {
                    manageStateOfPayToAddressButton(null)
                }
            }

            override fun handlePrivateKey(key: PrefixedChecksummedBytes) {

            }

            @Throws(VerificationException::class)
            override fun handleDirectTransaction(tx: Transaction) {

            }
        }.parse()
    }

    private fun manageStateOfPayToAddressButton(paymentIntent: PaymentIntent?) {
        pay_to_address.setActive(paymentIntent != null)
        if (paymentIntent == null) {
            pay_to_address.setSubTitle(R.string.payments_pay_to_clipboard_sub_title)
        } else {
            pay_to_address.setSubTitle(paymentIntent.address.toBase58())
        }
    }
}
