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

package org.dash.wallet.integrations.crowdnode.api

import kotlinx.coroutines.flow.first
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.transactions.ByAddressCoinSelector
import org.dash.wallet.common.transactions.ExactOutputsSelector
import org.dash.wallet.common.transactions.LockedTransaction
import org.dash.wallet.integrations.crowdnode.model.CrowdNodeException
import org.dash.wallet.integrations.crowdnode.transactions.*
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import org.slf4j.LoggerFactory
import javax.inject.Inject
import kotlin.jvm.Throws

open class CrowdNodeBlockchainApi @Inject constructor(
    private val paymentService: SendPaymentService,
    private val walletDataProvider: WalletDataProvider
) {
    companion object {
        private val log = LoggerFactory.getLogger(CrowdNodeBlockchainApi::class.java)
    }

    private val params = walletDataProvider.networkParameters

    suspend fun topUpAddress(accountAddress: Address, amount: Coin, emptyWallet: Boolean = false): Transaction {
        val topUpTx = paymentService.sendCoins(accountAddress, amount, null, emptyWallet)
        return walletDataProvider.observeTransactions(LockedTransaction(topUpTx.txId)).first()
    }

    suspend fun makeSignUpRequest(accountAddress: Address): Transaction {
        val requestValue = CrowdNodeSignUpTx.SIGNUP_REQUEST_CODE
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(params)
        val selector = ByAddressCoinSelector(accountAddress)
        val signUpTx = paymentService.sendCoins(crowdNodeAddress, requestValue, selector)
        log.info("signUpTx id: ${signUpTx.txId}")
        val errorResponse = CrowdNodeErrorResponse(params, requestValue)
        val tx = walletDataProvider.observeTransactions(
            CrowdNodeAcceptTermsResponse(params),
            errorResponse
        ).first()

        if (errorResponse.matches(tx)) {
            throw CrowdNodeException("SignUp request returned an error")
        }

        return tx
    }

    suspend fun acceptTerms(accountAddress: Address): Transaction {
        val requestValue = CrowdNodeAcceptTermsTx.ACCEPT_TERMS_REQUEST_CODE
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(params)
        val selector = ByAddressCoinSelector(accountAddress)
        val acceptTx = paymentService.sendCoins(crowdNodeAddress, requestValue, selector)
        log.info("acceptTx id: ${acceptTx.txId}")
        val errorResponse = CrowdNodeErrorResponse(params, requestValue)
        val tx = walletDataProvider.observeTransactions(
            CrowdNodeWelcomeToApiResponse(params),
            errorResponse
        ).first()

        if (errorResponse.matches(tx)) {
            throw CrowdNodeException("AcceptTerms request returned an error")
        }

        return tx
    }

    @Throws(LeftoverBalanceException::class)
    suspend fun deposit(
        accountAddress: Address,
        amount: Coin,
        emptyWallet: Boolean,
        checkBalanceConditions: Boolean
    ): Transaction {
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(params)
        val selector = ByAddressCoinSelector(accountAddress)

        return paymentService.sendCoins(crowdNodeAddress, amount, selector, emptyWallet, checkBalanceConditions)
    }

    suspend fun waitForDepositResponse(amount: Coin): Transaction {
        val errorResponse = CrowdNodeErrorResponse(params, amount)
        val tx = walletDataProvider.observeTransactions(
            CrowdNodeDepositReceivedResponse(params),
            errorResponse
        ).first()

        if (errorResponse.matches(tx)) {
            throw CrowdNodeException(CrowdNodeException.DEPOSIT_ERROR)
        }

        return tx
    }

    suspend fun requestWithdrawal(accountAddress: Address, requestValue: Coin): Transaction {
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(params)
        val selector = ByAddressCoinSelector(accountAddress)

        return paymentService.sendCoins(crowdNodeAddress, requestValue, selector)
    }

    suspend fun waitForWithdrawalResponse(requestValue: Coin): Transaction {
        val errorResponse = CrowdNodeErrorResponse(params, requestValue)
        val deniedResponse = CrowdNodeWithdrawalDeniedResponse(params)
        val tx = walletDataProvider.observeTransactions(
            CrowdNodeWithdrawalQueueResponse(params),
            deniedResponse,
            errorResponse
        ).first()


        if (deniedResponse.matches(tx) || errorResponse.matches(tx)) {
            throw CrowdNodeException(CrowdNodeException.WITHDRAWAL_ERROR)
        }

        return tx
    }

    fun getDeposits(accountAddress: Address): Collection<Transaction> {
        return walletDataProvider.getTransactions(CrowdNodeDepositTx(accountAddress))
    }

    fun getDepositConfirmations(): Collection<Transaction> {
        return walletDataProvider.getTransactions(CrowdNodeDepositReceivedResponse(params))
    }

    suspend fun waitForApiAddressConfirmation(accountAddress: Address): Transaction {
        val filter = CrowdNodeAPIConfirmationTx(accountAddress)
        return walletDataProvider.getTransactions(filter).firstOrNull()
            ?: walletDataProvider.observeTransactions(filter).first()
    }

    open fun getFullSignUpTxSet(): FullCrowdNodeSignUpTxSet? {
        val wrappedTransactions = walletDataProvider.wrapAllTransactions(FullCrowdNodeSignUpTxSet(params))
        return wrappedTransactions.firstOrNull { it is FullCrowdNodeSignUpTxSet } as? FullCrowdNodeSignUpTxSet
    }

    suspend fun resendConfirmationTx(confirmationTx: Transaction) {
        val selector = ExactOutputsSelector(
            listOf(confirmationTx.outputs.first { it.value == CrowdNodeConstants.API_CONFIRMATION_DASH_AMOUNT })
        )
        val resentTx = paymentService.sendCoins(
            CrowdNodeConstants.getCrowdNodeAddress(params),
            CrowdNodeConstants.API_CONFIRMATION_DASH_AMOUNT,
            selector,
            true
        )
        log.info("Re-sent the confirmation tx: ${resentTx.txId}")

        val errorResponse = CrowdNodeErrorResponse(params, resentTx.outputs.first().value)
        val tx = walletDataProvider.observeTransactions(
            CrowdNodeDepositReceivedResponse(params),
            errorResponse
        ).first()

        if (errorResponse.matches(tx)) {
            throw CrowdNodeException(CrowdNodeException.CONFIRMATION_ERROR)
        }
    }
}