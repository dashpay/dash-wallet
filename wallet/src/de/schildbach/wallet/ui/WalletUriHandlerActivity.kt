/*
 * Copyright 2024 Dash Core Group.
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

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.integration.android.BitcoinIntegration
import de.schildbach.wallet.ui.main.MainActivity
import de.schildbach.wallet.ui.send.SendCoinsActivity
import de.schildbach.wallet.ui.util.InputParser.WalletUriParser
import de.schildbach.wallet.ui.util.WalletUri
import de.schildbach.wallet_test.R
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.ui.BaseAlertDialogBuilder
import org.dash.wallet.common.ui.formatString
import org.dash.wallet.integrations.coinbase.CoinbaseConstants
import org.dash.wallet.integrations.uphold.ui.UpholdPortalFragment

/**
 * The only purpose of this Activity is to handle all so called Wallet Uris
 * providing simple and convenient Inter App Communication.
 * It could not be handled directly by WalletActivity, since it is configured
 * as a singleTask and doesn't support startActivityForResult(...) pattern.
 */
class WalletUriHandlerActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_CODE_SEND_FROM_WALLET_URI = 1
    }

    private var wallet: Wallet? = null
    private lateinit var walletUriResultLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wallet = (application as WalletApplication).wallet
        walletUriResultLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            var resultIntent: Intent? = null
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val requestData = intent.data
                val transactionHash = BitcoinIntegration.transactionHashFromResult(data)
                resultIntent = WalletUri.createPaymentResult(requestData, transactionHash)
            }
            setResult(result.resultCode, resultIntent)
            finish()
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(getIntent())
    }

    private fun handleIntent(intent: Intent) {
        if (wallet == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        intent.data?.let { intentUri ->
            val action = intent.action
            val scheme = intentUri.scheme

            if (Intent.ACTION_VIEW == action && Constants.WALLET_URI_SCHEME == scheme) {
                if (intentUri.host.equals("brokers", ignoreCase = true)) {
                    val activityIntent = Intent(this, MainActivity::class.java)
                    activityIntent.putExtra("uri", intentUri)
                    activityIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    activityIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

                    if (intentUri.path?.contains("uphold") == true) {
                        activityIntent.setAction(UpholdPortalFragment.AUTH_RESULT_ACTION)
                        LocalBroadcastManager.getInstance(this).sendBroadcast(activityIntent)
                    } else if (intentUri.path?.contains("coinbase") == true) {
                        activityIntent.setAction(CoinbaseConstants.AUTH_RESULT_ACTION)
                        LocalBroadcastManager.getInstance(this).sendBroadcast(activityIntent)
                    }
                    finish()
                } else {
                    object : WalletUriParser(intentUri) {
                        override fun handlePaymentIntent(paymentIntent: PaymentIntent, forceInstantSend: Boolean) {
                            val intent = Intent(this@WalletUriHandlerActivity, SendCoinsActivity::class.java)
                            intent.action = SendCoinsActivity.ACTION_SEND_FROM_WALLET_URI
                            intent.putExtra(SendCoinsActivity.INTENT_EXTRA_PAYMENT_INTENT, paymentIntent)
                            walletUriResultLauncher.launch(intent)
                        }

                        override fun handleMasterPublicKeyRequest(sender: String) {
                            val confirmationMessage =
                                getString(R.string.wallet_uri_handler_public_key_request_dialog_msg, sender)
                            showConfirmationDialog(confirmationMessage, positiveBtnClickCreateMasterKey)
                        }

                        override fun handleAddressRequest(sender: String) {
                            val confirmationMessage =
                                getString(R.string.wallet_uri_handler_address_request_dialog_msg, sender)
                            showConfirmationDialog(confirmationMessage, positiveBtnClickCreateAddress)
                        }

                        override fun error(x: Exception, messageResId: Int, vararg messageArgs: Any) {
                            val baseAlertDialogBuilder = BaseAlertDialogBuilder(this@WalletUriHandlerActivity)
                            baseAlertDialogBuilder.message =
                                this@WalletUriHandlerActivity.formatString(messageResId, messageArgs)
                            baseAlertDialogBuilder.neutralText = getString(R.string.button_dismiss)
                            baseAlertDialogBuilder.neutralAction = {
                                finish()
                            }
                        }
                    }.parse()
                }
            }
        }
    }

    private val appName: String
        get() {
            val applicationInfo = applicationInfo
            val stringId = applicationInfo.labelRes
            return if (stringId == 0) applicationInfo.nonLocalizedLabel.toString() else getString(stringId)
        }

    private fun showConfirmationDialog(message: String, onPositiveButtonClickListener: Function0<Unit>) {
        val confirmationAlertDialogBuilder = BaseAlertDialogBuilder(this)
        confirmationAlertDialogBuilder.title = getString(R.string.app_name)
        confirmationAlertDialogBuilder.message = message
        confirmationAlertDialogBuilder.positiveText = getString(R.string.button_ok)
        confirmationAlertDialogBuilder.positiveAction = onPositiveButtonClickListener
        confirmationAlertDialogBuilder.negativeText = getString(R.string.button_cancel)
        confirmationAlertDialogBuilder.negativeAction = negativeButtonClickListener
        confirmationAlertDialogBuilder.buildAlertDialog().show()
    }

    private val negativeButtonClickListener = {
        this@WalletUriHandlerActivity.setResult(RESULT_CANCELED)
        finish()
    }

    private val positiveBtnClickCreateMasterKey = {
        val watchingKey = wallet!!.watchingKey.serializePubB58(wallet!!.networkParameters)
        val requestData = intent.data
        val result = WalletUri.createMasterPublicKeyResult(
            requestData, watchingKey, null,
            appName
        )
        setResult(RESULT_OK, result)
        finish()
    }

    private val positiveBtnClickCreateAddress = {
        val address = wallet!!.freshReceiveAddress()
        val requestData = intent.data
        val result = WalletUri.createAddressResult(requestData, address.toString(), appName)
        setResult(RESULT_OK, result)
        finish()
    }
}

