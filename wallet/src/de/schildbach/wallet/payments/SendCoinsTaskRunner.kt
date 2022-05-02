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
package de.schildbach.wallet.payments

import com.google.common.base.Preconditions
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.ui.security.SecurityGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.*
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.crypto.KeyCrypterScrypt
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.ZeroConfCoinSelector
import org.bouncycastle.crypto.params.KeyParameter
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.transactions.ByAddressCoinSelector
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import javax.inject.Inject


class SendCoinsTaskRunner @Inject constructor(
    private val walletApplication: WalletApplication
) : SendPaymentService {
    private val log = LoggerFactory.getLogger(SendCoinsTaskRunner::class.java)
    val wallet = walletApplication.wallet ?: throw RuntimeException("this method can't be used before creating the wallet")

    override suspend fun sendCoins(
        address: Address,
        amount: Coin,
        constrainInputsTo: Address?,
        emptyWallet: Boolean
    ): Transaction {
        Context.propagate(wallet.context)
        val sendRequest = createSendRequest(address, amount, constrainInputsTo, emptyWallet)
        val scryptIterationsTarget = walletApplication.scryptIterationsTarget()

        return sendCoins(wallet, sendRequest, scryptIterationsTarget)
    }

    override suspend fun estimateNetworkFee(
        address: Address,
        amount: Coin,
        constrainInputsTo: Address?,
        emptyWallet: Boolean
    ): SendPaymentService.TransactionDetails {

        var sendRequest = createSendRequest(address, amount, constrainInputsTo, emptyWallet, false)
        try {
            val securityGuard = SecurityGuard()
            val password = securityGuard.retrievePassword()
            val scryptIterationsTarget = walletApplication.scryptIterationsTarget()
            val encryptionKey = deriveKey(wallet, password, scryptIterationsTarget)

            sendRequest.aesKey = encryptionKey

            wallet.completeTx(sendRequest)
            if (checkDust(sendRequest)){
                sendRequest = createSendRequest(address, amount, constrainInputsTo, emptyWallet)
                wallet.completeTx(sendRequest)
            }

        } catch (e: Exception){
            e.printStackTrace()
        } catch (e: GeneralSecurityException){
            e.printStackTrace()
        }

        val txFee = sendRequest.tx.fee

        val amountToSend = if (sendRequest.emptyWallet){
            amount.minus(txFee)
        } else {
            amount
        }

        val totalAmount = if (sendRequest.emptyWallet){
            amount.toPlainString()
        } else {
            amount.add(txFee).toPlainString()
        }

        return SendPaymentService.TransactionDetails(txFee.toPlainString(), amountToSend, totalAmount)
    }

    private fun createSendRequest(
        address: Address,
        amount: Coin,
        constrainInputsTo: Address? = null,
        emptyWallet: Boolean = false,
        forceMinFee: Boolean = true
    ): SendRequest {
        return SendRequest.to(address, amount).apply {
            coinSelector = ZeroConfCoinSelector.get()
            coinSelector = if (constrainInputsTo == null) ZeroConfCoinSelector.get() else ByAddressCoinSelector(constrainInputsTo)
            feePerKb = Constants.ECONOMIC_FEE
            ensureMinRequiredFee = forceMinFee
            changeAddress = constrainInputsTo
            this.emptyWallet = emptyWallet
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private suspend fun sendCoins(
        wallet: Wallet,
        sendRequest: SendRequest,
        scryptIterationsTarget: Int,
        exchangeRate: ExchangeRate? = null,
        txCompleted: Boolean = false
    ): Transaction = withContext(Dispatchers.IO) {
        Context.propagate(wallet.context)

        val securityGuard = SecurityGuard()
        val password = securityGuard.retrievePassword()
        val encryptionKey = deriveKey(wallet, password, scryptIterationsTarget)

        sendRequest.aesKey = encryptionKey
        sendRequest.exchangeRate = exchangeRate

        try {
            log.info("sending: {}", sendRequest)

            if (txCompleted) {
                wallet.commitTx(sendRequest.tx)
            } else {
                wallet.sendCoinsOffline(sendRequest)
            }

            val transaction = sendRequest.tx
            log.info("send successful, transaction committed: {}", transaction.txId.toString())
            walletApplication.broadcastTransaction(transaction)
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
                log.info(
                    "upgrading scrypt iterations from {} to {}; re-encrypting wallet",
                    scryptIterations, scryptIterationsTarget
                )
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

    private fun checkDust(req: SendRequest): Boolean {
        if (req.tx != null) {
            for (output in req.tx.outputs) {
                if (output.isDust) return true
            }
        }
        return false
    }
}
