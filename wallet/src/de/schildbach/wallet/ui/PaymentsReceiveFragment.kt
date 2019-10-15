package de.schildbach.wallet.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.util.Qr
import de.schildbach.wallet.util.Toast
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.fragment_payments_receive.*
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.uri.BitcoinURI
import org.bitcoinj.uri.BitcoinURIParseException
import org.dash.wallet.common.Configuration
import org.slf4j.LoggerFactory

class PaymentsReceiveFragment : Fragment() {

    companion object {
        @JvmStatic
        fun newInstance() = PaymentsReceiveFragment()
    }

    private lateinit var config: Configuration
    private lateinit var clipboardManager: ClipboardManager
    private lateinit var qrCodeBitmap: BitmapDrawable
    private lateinit var address: Address

    private val log = LoggerFactory.getLogger(PaymentsReceiveFragment::class.java)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_payments_receive, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        address_pane.setOnClickListener {
            handleCopyAddress()
        }
        share_button.setOnClickListener {
            handleShare()
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        val walletApplication = context.applicationContext as WalletApplication
        config = walletApplication.configuration
        clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        address = walletApplication.wallet.freshReceiveAddress()
    }

    override fun onResume() {
        super.onResume()
        updateView()
    }

    private fun updateView() {
        if (!isResumed) {
            return
        }

        val dashRequest = determineDashRequestStr()

        qrCodeBitmap = BitmapDrawable(resources, Qr.bitmap(dashRequest))
        qrCodeBitmap.isFilterBitmap = false

        qr_preview.setImageDrawable(qrCodeBitmap)
        address_view.text = address.toBase58()
    }

    private fun determineDashRequestStr(): String {
        val amount = Coin.parseCoin("1.234")
        val ownName = config.ownName
        val uri = StringBuilder(BitcoinURI.convertToBitcoinURI(address, amount, ownName, null))
        return uri.toString()
    }

    private fun handleCopyAddress() {
        try {
            val request = Uri.parse(determineDashRequestStr())
            clipboardManager.primaryClip = ClipData.newPlainText("Dash address", BitcoinURI(request.toString()).address!!.toString())
            log.info("address copied to clipboard: {}", request)
            Toast(activity).toast(R.string.request_coins_clipboard_address_msg)
        } catch (ignore: BitcoinURIParseException) {

        }
    }

    private fun handleShare() {
        val request = determineDashRequestStr()
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, request)
        startActivity(Intent.createChooser(intent, getString(R.string.request_coins_share_dialog_title)))
        log.info("payment request shared via intent: {}", request)
    }
}
