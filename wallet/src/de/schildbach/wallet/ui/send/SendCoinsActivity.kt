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

package de.schildbach.wallet.ui.send

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import android.util.Log
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import kotlin.coroutines.resume
import androidx.navigation.fragment.NavHostFragment
import dagger.hilt.android.AndroidEntryPoint
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.integration.android.BitcoinIntegration
import de.schildbach.wallet.ui.util.InputParser
import de.schildbach.wallet.ui.LockScreenActivity
import de.schildbach.wallet.ui.util.InputParserException
import de.schildbach.wallet.util.Nfc
import de.schildbach.wallet_test.R
import de.schildbach.wallet_test.databinding.ActivitySendCoinsBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.bitcoinj.protocols.payments.PaymentProtocol
import org.dash.wallet.common.ui.dialogs.AdaptiveDialog
import java.io.FileNotFoundException
import kotlin.coroutines.resumeWithException

@AndroidEntryPoint
open class SendCoinsActivity : LockScreenActivity() {
    companion object {
        const val ACTION_SEND_FROM_WALLET_URI = "de.schildbach.wallet.action.SEND_FROM_WALLET_URI"
        const val INTENT_EXTRA_PAYMENT_INTENT = "paymentIntent"
        const val ANYPAY_SCHEME = "pay"
        const val DASH_SCHEME = "dash"

        fun start(context: Context, paymentIntent: PaymentIntent?) {
            start(context, null, paymentIntent, false)
        }

        fun start(context: Context, action: String?, paymentIntent: PaymentIntent?, keepUnlocked: Boolean) {
            val intent = Intent(context, SendCoinsActivity::class.java)

            if (action != null) {
                intent.action = action
            }

            intent.putExtra(INTENT_EXTRA_PAYMENT_INTENT, paymentIntent)
            intent.putExtra(INTENT_EXTRA_KEEP_UNLOCKED, keepUnlocked)
            context.startActivity(intent)
        }

        fun sendFromWalletUri(callingActivity: Activity, requestCode: Int, paymentIntent: PaymentIntent?) {
            val intent = Intent(callingActivity, SendCoinsActivity::class.java)
            intent.action = ACTION_SEND_FROM_WALLET_URI
            intent.putExtra(INTENT_EXTRA_PAYMENT_INTENT, paymentIntent)
            callingActivity.startActivityForResult(intent, requestCode)
        }
    }

    private lateinit var binding: ActivitySendCoinsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // only set INTENT_EXTRA_KEEP_UNLOCKED if it is not yet set
        // if this Activity is started by a dash: uri, then it will not be set
        if (!intent.hasExtra(INTENT_EXTRA_KEEP_UNLOCKED)) {
            intent.putExtra(INTENT_EXTRA_KEEP_UNLOCKED, true)
        }

