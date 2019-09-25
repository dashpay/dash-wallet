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
import androidx.fragment.app.Fragment
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_payments_pay.*
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.bitcoinj.core.VersionedChecksummedBytes

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
            handleScan()
        })
        pay_to_address.setOnButtonClickListener(View.OnClickListener {
            handlePaste(false)
        })
    }

    override fun onResume() {
        super.onResume()
        handlePaste(true)
    }

    fun handleScan() {
        startActivityForResult(Intent(context, ScanActivity::class.java), REQUEST_CODE_SCAN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {
        if (requestCode == REQUEST_CODE_SCAN && resultCode == Activity.RESULT_OK) {
            val input = intent!!.getStringExtra(ScanActivity.INTENT_EXTRA_RESULT)
            handleString(input, false, R.string.button_scan)
        } else {
            super.onActivityResult(requestCode, resultCode, intent)
        }
    }

    private fun handlePaste(activateButtonOnly: Boolean) {
        var input: String? = null
        val clipboardManager = context!!.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboardManager.hasPrimaryClip()) {
            val clip = clipboardManager.primaryClip ?: return
            val clipDescription = clip.description
            if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) {
                val clipUri = clip.getItemAt(0).uri
                if (clipUri != null) {
                    input = clipUri.toString()
                }
            } else if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                val clipText = clip.getItemAt(0).text
                if (clipText != null) {
                    input = clipText.toString()
                }
            }
        }
        if (input != null) {
            handleString(input, activateButtonOnly, R.string.payments_pay_to_clipboard_title)
        } else {
            InputParser.dialog(context, null, R.string.payments_pay_to_clipboard_title, R.string.scan_to_pay_error_dialog_message_no_data)
        }
    }

    private fun handleString(input: String, noAction: Boolean, errorDialogTitleResId: Int) {
        object : InputParser.StringInputParser(input) {
            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                if (noAction) {
                    activatePayToAddress(paymentIntent)
                } else {
                    SendCoinsActivity.start(context, paymentIntent)
                }
            }

            override fun handlePrivateKey(key: VersionedChecksummedBytes) {

            }

            @Throws(VerificationException::class)
            override fun handleDirectTransaction(tx: Transaction) {

            }

            override fun error(messageResId: Int, vararg messageArgs: Any) {
                if (noAction) {
                    activatePayToAddress(null)
                } else {
                    dialog(context, null, errorDialogTitleResId, messageResId, *messageArgs)
                }
            }

            override fun cannotClassify(input: String) {
                error(R.string.input_parser_cannot_classify, input)
            }
        }.parse()
    }

    private fun activatePayToAddress(paymentIntent: PaymentIntent?) {
        pay_to_address.setActive(paymentIntent != null)
        if (paymentIntent == null) {
            pay_to_address.setSubTitle(R.string.payments_pay_to_clipboard_sub_title)
        } else {
            pay_to_address.setSubTitle(paymentIntent.address.toBase58())
        }
    }
}
