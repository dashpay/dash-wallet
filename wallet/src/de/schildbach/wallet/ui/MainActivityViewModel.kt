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

package de.schildbach.wallet.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.core.os.bundleOf
import androidx.lifecycle.AndroidViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.ui.InputParser.StringInputParser
import de.schildbach.wallet.ui.send.SendCoinsInternalActivity
import de.schildbach.wallet.ui.send.SweepWalletActivity
import de.schildbach.wallet_test.R
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import javax.inject.Inject

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    application: Application,
    private val analytics: AnalyticsService,
    private val clipboardManager: ClipboardManager
) : AndroidViewModel(application) {

    companion object {
        private val log = LoggerFactory.getLogger(MainActivityViewModel::class.java)
    }

    fun logEvent(event: String) {
        analytics.logEvent(event, bundleOf())
    }

    private fun handlePaste() {
        var input: String? = null

        if (clipboardManager.hasPrimaryClip()) {
            val clip = clipboardManager.primaryClip ?: return
            val clipDescription = clip.description

            if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) {
                input = clip.getItemAt(0).uri?.toString()
            } else if (clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
                || clipDescription.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)
            ) {
                input = clip.getItemAt(0).text?.toString()
            }
        }

        if (input != null) {
            handleString(
                input,
                R.string.scan_to_pay_error_dialog_title,
                R.string.scan_to_pay_error_dialog_message
            )
        } else {
//            baseAlertDialogBuilder.title = getString(R.string.scan_to_pay_error_dialog_title)
//            baseAlertDialogBuilder.message =
//                getString(R.string.scan_to_pay_error_dialog_message_no_data)
//            baseAlertDialogBuilder.neutralText = getString(R.string.button_dismiss)
//            alertDialog = baseAlertDialogBuilder.buildAlertDialog()
//            alertDialog.show()
        }
    }

    private fun handleString(
        input: String,
        errorDialogTitleResId: Int,
        cannotClassifyCustomMessageResId: Int
    ) {
        object : StringInputParser(input, true) {
            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                SendCoinsInternalActivity.start(this@WalletActivity, paymentIntent, true)
            }

            override fun handlePrivateKey(key: PrefixedChecksummedBytes) {
                SweepWalletActivity.start(this@WalletActivity, key, true)
            }

            @Throws(VerificationException::class)
            override fun handleDirectTransaction(tx: Transaction) {
                application.processDirectTransaction(tx)
            }

            override fun error(x: Exception, messageResId: Int, vararg messageArgs: Any) {
                baseAlertDialogBuilder.title = getString(errorDialogTitleResId)
                baseAlertDialogBuilder.message = getString(messageResId, *messageArgs)
                baseAlertDialogBuilder.neutralText = getString(R.string.button_dismiss)
                alertDialog = baseAlertDialogBuilder.buildAlertDialog()
                alertDialog.show()
            }

            override fun cannotClassify(input: String) {
                AbstractWalletActivity.log.info("cannot classify: '{}'", input)
                error(null, cannotClassifyCustomMessageResId, input)
            }
        }.parse()
    }
}