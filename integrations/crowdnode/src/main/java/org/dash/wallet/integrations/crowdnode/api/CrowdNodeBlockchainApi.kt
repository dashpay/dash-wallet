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
import org.bitcoinj.script.ScriptPattern
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.transactions.ByAddressCoinSelector
import org.dash.wallet.common.transactions.ExactOutputsSelector
import org.dash.wallet.common.transactions.TransactionUtils
import org.dash.wallet.common.transactions.filters.CoinsReceivedTxFilter
import org.dash.wallet.common.transactions.filters.LockedTransaction
import org.dash.wallet.common.transactions.filters.TxWithinTimePeriod
import org.dash.wallet.common.transactions.waitToMatchFilters
import org.dash.wallet.integrations.crowdnode.model.CrowdNodeException
import org.dash.wallet.integrations.crowdnode.transactions.*
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*
import javax.inject.Inject
import kotlin.time.Duration

open class CrowdNodeBlockchainApi @Inject constructor(
    private val paymentService: SendPaymentService,
    private val walletData: WalletDataProvider
) {
    companion object {
        private val log = LoggerFactory.getLogger(CrowdNodeBlockchainApi::class.java)
    }

    private val params = walletData.networkParameters

    suspend fun topUpAddress(accountAddress: Address, amount: Coin, emptyWallet: Boolean = false): Transaction {
        val topUpTx = paymentService.sendCoins(accountAddress, amount, null, emptyWallet, beforeSending = {
            lockAccountAddressOutput(it, accountAddress)
        })
        topUpTx.waitToMatchFilters(LockedTransaction())
        return topUpTx
    }

    /** lock funds in outputs to accountAddress to prevent other send operations from using these funds */
    private fun lockAccountAddressOutput(
        it: Transaction,
        accountAddress: Address
    ) {
        it.outputs.filter { output ->
            ScriptPattern.isP2PKH(output.scriptPubKey) &&
                Address.fromPubKeyHash(
                walletData.networkParameters,
                ScriptPattern.extractHashFromP2PKH(output.scriptPubKey)
            ) == accountAddress
        }.forEach { output ->
            walletData.lockOutput(output.outPointFor)
        }
    }

    suspend fun makeSignUpRequest(accountAddress: Address): Transaction {
        val requestValue = CrowdNodeSignUpTx.SIGNUP_REQUEST_CODE
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(params)
        val selector = ByAddressCoinSelector(accountAddress)
        val signUpTx = paymentService.sendCoins(crowdNodeAddress, requestValue, selector, canSendLockedOutput = {
            it.scriptPubKey.getToAddress(params) == accountAddress
        })
        log.info("signUpTx id: ${signUpTx.txId}")
        val errorResponse = CrowdNodeErrorResponse(params, requestValue)
        val tx = walletData.observeTransactions(
            true,
            CrowdNodeAcceptTermsResponse(params),
            PossibleAcceptTermsResponse(walletData.transactionBag, accountAddress),
            errorResponse
        ).first()
        lockAccountAddressOutput(tx, accountAddress)
        if (errorResponse.matches(tx)) {
            throw CrowdNodeException("SignUp request returned an error")
        }

        return tx
    }

    suspend fun acceptTerms(accountAddress: Address): Transaction {
        val requestValue = CrowdNodeAcceptTermsTx.ACCEPT_TERMS_REQUEST_CODE
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(params)
        val selector = ByAddressCoinSelector(accountAddress)
        val acceptTx = paymentService.sendCoins(crowdNodeAddress, requestValue, selector, canSendLockedOutput = {
            it.scriptPubKey.getToAddress(params) == accountAddress
        })
        log.info("acceptTx id: ${acceptTx.txId}")
        val errorResponse = CrowdNodeErrorResponse(params, requestValue)
        val tx = walletData.observeTransactions(
            true,
            CrowdNodeWelcomeToApiResponse(params),
            PossibleWelcomeResponse(walletData.transactionBag, accountAddress),
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

        return paymentService.sendCoins(
            crowdNodeAddress,
            amount,
            selector,
            emptyWallet,
            checkBalanceConditions,
            canSendLockedOutput = {
                it.scriptPubKey.getToAddress(params) == accountAddress
            }
        )
    }

    suspend fun waitForDepositResponse(amount: Coin): Transaction {
        val errorResponse = CrowdNodeErrorResponse(params, amount)
        val tx = walletData.observeTransactions(
            true,
            CrowdNodeDepositReceivedResponse(params),
            errorResponse
        ).first()

        if (errorResponse.matches(tx)) {
            throw CrowdNodeException(CrowdNodeException.DEPOSIT_ERROR)
        }

        return tx
    }

    // not currently used
    suspend fun requestWithdrawal(accountAddress: Address, requestValue: Coin): Transaction {
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(params)
        val selector = ByAddressCoinSelector(accountAddress)

        return paymentService.sendCoins(
            crowdNodeAddress,
            requestValue,
            selector,
            emptyWallet = false,
            checkBalanceConditions = false,
            canSendLockedOutput = {
                it.scriptPubKey.getToAddress(params) == accountAddress
            }
        )
    }

    suspend fun waitForWithdrawalResponse(requestValue: Coin): Transaction {
        val errorResponse = CrowdNodeErrorResponse(params, requestValue)
        val deniedResponse = CrowdNodeWithdrawalDeniedResponse(params)
        val tx = walletData.observeTransactions(
            true,
            CrowdNodeWithdrawalQueueResponse(params),
            deniedResponse,
            errorResponse
        ).first()

        if (deniedResponse.matches(tx) || errorResponse.matches(tx)) {
            throw CrowdNodeException(CrowdNodeException.WITHDRAWAL_ERROR)
        }

        return tx
    }

    suspend fun waitForSignUpResponse(): Transaction {
        val acceptFilter = CrowdNodeAcceptTermsResponse(params)
        val errorFilter = CrowdNodeErrorResponse(params, CrowdNodeSignUpTx.SIGNUP_REQUEST_CODE)
        val tx = walletData.getTransactions(acceptFilter, errorFilter).firstOrNull()
            ?: walletData.observeTransactions(true, acceptFilter, errorFilter).first()

        if (errorFilter.matches(tx)) {
            throw CrowdNodeException("SignUp request returned an error")
        }

        return tx
    }

    suspend fun waitForAcceptTermsResponse(): Transaction {
        val welcomeFilter = CrowdNodeWelcomeToApiResponse(params)
        val errorFilter = CrowdNodeErrorResponse(params, CrowdNodeAcceptTermsTx.ACCEPT_TERMS_REQUEST_CODE)

        val tx = walletData.getTransactions(welcomeFilter, errorFilter).firstOrNull()
            ?: walletData.observeTransactions(true, welcomeFilter, errorFilter).first()

        if (errorFilter.matches(tx)) {
            throw CrowdNodeException("AcceptTerms request returned an error")
        }

        return tx
    }

    fun getDeposits(accountAddress: Address): Collection<Transaction> {
        return walletData.getTransactions(CrowdNodeDepositTx(accountAddress))
    }

    fun getDepositConfirmations(): Collection<Transaction> {
        return walletData.getTransactions(CrowdNodeDepositReceivedResponse(params))
    }

    suspend fun waitForApiAddressConfirmation(accountAddress: Address): Transaction {
        val filter = CrowdNodeAPIConfirmationTx(accountAddress)
        return walletData.getTransactions(filter).firstOrNull()
            ?: walletData.observeTransactions(true, filter).first()
    }

    open fun getApiAddressConfirmationTx(): Transaction? {
        val apiConfirmationFilter = CoinsReceivedTxFilter(
            walletData.transactionBag,
            CrowdNodeConstants.API_CONFIRMATION_DASH_AMOUNT
        ) // account address is unknown at this point

        val potentialApiConfirmationTxs = walletData.getTransactions(apiConfirmationFilter)
        potentialApiConfirmationTxs.forEach { confirmationTx ->
            val receivedTo = TransactionUtils.getWalletAddressOfReceived(confirmationTx, walletData.transactionBag)
            val forwardedConfirmationFilter = CrowdNodeAPIConfirmationForwarded(params)
            // There might be several matching transactions. The real one will be forwarded to CrowdNode
            val forwardedTx = walletData.getTransactions(forwardedConfirmationFilter).firstOrNull()

            if (forwardedTx != null && forwardedConfirmationFilter.fromAddresses.contains(receivedTo)) {
                return confirmationTx
            }
        }

        return null
    }

    open fun getFullSignUpTxSet(): FullCrowdNodeSignUpTxSet? {
        val wrappedTransactions = walletData.wrapAllTransactions(
            FullCrowdNodeSignUpTxSetFactory(params, walletData.transactionBag)
        )
        return wrappedTransactions.firstOrNull { it is FullCrowdNodeSignUpTxSet } as? FullCrowdNodeSignUpTxSet
    }

    suspend fun resendConfirmationTx(confirmationTx: Transaction, accountAddress: Address) {
        // lock the outputs
        lockAccountAddressOutput(confirmationTx, accountAddress)
        val selector = ExactOutputsSelector(
            listOf(confirmationTx.outputs.first { it.value == CrowdNodeConstants.API_CONFIRMATION_DASH_AMOUNT })
        )
        val resentTx = paymentService.sendCoins(
            CrowdNodeConstants.getCrowdNodeAddress(params),
            CrowdNodeConstants.API_CONFIRMATION_DASH_AMOUNT,
            selector,
            emptyWallet = true,
            checkBalanceConditions = false,
            canSendLockedOutput = {
                it.scriptPubKey.getToAddress(params) == accountAddress
            }
        )
        log.info("Re-sent the confirmation tx: ${resentTx.txId}")

        val errorResponse = CrowdNodeErrorResponse(params, resentTx.outputs.first().value)
        val tx = walletData.observeTransactions(
            true,
            CrowdNodeDepositReceivedResponse(params),
            errorResponse
        ).first()

        if (errorResponse.matches(tx)) {
            throw CrowdNodeException(CrowdNodeException.CONFIRMATION_ERROR)
        }
    }

    suspend fun waitForWithdrawalReceived(): Transaction {
        val filter = CrowdNodeWithdrawalReceivedTx(params)
        return walletData.getTransactions(filter).firstOrNull()
            ?: walletData.observeTransactions(true, filter).first()
    }

    fun getWithdrawalsForTheLast(duration: Duration): Coin {
        val now = Instant.now()
        val from = now.minusSeconds(duration.inWholeSeconds)

        val withdrawals = walletData.getTransactions(
            CrowdNodeWithdrawalReceivedTx(params)
                .and(TxWithinTimePeriod(Date.from(from), Date.from(now)))
        )

        return Coin.valueOf(withdrawals.sumOf { it.getValue(walletData.transactionBag).value })
    }
}
