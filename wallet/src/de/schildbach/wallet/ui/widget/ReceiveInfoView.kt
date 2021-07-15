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

package de.schildbach.wallet.ui.widget

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.text.SpannableString
import android.text.style.ImageSpan
import android.util.AttributeSet
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.lifecycleOwner
import de.schildbach.wallet.ui.ReceiveActivity
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.ui.dashpay.utils.ProfilePictureDisplay
import de.schildbach.wallet.util.Qr
import de.schildbach.wallet.util.Toast
import de.schildbach.wallet_test.R
import kotlinx.android.synthetic.main.receive_info_view.view.*
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.uri.BitcoinURI
import org.bitcoinj.uri.BitcoinURIParseException
import org.dash.wallet.common.Configuration
import org.dashj.platform.dashpay.BlockchainIdentity
import org.slf4j.LoggerFactory


class ReceiveInfoView(context: Context, attrs: AttributeSet?) : ConstraintLayout(context, attrs) {

    private val log = LoggerFactory.getLogger(ReceiveInfoView::class.java)

    private var config: Configuration? = null
    private var blockchainIdentity: BlockchainIdentity? = null

    private lateinit var address: Address
    var amount: Coin? = null
        set(value) {
            field = value
            refresh()
        }

    private lateinit var paymentRequestUri: String
    private lateinit var qrCodeBitmap: BitmapDrawable

    init {
        inflate(context, R.layout.receive_info_view, this)

        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.ReceiveInfoView)
        try {
            val showAmountAction = attrsArray.getBoolean(R.styleable.ReceiveInfoView_ri_show_amount_action, true)
            specify_amount_button.visibility = if (showAmountAction) View.VISIBLE else View.GONE
            val showShareAction = attrsArray.getBoolean(R.styleable.ReceiveInfoView_ri_show_share_action, true)
            share_button.visibility = if (showShareAction) View.VISIBLE else View.GONE
        } finally {
            attrsArray.recycle()
        }

        if (!isInEditMode) {
            val walletApplication = context.applicationContext as WalletApplication
            config = walletApplication.configuration
            blockchainIdentity = PlatformRepo.getInstance().getBlockchainIdentity()

            address_preview_pane.setOnClickListener {
                handleCopyAddress()
            }
            specify_amount_button.setOnClickListener {
                handleSpecifyAmount()
            }
            share_button.setOnClickListener {
                handleShare()
            }
            share_button2.setOnClickListener {
                handleShare()
            }

            refresh()
            setupUser()
        }
    }

    private fun refresh() {
        refreshData()

        qrCodeBitmap = BitmapDrawable(resources, Qr.bitmap(paymentRequestUri))
        qrCodeBitmap.isFilterBitmap = false

        qr_preview.setImageDrawable(qrCodeBitmap)
        address_preview.text = address.toBase58() + "  "

        val addressSpannable = SpannableString.valueOf(address_preview.text)
        val copyIconDrawable = ContextCompat.getDrawable(context, R.drawable.ic_copy_addres)!!
        val iconSize = address_preview.lineHeight
        copyIconDrawable.setBounds(0, 0, iconSize, iconSize)
        val imageSpan = ImageSpan(copyIconDrawable, ImageSpan.ALIGN_BOTTOM)
        addressSpannable.setSpan(imageSpan, addressSpannable.length - 1, addressSpannable.length, 0)
        address_preview.text = addressSpannable
    }

    private fun setupUser() {
        if (blockchainIdentity != null && blockchainIdentity!!.currentUsername != null) {
            avatar_container.visibility = View.VISIBLE
            qr_dash_logo.visibility = View.GONE
            username_1.visibility = View.VISIBLE
            username_2.visibility = View.VISIBLE
            val username = blockchainIdentity!!.currentUsername!!

            PlatformRepo.getInstance().loadProfileByUserId(blockchainIdentity!!.uniqueIdString)
                    .observe(context.lifecycleOwner()!!, Observer {
                        if (it !== null) {
                            val displayName = it.displayName
                            if (displayName.isNotEmpty()) {
                                username_1.text = displayName
                                username_2.text = username
                            } else {
                                username_1.text = username
                                username_2.visibility = View.GONE
                            }

                            ProfilePictureDisplay.display(avatar, it)
                        }
                    })

        } else {
            username_1.visibility = View.GONE
            username_2.visibility = View.GONE
        }
    }

    private fun refreshData() {
        val walletApplication = context.applicationContext as WalletApplication
        address = walletApplication.wallet.freshReceiveAddress()
        val ownName = config!!.ownName
        paymentRequestUri = BitcoinURI.convertToBitcoinURI(address.parameters, address.toString(),
                amount, ownName, null, blockchainIdentity?.currentUsername)
    }

    private fun handleCopyAddress() {
        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (amount != null) {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("Dash payment request", paymentRequestUri))
            } else {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("Dash address", address.toBase58()))
            }
            Toast(context).toast(R.string.receive_copied)
            log.info("address copied to clipboard: {}", address)
        } catch (ignore: BitcoinURIParseException) {

        }
    }

    private fun handleSpecifyAmount() {
        context.startActivity(Intent(context, ReceiveActivity::class.java))
    }

    private fun handleShare() {
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_TEXT, paymentRequestUri)
        context.startActivity(Intent.createChooser(intent, resources.getString(R.string.request_coins_share_dialog_title)))
        log.info("payment request shared via intent: {}", paymentRequestUri)
    }

    fun setupForDialog() {
        username_1.visibility = View.GONE
        username_2.visibility = View.GONE
        share_button.visibility = View.GONE
        share_button2.visibility = View.VISIBLE
    }
}
