/*
 * Copyright 2020 Dash Core Group
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
package de.schildbach.wallet.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.zxing.*
import de.schildbach.wallet.WalletApplication
import org.dash.wallet.common.data.PaymentIntent
import de.schildbach.wallet.ui.payments.SweepWalletActivity
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet.ui.util.InputParser.StringInputParser
import de.schildbach.wallet_test.R
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import org.dash.wallet.common.util.Qr
import org.slf4j.LoggerFactory

/**
 * The only purpose of this Activity is to handle images shared with the App
 * It search for the QR codes inside the image, decode them and try to parse
 * the decoded URI
 */
class ImportSharedImageActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = null
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(getIntent())
    }

    private fun handleIntent(intent: Intent) {
        if ((application as WalletApplication).wallet == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        val action = intent.action
        val type = intent.type
        if (Intent.ACTION_SEND == action && type != null) {
            if (type.startsWith("image/")) {
                log.warn("handling image shared by other app")
                handleSendImage(intent)
                return
            } else {
                log.warn("unsupported MIME type {}", type)
            }
        } else {
            log.warn("unsupported action {}", action)
        }
        finish()
    }

    private fun handleSendImage(intent: Intent) {
        val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (imageUri != null) {
            Glide.with(this)
                .asBitmap()
                .load(imageUri).into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        val qrCode = Qr.scanQRImage(resource)
                        if (qrCode != null) {
                            handleQRCode(qrCode)
                        } else {
                            log.info("no QR code found in image {}", imageUri)
                            AdaptiveDialog.create(
                                R.drawable.ic_not_valid_qr_code,
                                getString(R.string.import_image_not_valid_qr_code),
                                getString(R.string.import_image_please_use_valid_qr_code),
                                getString(R.string.button_ok)
                            ).show(this@ImportSharedImageActivity)
                        }
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        log.info("load image failed {}", imageUri)
                        AdaptiveDialog.create(
                            R.drawable.ic_not_valid_qr_code,
                            getString(R.string.import_image_not_valid_qr_code),
                            getString(R.string.import_image_please_use_valid_qr_code),
                            getString(R.string.button_ok)
                        ).show(this@ImportSharedImageActivity)
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        // nothing to do
                    }
                })
        }
    }

    private fun handleQRCode(input: String) {
        object : StringInputParser(input, true) {
            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                SendCoinsActivity.start(this@ImportSharedImageActivity, intent.action, paymentIntent, true)
                finish()
            }

            override fun handlePrivateKey(key: PrefixedChecksummedBytes) {
                SweepWalletActivity.start(this@ImportSharedImageActivity, key, false)
                finish()
            }

            @Throws(VerificationException::class)
            override fun handleDirectTransaction(transaction: Transaction) {
                // ignore
                finish()
            }

            override fun error(x: Exception?, messageResId: Int, vararg messageArgs: Any) {
                AdaptiveDialog.create(
                    R.drawable.ic_not_valid_qr_code,
                    getString(R.string.import_image_invalid_private),
                    getString(messageResId, *messageArgs),
                    getString(R.string.button_ok)
                ).show(this@ImportSharedImageActivity)
            }
        }.parse()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ImportSharedImageActivity::class.java)
    }
}
