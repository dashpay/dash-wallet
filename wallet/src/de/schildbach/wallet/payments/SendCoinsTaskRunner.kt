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
package de.schildbach.wallet.payments

import android.util.Log
import com.google.common.base.Preconditions
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.security.SecurityGuard
import de.schildbach.wallet.ui.send.SendCoinsBaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.*
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.crypto.KeyCrypterScrypt
import org.bitcoinj.script.ScriptPattern
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.wallet.*
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.services.SendPaymentService
import org.slf4j.LoggerFactory
import javax.inject.Inject

class SendCoinsTaskRunner @Inject constructor(
    private val walletApplication: WalletApplication
) : SendPaymentService {
    private val log = LoggerFactory.getLogger(SendCoinsTaskRunner::class.java)

    override suspend fun sendCoins(address: Address, amount: Coin, constrainInputsTo: Address?): Transaction {
        val wallet = walletApplication.wallet ?: throw RuntimeException("this method can't be used before creating the wallet")
        val sendRequest = createSendRequest(address, amount, constrainInputsTo)
        val scryptIterationsTarget = walletApplication.scryptIterationsTarget()

        return sendCoins(wallet, sendRequest, scryptIterationsTarget)

//        if (checkDust(sendRequest)) {
//            sendRequest = createSendRequest(wallet, false, paymentIntent, signInputs = false, forceEnsureMinRequiredFee = true)
//            wallet.completeTx(sendRequest)
//        }
    }

    private fun createSendRequest(address: Address, amount: Coin, constrainInputsTo: Address? = null): SendRequest {
        return SendRequest.to(address, amount).apply {
            coinSelector = ZeroConfCoinSelector.get()
            coinSelector = if (constrainInputsTo == null) ZeroConfCoinSelector.get() else MyOwnSelector(constrainInputsTo)
            feePerKb = SendCoinsBaseViewModel.ECONOMIC_FEE // TODO reference to an unrelated ViewModel. ECONOMIC_FEE should probably be moved to a dedicated class
            ensureMinRequiredFee = true
            changeAddress = constrainInputsTo
        }
    }

    private suspend fun sendCoins(
        wallet: Wallet,
        sendRequest: SendRequest,
        scryptIterationsTarget: Int,
        exchangeRate: ExchangeRate? = null,
        txCompleted: Boolean = false
    ): Transaction = withContext(Dispatchers.IO) {
        val securityGuard = SecurityGuard()
        val password = securityGuard.retrievePassword()
        val encryptionKey = deriveKey(wallet, password, scryptIterationsTarget)

        sendRequest.aesKey = encryptionKey
        sendRequest.exchangeRate = exchangeRate

        try {
            log.info("sending: {}", sendRequest)
            Context.propagate(Constants.CONTEXT)

            if (txCompleted) {
                wallet.commitTx(sendRequest.tx)
            } else {
                wallet.sendCoinsOffline(sendRequest)
            }

            val transaction = sendRequest.tx
            log.info("send successful, transaction committed: {}", transaction.txId.toString())
            transaction
        } catch (ex: Exception) {
            when (ex) {
                is InsufficientMoneyException -> ex.missing?.run {
                    log.info("send failed, {} missing", toFriendlyString())
                } ?: log.info("send failed, insufficient coins")
                is ECKey.KeyIsEncryptedException -> log.info("send failed, key is encrypted: {}", ex.message)
                is KeyCrypterException -> log.info("send failed, key crypter exception: {}", ex.message)
                is Wallet.CouldNotAdjustDownwards -> log.info("send failed, could not adjust downwards: {}", ex.message)
                is Wallet.CompletionException -> log.info("send failed, cannot complete: {}", ex.message)
            }
            throw ex
        }
    }

    @Throws(KeyCrypterException::class)
    private fun deriveKey(wallet: Wallet, password: String, scryptIterationsTarget: Int): KeyParameter {
        Preconditions.checkState(wallet.isEncrypted)
        val keyCrypter = wallet.keyCrypter!!

        // Key derivation takes time.
        var key = keyCrypter.deriveKey(password)

        // If the key isn't derived using the desired parameters, derive a new key.
        if (keyCrypter is KeyCrypterScrypt) {
            val scryptIterations = keyCrypter.scryptParameters.n

            if (scryptIterations != scryptIterationsTarget.toLong()) {
                log.info("upgrading scrypt iterations from {} to {}; re-encrypting wallet",
                        scryptIterations, scryptIterationsTarget)
                val newKeyCrypter = KeyCrypterScrypt(scryptIterationsTarget)
                val newKey: KeyParameter = newKeyCrypter.deriveKey(password)

                // Re-encrypt wallet with new key.
                try {
                    wallet.changeEncryptionKey(newKeyCrypter, key, newKey)
                    key = newKey
                    log.info("scrypt upgrade succeeded")
                } catch (x: KeyCrypterException) {
                    log.info("scrypt upgrade failed: {}", x.message)
                }
            }
        }

        // Hand back the (possibly changed) encryption key.
        return key
    }

    private class MyOwnSelector(private val address: Address) : CoinSelector {
        private val selector = ZeroConfCoinSelector.get()

        override fun select(
            target: Coin,
            candidates: MutableList<TransactionOutput>
        ): CoinSelection {
            Log.i("CROWDNODE", "Select override")
//            candidates?.forEach {
//                Log.i("CROWDNODE", "\ncandidate: ${it.index}, ${}")
//                Log.i("CROWDNODE", "parent transaction: ${it.parentTransaction?.toString()}")
//            }
            val filtered = candidates.filter { output ->
                val script = output.scriptPubKey
                val match = (ScriptPattern.isP2PKH(script) || ScriptPattern.isP2SH(script)) &&
                        script.getToAddress(address.parameters).toBase58() == address.toBase58()
                Log.i("CROWDNODE", "\nfiltering, match? ${match}\n$output")

                match
            }
            Log.i("CROWDNODE", "Filtered gathered: ${filtered.size}")

            val selection = selector.select(target, filtered)
            Log.i("CROWDNODE", "selection gathered: ${selection.gathered.size}")

            return selection
        }
    }
}