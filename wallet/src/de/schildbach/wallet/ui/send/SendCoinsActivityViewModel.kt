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
package de.schildbach.wallet.ui.send

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.livedata.Resource
import de.schildbach.wallet.ui.InputParser.*
import org.bitcoinj.core.PrefixedChecksummedBytes
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.VerificationException
import org.slf4j.LoggerFactory
import java.io.FileNotFoundException
import java.io.InputStream

open class SendCoinsActivityViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private val log = LoggerFactory.getLogger(SendCoinsActivityViewModel::class.java)
    }

    val walletApplication = application as WalletApplication
    val wallet = walletApplication.wallet!!

    val basePaymentIntent = MutableLiveData<Resource<PaymentIntent>>()

    fun initStateFromDashUri(dashUri: Uri) {

        val input = dashUri.toString()
        object : StringInputParser(input, true) {
            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                basePaymentIntent.value = Resource.success(paymentIntent)
            }

            override fun handlePrivateKey(key: PrefixedChecksummedBytes) {
                throw UnsupportedOperationException()
            }

            @Throws(VerificationException::class)
            override fun handleDirectTransaction(transaction: Transaction) {
                throw UnsupportedOperationException()
            }

            override fun error(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                val message = walletApplication.getString(messageResId, *messageArgs)
                basePaymentIntent.value = Resource.error(message)
            }
        }.parse()
    }

    fun initStateFromPaymentRequest(mimeType: String, input: ByteArray) {
        object : BinaryInputParser(mimeType, input) {
            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                basePaymentIntent.value = Resource.success(paymentIntent)
            }

            override fun error(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                val message = walletApplication.getString(messageResId, *messageArgs)
                basePaymentIntent.value = Resource.error(message)
            }
        }.parse()
    }

    fun initStateFromIntentUri(mimeType: String, bitcoinUri: Uri) {
        val inputStream: InputStream
        try {
            inputStream = walletApplication.contentResolver.openInputStream(bitcoinUri)!!
        } catch (x: FileNotFoundException) {
            throw RuntimeException(x)
        }

        object : StreamInputParser(mimeType, inputStream) {
            override fun handlePaymentIntent(paymentIntent: PaymentIntent) {
                basePaymentIntent.value = Resource.success(paymentIntent)
            }

            override fun error(ex: Exception?, messageResId: Int, vararg messageArgs: Any) {
                val message = walletApplication.getString(messageResId, *messageArgs)
                basePaymentIntent.value = Resource.error(message)
            }
        }.parse()
    }
}