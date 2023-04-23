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

package org.dash.wallet.common.ui.receive

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.Toast
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.uri.BitcoinURI
import org.bitcoinj.uri.BitcoinURIParseException
import org.dash.wallet.common.R
import org.dash.wallet.common.databinding.ReceiveInfoViewBinding
import org.dash.wallet.common.ui.avatar.ProfilePictureDisplay
import org.dash.wallet.common.util.Qr
import org.dash.wallet.common.util.shareText
import org.slf4j.LoggerFactory


class ReceiveInfoView(context: Context, attrs: AttributeSet?) : ConstraintLayout(context, attrs) {
    private val log = LoggerFactory.getLogger(ReceiveInfoView::class.java)
    private val binding: ReceiveInfoViewBinding
    private var onAddressClicked: (() -> Unit)? = null
    private var onSpecifyAmountClicked: (() -> Unit)? = null
    private var onShareClicked: (() -> Unit)? = null

    private var address: Address? = null
    private var amount: Coin? = null
    private var paymentRequestUri: String = ""
    private var username: String? = null

    init {
        binding = ReceiveInfoViewBinding.inflate(LayoutInflater.from(context), this)
        val attrsArray = context.obtainStyledAttributes(attrs, R.styleable.ReceiveInfoView)

        try {
            val showAmountAction = attrsArray.getBoolean(R.styleable.ReceiveInfoView_ri_show_amount_action, true)
            binding.specifyAmountButton.isVisible = showAmountAction
            val showShareAction = attrsArray.getBoolean(R.styleable.ReceiveInfoView_ri_show_share_action, true)
            binding.shareButton.isVisible = showShareAction

            val qrPreviewScale = attrsArray.getFloat(R.styleable.ReceiveInfoView_ri_qr_code_scale, 1.0f)
            (binding.qrPreviewBg.layoutParams as LayoutParams).matchConstraintPercentWidth = qrPreviewScale
            (binding.qrDashLogo.layoutParams as LayoutParams).matchConstraintPercentWidth = (qrPreviewScale / 4.8f)
        } finally {
            attrsArray.recycle()
        }

        if (!isInEditMode) {
            binding.addressCopyBtn.setOnClickListener {
                onAddressClicked?.invoke()
                address?.let { handleCopyAddress(it) }
            }
            binding.usernameCopyBtn.setOnClickListener {
                username?.let { handleCopyUsername(it) }
            }
            binding.specifyAmountButton.setOnClickListener {
                onSpecifyAmountClicked?.invoke()
            }
            binding.shareButton.setOnClickListener {
                onShareClicked?.invoke()
                handleShare(paymentRequestUri)
            }

            refresh()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.qrPreviewBg.isForceDarkAllowed = false
        }
    }

    fun setInfo(address: Address, amount: Coin?) {
        if (this.address != address) {
            this.address = address
            this.amount = amount
            refresh()
        } else if (this.amount != amount) {
            this.amount = amount
            refreshQr()
        }
    }

    fun setProfile(username: String?, displayName: String?, avatar: String?, avatarHash: ByteArray?) {
        this.username = username

        if (!username.isNullOrEmpty()) {
            binding.usernamePreviewPane.isVisible = true
            binding.usernamePreview.text = username

            if (!displayName.isNullOrEmpty()) {
                binding.usernameLabel.text = displayName
            } else {
                binding.usernameLabel.text = context.getString(R.string.username)
            }

            if (!avatar.isNullOrEmpty()) {
                ProfilePictureDisplay.display(binding.avatar, avatar, avatarHash, username, false, null)
            }
        } else {
            binding.usernamePreviewPane.isVisible = false
        }
    }

    fun setOnSpecifyAmountClicked(listener: () -> Unit) {
        onSpecifyAmountClicked = listener
    }

    fun setOnAddressClicked(listener: () -> Unit) {
        onAddressClicked = listener
    }

    fun setOnShareClicked(listener: () -> Unit) {
        onShareClicked = listener
    }

    private fun refresh() {
        val address = this.address

        if (address != null) {
            binding.addressPreviewPane.isVisible = true
            binding.addressPreview.text = address.toBase58()
        } else {
            binding.addressPreviewPane.isVisible = false
        }

        refreshQr()
    }

    private fun refreshQr() {
        val address = this.address

        if (address != null) {
            paymentRequestUri = BitcoinURI.convertToBitcoinURI(address, amount, null, null)
            val qrCodeBitmap = Qr.themeAwareDrawable(paymentRequestUri, resources)
            binding.qrPreview.setImageDrawable(qrCodeBitmap)
        } else {
            paymentRequestUri = ""
            binding.qrPreview.setImageDrawable(null)
        }
    }

    private fun handleCopyAddress(address: Address) {
        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

            if (amount != null && paymentRequestUri.isNotEmpty()) {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("Dash payment request", paymentRequestUri))
            } else {
                clipboardManager.setPrimaryClip(ClipData.newPlainText("Dash address", address.toBase58()))
            }

            Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
            log.info("address copied to clipboard: {}", address)
        } catch (ignore: BitcoinURIParseException) { }
    }

    private fun handleCopyUsername(username: String) {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText("Dash Username", username))
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
        log.info("username copied to clipboard: {}", username)
    }

    private fun handleShare(paymentRequestUri: String) {
        val title = resources.getString(R.string.request_coins_share_dialog_title)
        context.shareText(paymentRequestUri, title)
        log.info("payment request shared via intent: {}", paymentRequestUri)
    }
}
