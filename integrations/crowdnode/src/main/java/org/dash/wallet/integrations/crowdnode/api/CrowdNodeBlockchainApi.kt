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
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.money.Dash
import org.dash.wallet.common.services.LeftoverBalanceException
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.SpendSelection
import org.dash.wallet.common.transactions.TransactionUtils
import org.dash.wallet.common.transactions.TxInfo
import org.dash.wallet.common.transactions.filters.CoinsReceivedTxFilter
import org.dash.wallet.common.transactions.filters.TxWithinTimePeriod
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

    private val networkId = walletData.networkId

    suspend fun topUpAddress(accountAddress: String, amount: Dash, emptyWallet: Boolean = false): TxInfo {
        // lock funds in outputs to accountAddress to prevent other send operations from using these funds
        val topUpTx = paymentService.sendCoinsSelected(
            accountAddress,
            amount,
            emptyWallet = emptyWallet,
            lockSentOutputsTo = accountAddress
        )
        walletData.waitUntilLocked(topUpTx.txId)
        return topUpTx
    }

    suspend fun makeSignUpRequest(accountAddress: String): TxInfo {
        val requestValue = CrowdNodeSignUpTx.SIGNUP_REQUEST_CODE
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(networkId)
        val signUpTx = paymentService.sendCoinsSelected(
            crowdNodeAddress,
            requestValue,
            SpendSelection.ByAddress(accountAddress),
            canSpendLockedOutputsTo = accountAddress
        )
        log.info("signUpTx id: ${signUpTx.txId}")
        val errorResponse = CrowdNodeErrorResponse(networkId, requestValue)
        val tx = walletData.observeTransactions(
            true,
            CrowdNodeAcceptTermsResponse(networkId),
            PossibleAcceptTermsResponse(accountAddress),
            errorResponse
        ).first()
        walletData.lockOutputsPayingTo(tx.txId, accountAddress)
        if (errorResponse.matches(tx)) {
            throw CrowdNodeException("SignUp request returned an error")
        }

        return tx
    }

    suspend fun acceptTerms(accountAddress: String): TxInfo {
        val requestValue = CrowdNodeAcceptTermsTx.ACCEPT_TERMS_REQUEST_CODE
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(networkId)
        val acceptTx = paymentService.sendCoinsSelected(
            crowdNodeAddress,
            requestValue,
            SpendSelection.ByAddress(accountAddress),
            canSpendLockedOutputsTo = accountAddress
        )
        log.info("acceptTx id: ${acceptTx.txId}")
        val errorResponse = CrowdNodeErrorResponse(networkId, requestValue)
        val tx = walletData.observeTransactions(
            true,
            CrowdNodeWelcomeToApiResponse(networkId),
            PossibleWelcomeResponse(accountAddress),
            errorResponse
        ).first()

        if (errorResponse.matches(tx)) {
            throw CrowdNodeException("AcceptTerms request returned an error")
        }

        return tx
    }

    @Throws(LeftoverBalanceException::class)
    suspend fun deposit(
        accountAddress: String,
        amount: Dash,
        emptyWallet: Boolean,
        checkBalanceConditions: Boolean
    ): TxInfo {
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(networkId)

        return paymentService.sendCoinsSelected(
            crowdNodeAddress,
            amount,
            SpendSelection.ByAddress(accountAddress),
            emptyWallet,
            checkBalanceConditions,
            canSpendLockedOutputsTo = accountAddress
        )
    }

    suspend fun waitForDepositResponse(amount: Dash): TxInfo {
        val errorResponse = CrowdNodeErrorResponse(networkId, amount)
        val tx = walletData.observeTransactions(
            true,
            CrowdNodeDepositReceivedResponse(networkId),
            errorResponse
        ).first()

        if (errorResponse.matches(tx)) {
            throw CrowdNodeException(CrowdNodeException.DEPOSIT_ERROR)
        }

        return tx
    }

    // not currently used
    suspend fun requestWithdrawal(accountAddress: String, requestValue: Dash): TxInfo {
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(networkId)

        return paymentService.sendCoinsSelected(
            crowdNodeAddress,
            requestValue,
            SpendSelection.ByAddress(accountAddress),
            emptyWallet = false,
            checkBalanceConditions = false,
            canSpendLockedOutputsTo = accountAddress
        )
    }

    suspend fun waitForWithdrawalResponse(requestValue: Dash): TxInfo {
        val errorResponse = CrowdNodeErrorResponse(networkId, requestValue)
        val deniedResponse = CrowdNodeWithdrawalDeniedResponse(networkId)
        val tx = walletData.observeTransactions(
            true,
            CrowdNodeWithdrawalQueueResponse(networkId),
            deniedResponse,
            errorResponse
        ).first()

        if (deniedResponse.matches(tx) || errorResponse.matches(tx)) {
            throw CrowdNodeException(CrowdNodeException.WITHDRAWAL_ERROR)
        }

        return tx
    }

    suspend fun waitForSignUpResponse(): TxInfo {
        val acceptFilter = CrowdNodeAcceptTermsResponse(networkId)
        val errorFilter = CrowdNodeErrorResponse(networkId, CrowdNodeSignUpTx.SIGNUP_REQUEST_CODE)
        val tx = walletData.getTransactions(acceptFilter, errorFilter).firstOrNull()
            ?: walletData.observeTransactions(true, acceptFilter, errorFilter).first()

        if (errorFilter.matches(tx)) {
            throw CrowdNodeException("SignUp request returned an error")
        }

        return tx
    }

    suspend fun waitForAcceptTermsResponse(): TxInfo {
        val welcomeFilter = CrowdNodeWelcomeToApiResponse(networkId)
        val errorFilter = CrowdNodeErrorResponse(networkId, CrowdNodeAcceptTermsTx.ACCEPT_TERMS_REQUEST_CODE)

        val tx = walletData.getTransactions(welcomeFilter, errorFilter).firstOrNull()
            ?: walletData.observeTransactions(true, welcomeFilter, errorFilter).first()

        if (errorFilter.matches(tx)) {
            throw CrowdNodeException("AcceptTerms request returned an error")
        }

        return tx
    }

    fun getDeposits(accountAddress: String): Collection<TxInfo> {
        return walletData.getTransactions(CrowdNodeDepositTx(accountAddress))
    }

    fun getDepositConfirmations(): Collection<TxInfo> {
        return walletData.getTransactions(CrowdNodeDepositReceivedResponse(networkId))
    }

    suspend fun waitForApiAddressConfirmation(accountAddress: String): TxInfo {
        val filter = CrowdNodeAPIConfirmationTx(accountAddress)
        return walletData.getTransactions(filter).firstOrNull()
            ?: walletData.observeTransactions(true, filter).first()
    }

    open fun getApiAddressConfirmationTx(): TxInfo? {
        val apiConfirmationFilter = CoinsReceivedTxFilter(
            CrowdNodeConstants.API_CONFIRMATION_DASH_AMOUNT
        ) // account address is unknown at this point

        val potentialApiConfirmationTxs = walletData.getTransactions(apiConfirmationFilter)
        potentialApiConfirmationTxs.forEach { confirmationTx ->
            val receivedTo = TransactionUtils.getWalletAddressOfReceived(confirmationTx)
            val forwardedConfirmationFilter = CrowdNodeAPIConfirmationForwarded(networkId)
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
            FullCrowdNodeSignUpTxSetFactory(networkId)
        )
        return wrappedTransactions.firstOrNull { it is FullCrowdNodeSignUpTxSet } as? FullCrowdNodeSignUpTxSet
    }

    suspend fun resendConfirmationTx(confirmationTx: TxInfo, accountAddress: String) {
        // lock the outputs
        walletData.lockOutputsPayingTo(confirmationTx.txId, accountAddress)
        val confirmationOutput = confirmationTx.outputs.first {
            it.valueDuffs == CrowdNodeConstants.API_CONFIRMATION_DASH_AMOUNT.duffs
        }
        val resentTx = paymentService.sendCoinsSelected(
            CrowdNodeConstants.getCrowdNodeAddress(networkId),
            CrowdNodeConstants.API_CONFIRMATION_DASH_AMOUNT,
            SpendSelection.ExactOutput(confirmationTx.txId, confirmationOutput.index),
            emptyWallet = true,
            checkBalanceConditions = false,
            canSpendLockedOutputsTo = accountAddress
        )
        log.info("Re-sent the confirmation tx: ${resentTx.txId}")

        val errorResponse = CrowdNodeErrorResponse(networkId, Dash.valueOf(resentTx.outputs.first().valueDuffs))
        val tx = walletData.observeTransactions(
            true,
            CrowdNodeDepositReceivedResponse(networkId),
            errorResponse
        ).first()

        if (errorResponse.matches(tx)) {
            throw CrowdNodeException(CrowdNodeException.CONFIRMATION_ERROR)
        }
    }

    suspend fun waitForWithdrawalReceived(): TxInfo {
        val filter = CrowdNodeWithdrawalReceivedTx(networkId)
        return walletData.getTransactions(filter).firstOrNull()
            ?: walletData.observeTransactions(true, filter).first()
    }

    fun getWithdrawalsForTheLast(duration: Duration): Dash {
        val now = Instant.now()
        val from = now.minusSeconds(duration.inWholeSeconds)

        val withdrawals = walletData.getTransactions(
            CrowdNodeWithdrawalReceivedTx(networkId)
                .and(TxWithinTimePeriod(Date.from(from), Date.from(now)))
        )

        return Dash.valueOf(withdrawals.sumOf { it.netValueDuffs })
    }
}