        binding = ActivitySendCoinsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            lifecycleScope.launch {
                try {
                    val paymentIntent = initPaymentIntentFromIntent(intent)
                    initNavController(paymentIntent)
                } catch (ex: Exception) {
                    AdaptiveDialog.simple(
                        ex.message ?: getString(R.string.error),
                        getString(R.string.button_dismiss),
                        ""
                    ).show(this@SendCoinsActivity) {
                        finish()
                    }
                } finally {
                    binding.progressRing.isVisible = false
                }
            }
        }
    }

    private suspend fun initPaymentIntentFromIntent(intent: Intent): PaymentIntent {
        val action = intent.action
        val intentUri = intent.data
        val mimeType = intent.type

        return if ((action == Intent.ACTION_VIEW || action == NfcAdapter.ACTION_NDEF_DISCOVERED) &&
            intentUri?.hasValidScheme() == true
        ) {
            initStateFromDashUri(intentUri)
        } else if (action == NfcAdapter.ACTION_NDEF_DISCOVERED && mimeType == PaymentProtocol.MIMETYPE_PAYMENTREQUEST) {
            val ndefMessage = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)?.get(0) as? NdefMessage
            val ndefMessagePayload = ndefMessage?.let {
                Nfc.extractMimePayload(PaymentProtocol.MIMETYPE_PAYMENTREQUEST, ndefMessage)
            }

            if (ndefMessagePayload != null) {
                initStateFromPaymentRequest(mimeType, ndefMessagePayload)
            } else {
                throw IllegalArgumentException("ndefMessagePayload is null")
            }
        } else if (Intent.ACTION_VIEW == action && PaymentProtocol.MIMETYPE_PAYMENTREQUEST == mimeType) {
            val paymentRequest = BitcoinIntegration.paymentRequestFromIntent(intent)

            if (intentUri != null) {
                initStateFromIntentUri(mimeType, intentUri)
            } else if (paymentRequest != null) {
                initStateFromPaymentRequest(mimeType, paymentRequest)
            } else {
                throw IllegalArgumentException("intentUri and paymentRequest are null")
            }
        } else if (intent.hasExtra(INTENT_EXTRA_PAYMENT_INTENT)) {
            val paymentIntent = intent.getParcelableExtra(INTENT_EXTRA_PAYMENT_INTENT) as PaymentIntent?
            return paymentIntent ?: throw IllegalArgumentException("paymentIntent is null")
        } else {
            throw IllegalStateException()
        }
    }

    private suspend fun initStateFromDashUri(dashUri: Uri): PaymentIntent {
        return suspendCancellableCoroutine { coroutine ->
            object : InputParser.StringInputParser(dashUri.toString(), true) {
                override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                    if (coroutine.isActive) {
                        coroutine.resume(paymentIntent)
                    }
                }

                override fun handlePrivateKey(key: PrefixedChecksummedBytes) {
                    if (coroutine.isActive) {
                        coroutine.resumeWithException(UnsupportedOperationException())
                    }
                }

                @Throws(VerificationException::class)
                override fun handleDirectTransaction(transaction: Transaction) {
                    if (coroutine.isActive) {
                        coroutine.resumeWithException(UnsupportedOperationException())
                    }
                }

                override fun error(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                    if (coroutine.isActive) {
                        coroutine.resumeWithException(
                            InputParserException(ex, getString(messageResId, *messageArgs))
                        )
                    }
                }
            }.parse()
        }
    }

    private suspend fun initStateFromPaymentRequest(mimeType: String, input: ByteArray): PaymentIntent {
        return suspendCancellableCoroutine { coroutine ->
            object : InputParser.BinaryInputParser(mimeType, input) {
                override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                    if (coroutine.isActive) {
                        coroutine.resume(paymentIntent)
                    }
                }

                override fun error(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                    if (coroutine.isActive) {
                        coroutine.resumeWithException(
                            InputParserException(ex, getString(messageResId, *messageArgs))
                        )
                    }
                }
            }.parse()
        }
    }

    private suspend fun initStateFromIntentUri(mimeType: String, uri: Uri): PaymentIntent {
        return suspendCancellableCoroutine { coroutine ->
            try {
                contentResolver.openInputStream(uri)!!.use {
                    object : InputParser.StreamInputParser(mimeType, it) {
                        override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                            if (coroutine.isActive) {
                                coroutine.resume(paymentIntent)
                            }
                        }

                        override fun error(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                            if (coroutine.isActive) {
                                coroutine.resumeWithException(
                                    InputParserException(ex, getString(messageResId, *messageArgs))
                                )
                            }
                        }
                    }.parse()
                }
            } catch (ex: FileNotFoundException) {
                if (coroutine.isActive) {
                    coroutine.resumeWithException(
                        InputParserException(ex, ex.toString())
                    )
                }
            }
        }
    }

    private fun initNavController(paymentIntent: PaymentIntent) {
        Log.i("REFACTORING", paymentIntent.mayEditAmount().toString())

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val navGraph = navController.navInflater.inflate(R.navigation.nav_send)

        navGraph.setStartDestination(
            if (paymentIntent.hasPaymentRequestUrl()) {
                R.id.paymentProtocolFragment
            } else {
                R.id.sendCoinsFragment
            }
        )

        navController.setGraph(navGraph, bundleOf(
            INTENT_EXTRA_PAYMENT_INTENT to paymentIntent
        ))
    }

    private fun Uri.hasValidScheme() =
        this.scheme == DASH_SCHEME || this.scheme == ANYPAY_SCHEME
}