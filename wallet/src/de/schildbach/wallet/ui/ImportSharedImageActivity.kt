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
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.ui.InputParser.StringInputParser
import de.schildbach.wallet.ui.send.SendCoinsInternalActivity
import de.schildbach.wallet.ui.send.SweepWalletActivity
import de.schildbach.wallet_test.R
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.dash.wallet.common.ui.FancyAlertDialog
import org.dash.wallet.common.ui.FancyAlertDialogViewModel
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
                            val qrCode = scanQRImage(resource)
                            if (qrCode != null) {
                                handleQRCode(qrCode)
                            } else {
                                log.info("no QR code found in image {}", imageUri)
                                showErrorDialog(
                                        R.string.import_image_not_valid_qr_code,
                                        R.string.import_image_please_use_valid_qr_code,
                                        R.drawable.ic_not_valid_qr_code)
                            }
                        }

                        override fun onLoadFailed(errorDrawable: Drawable?) {
                            log.info("load image failed {}", imageUri)
                            showErrorDialog(
                                    R.string.import_image_not_valid_qr_code,
                                    R.string.import_image_please_use_valid_qr_code,
                                    R.drawable.ic_not_valid_qr_code)
                        }

                        override fun onLoadCleared(placeholder: Drawable?) {
                            // nothing to do
                        }
                    })
        }
    }

    private fun showErrorDialog(title: Int, msg: Int, image: Int) {
        val errorDialog = FancyAlertDialog.newInstance(title, msg, image, R.string.button_ok, 0)
        errorDialog.show(supportFragmentManager, "error_dialog")
        val errorDialogViewModel = ViewModelProvider(this)[FancyAlertDialogViewModel::class.java]
        errorDialogViewModel.onPositiveButtonClick.observe(this, Observer {
            finish()
        })
        errorDialogViewModel.onNegativeButtonClick.observe(this, Observer {
            finish()
        })
    }

    /**
     * Scan QR code directly from bitmap
     * https://stackoverflow.com/a/32135865/795721
     */
    fun scanQRImage(bitmap: Bitmap): String? {
        val intArray = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(intArray, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source: LuminanceSource = RGBLuminanceSource(bitmap.width, bitmap.height, intArray)
        val reader: Reader = MultiFormatReader()
        return try {
            val result = reader.decode(BinaryBitmap(HybridBinarizer(source)))
            log.info("successfully decoded QR code from bitmap")
            result.text
        } catch (e: ReaderException) {
            try {
                // Invert and check for a code
                val invertedSource = source.invert()
                val invertedBitmap = BinaryBitmap(HybridBinarizer(invertedSource))
                val invertedResult = reader.decode(invertedBitmap)
                log.info("successfully decoded inverted QR code from bitmap")
                invertedResult.text
            } catch (ex: ReaderException) {
                log.warn("error decoding barcode", e)
                null
            }
        }
    }

    private fun handleQRCode(input: String) {
        object : StringInputParser(input, true) {
            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                SendCoinsInternalActivity.start(this@ImportSharedImageActivity, intent.action, paymentIntent, false, true)
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
                showErrorDialog(
                        R.string.import_image_invalid_private, 0,
                        R.drawable.ic_not_valid_qr_code)
            }
        }.parse()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ImportSharedImageActivity::class.java)
    }
}