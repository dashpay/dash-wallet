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

import android.os.Handler
import androidx.lifecycle.liveData
import com.google.common.base.Preconditions
import de.schildbach.wallet.Constants
import de.schildbach.wallet.ui.security.SecurityGuard
import kotlinx.coroutines.Dispatchers
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Transaction
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.crypto.KeyCrypterScrypt
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.data.Resource
import org.slf4j.LoggerFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private val log = LoggerFactory.getLogger(SendCoinsTask::class.java)

object SendCoinsTask {

    fun sendCoins(wallet: Wallet, sendRequest: SendRequest, scryptIterationsTarget: Int,
                  exchangeRate: ExchangeRate? = null, txCompleted: Boolean = false) = liveData<Resource<Transaction>>(Dispatchers.IO) {

        emit(Resource.loading())
        val encryptionKey: KeyParameter
        try {
            val securityGuard: SecurityGuard
            try {
                securityGuard = SecurityGuard()
            } catch (e: Exception) {
                emit(Resource.error(e))
                return@liveData
            }

            val password = securityGuard.retrievePassword()
            encryptionKey = deriveKey(wallet, password, scryptIterationsTarget)
        } catch (x: Exception) {
            emit(Resource.error(x))
            return@liveData
        }

        sendRequest.aesKey = encryptionKey
        sendRequest.exchangeRate = exchangeRate

        try {
            log.info("sending: {}", sendRequest)
            Context.propagate(Constants.CONTEXT);
            if (txCompleted) {
                wallet.commitTx(sendRequest.tx)
            } else {
                wallet.sendCoinsOffline(sendRequest)
            }

            val transaction: Transaction = sendRequest.tx
            log.info("send successful, transaction committed: {}", transaction.txId.toString())
            emit(Resource.success(transaction))

        } catch (x: InsufficientMoneyException) {
            x.missing?.run {
                log.info("send failed, {} missing", toFriendlyString())
            } ?: log.info("send failed, insufficient coins")
            emit(Resource.error(x))
        } catch (x: ECKey.KeyIsEncryptedException) {
            log.info("send failed, key is encrypted: {}", x.message)
            emit(Resource.error(x))
        } catch (x: KeyCrypterException) {
            log.info("send failed, key crypter exception: {}", x.message)
            emit(Resource.error(x))
        } catch (x: Wallet.CouldNotAdjustDownwards) {
            log.info("send failed, could not adjust downwards: {}", x.message)
            emit(Resource.error(x))
        } catch (x: Wallet.CompletionException) {
            log.info("send failed, cannot complete: {}", x.message)
            emit(Resource.error(x))
        }
    }

    /**
     * Wraps callbacks of DeriveKeyTask as Coroutine
     */
    private suspend fun deriveKey(handler: Handler, wallet: Wallet, password: String, scryptIterationsTarget: Int): KeyParameter {
        return suspendCoroutine { continuation ->
            object : DeriveKeyTask(handler, scryptIterationsTarget) {

                override fun onSuccess(encryptionKey: KeyParameter, wasChanged: Boolean) {
                    continuation.resume(encryptionKey)
                }

                override fun onFailure(ex: KeyCrypterException?) {
                    log.error("unable to decrypt wallet", ex)
                    continuation.resumeWithException(ex as Throwable)
                }

            }.deriveKey(wallet, password)
        }
    }

    @Throws(KeyCrypterException::class)
    suspend fun deriveKey(wallet: Wallet, password: String, scryptIterationsTarget: Int): KeyParameter {
        Preconditions.checkState(wallet.isEncrypted)
        val keyCrypter = wallet.keyCrypter!!

        // Key derivation takes time.
        var key = keyCrypter.deriveKey(password)

        var wasChanged = false
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
                    wasChanged = true
                    log.info("scrypt upgrade succeeded")
                } catch (x: KeyCrypterException) {
                    log.info("scrypt upgrade failed: {}", x.message)
                }
            }
        }
        // Hand back the (possibly changed) encryption key.
        return key
    }
}