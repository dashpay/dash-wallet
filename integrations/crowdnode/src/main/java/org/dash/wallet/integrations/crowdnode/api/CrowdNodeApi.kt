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

import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.bitcoinj.core.Address
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.transactions.LockedTransaction
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.transactions.*
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import org.slf4j.LoggerFactory
import javax.inject.Inject

enum class SignUpStatus {
    NotStarted,
    FundingWallet,
    SigningUp,
    AcceptingTerms,
    Finished,
    Error
}

interface CrowdNodeApi {
    val signUpStatus: StateFlow<SignUpStatus>
    val existingAccountAddress: Address?
    val apiError: Exception?
    var notificationIntent: Intent?
    var showNotificationOnResult: Boolean

    fun persistentSignUp(accountAddress: Address)
    suspend fun signUp(accountAddress: Address)
    fun reset()
}

class CrowdNodeException(message: String): Exception(message)

class CrowdNodeBlockchainApi @Inject constructor(
    private val paymentService: SendPaymentService,
    private val walletDataProvider: WalletDataProvider,
    private val notificationService: NotificationService,
    private val analyticsService: AnalyticsService,
    @ApplicationContext private val appContext: Context
): CrowdNodeApi {
    companion object {
        private val log = LoggerFactory.getLogger(CrowdNodeBlockchainApi::class.java)
    }

    private val params = walletDataProvider.networkParameters

    override val signUpStatus = MutableStateFlow(SignUpStatus.NotStarted)
    override var existingAccountAddress: Address? = null
        private set
    override var apiError: Exception? = null
        private set
    override var notificationIntent: Intent? = null
    override var showNotificationOnResult = false

    init {
        checkCrowdNodeTransactions()
    }

    override fun persistentSignUp(accountAddress: Address) {
        log.info("CrowdNode persistent sign up")

        val crowdNodeWorker = OneTimeWorkRequestBuilder<CrowdNodeWorker>()
            .setInputData(workDataOf(
                CrowdNodeWorker.API_REQUEST to CrowdNodeWorker.SIGNUP_CALL,
                CrowdNodeWorker.ACCOUNT_ADDRESS to accountAddress.toBase58()
            ))
            .build()

        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(CrowdNodeWorker.WORK_NAME, ExistingWorkPolicy.KEEP, crowdNodeWorker)
    }

    override suspend fun signUp(accountAddress: Address) {
        log.info("CrowdNode sign up, current status: ${signUpStatus.value}")

        try {
            if (signUpStatus.value.ordinal < SignUpStatus.SigningUp.ordinal) {
                signUpStatus.value = SignUpStatus.FundingWallet
                val topUpTx = topUpAddress(accountAddress)
                log.info("topUpTx id: ${topUpTx.txId}")
            }

            if (signUpStatus.value.ordinal < SignUpStatus.AcceptingTerms.ordinal) {
                signUpStatus.value = SignUpStatus.SigningUp
                val signUpResponseTx = makeSignUpRequest(accountAddress)
                log.info("signUpResponseTx id: ${signUpResponseTx.txId}")
            }

            signUpStatus.value = SignUpStatus.AcceptingTerms
            val acceptTermsResponseTx = acceptTerms(accountAddress)
            log.info("acceptTermsResponseTx id: ${acceptTermsResponseTx.txId}")

            signUpStatus.value = SignUpStatus.Finished
            log.info("CrowdNode sign up finished")

            if (showNotificationOnResult) {
                notificationService.showNotification(
                    "crowdnode_ready",
                    appContext.getString(R.string.crowdnode_account_ready),
                    false,
                    notificationIntent
                )
            }
        } catch (ex: Exception) {
            log.info("CrowdNode error: $ex")
            analyticsService.logError(ex, "status: ${signUpStatus.value}")

            apiError = ex
            signUpStatus.value = SignUpStatus.Error

            if (showNotificationOnResult) {
                notificationService.showNotification(
                    "crowdnode_error",
                    appContext.getString(R.string.crowdnode_error),
                    false,
                    notificationIntent
                )
            }
        }
    }

    override fun reset() {
        signUpStatus.value = SignUpStatus.NotStarted
        existingAccountAddress = null
        apiError = null
    }

    private fun checkCrowdNodeTransactions() {
        if (signUpStatus.value == SignUpStatus.NotStarted) {
            log.info("checking CrowdNode transactions")
            val wrappedTransactions = walletDataProvider.wrapAllTransactions(CrowdNodeFullTxSet(params))
            val crowdNodeFullSet = wrappedTransactions.firstOrNull { it is CrowdNodeFullTxSet }
            (crowdNodeFullSet as? CrowdNodeFullTxSet)?.let { set ->
                existingAccountAddress = set.accountAddress

                if (set.hasWelcomeToApiResponse) {
                    log.info("found finished sign up")
                    signUpStatus.value = SignUpStatus.Finished
                    return
                }

                if (set.hasAcceptTermsResponse) {
                    log.info("found accept terms response")
                    signUpStatus.value = SignUpStatus.AcceptingTerms
                }
            }
        }
    }

    private suspend fun topUpAddress(accountAddress: Address): Transaction {
        val topUpTx = paymentService.sendCoins(accountAddress, CrowdNodeConstants.REQUIRED_FOR_SIGNUP)
        return walletDataProvider.observeTransactions(LockedTransaction(topUpTx.txId)).first()
    }

    private suspend fun makeSignUpRequest(accountAddress: Address): Transaction {
        val requestValue = CrowdNodeSignUpTx.SIGNUP_REQUEST_CODE
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(params)
        val signUpTx = paymentService.sendCoins(crowdNodeAddress, requestValue, accountAddress)
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

    private suspend fun acceptTerms(accountAddress: Address): Transaction {
        val requestValue = CrowdNodeAcceptTermsTx.ACCEPT_TERMS_REQUEST_CODE
        val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(params)
        val acceptTx = paymentService.sendCoins(crowdNodeAddress, requestValue, accountAddress)
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
}