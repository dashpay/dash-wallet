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

import androidx.annotation.VisibleForTesting
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.security.SecurityFunctions
import de.schildbach.wallet.security.SecurityGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bitcoinj.core.*
import org.bitcoinj.crypto.KeyCrypterException
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.wallet.*
import org.dash.wallet.common.Constants
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.transactions.ByAddressCoinSelector
import org.slf4j.LoggerFactory
import javax.inject.Inject
import kotlin.jvm.Throws

class SendCoinsTaskRunner @Inject constructor(
    private val walletData: WalletDataProvider,
    private val walletApplication: WalletApplication,
    private val securityFunctions: SecurityFunctions
) : SendPaymentService {
    private val log = LoggerFactory.getLogger(SendCoinsTaskRunner::class.java)

    @Throws(LeftoverBalanceException::class)
    override suspend fun sendCoins(
        address: Address,
        amount: Coin,
        coinSelector: CoinSelector?,
        emptyWallet: Boolean,
        checkBalanceConditions: Boolean
    ): Transaction {
        val wallet = walletData.wallet ?: throw RuntimeException("this method can't be used before creating the wallet")
        Context.propagate(wallet.context)

        if (checkBalanceConditions && !wallet.isAddressMine(address)) {
            // This can throw LeftoverBalanceException
            walletData.checkSendingConditions(address, amount)
        }

        val sendRequest = createSendRequest(address, amount, coinSelector, emptyWallet)
        val scryptIterationsTarget = walletApplication.scryptIterationsTarget()

        return sendCoins(wallet, sendRequest, scryptIterationsTarget)
    }

    @VisibleForTesting
    fun createSendRequest(
        address: Address,
        amount: Coin,
        coinSelector: CoinSelector? = null,
        emptyWallet: Boolean = false
    ): SendRequest {
        return SendRequest.to(address, amount).apply {
            this.feePerKb = Constants.ECONOMIC_FEE
            this.ensureMinRequiredFee = true
            this.emptyWallet = emptyWallet

            val selector = coinSelector ?: ZeroConfCoinSelector.get()
            this.coinSelector = selector

            if (selector is ByAddressCoinSelector) {
                changeAddress = selector.address
            }
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
        val encryptionKey = securityFunctions.deriveKey(wallet, password, scryptIterationsTarget)

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
}