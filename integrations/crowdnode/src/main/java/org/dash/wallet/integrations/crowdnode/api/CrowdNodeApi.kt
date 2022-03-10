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
import com.google.common.math.LongMath.pow
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.transactions.LockedTransaction
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.transactions.*
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import org.dash.wallet.integrations.crowdnode.utils.ModuleConfiguration
import org.slf4j.LoggerFactory
import retrofit2.HttpException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
    val balance: StateFlow<Resource<Coin>>
    val apiError: MutableStateFlow<Exception?>
    val accountAddress: Address?
    var notificationIntent: Intent?
    var showNotificationOnResult: Boolean

    fun persistentSignUp(accountAddress: Address)
    suspend fun signUp(accountAddress: Address)
    suspend fun deposit(accountAddress: Address, amount: Coin): Boolean
    fun refreshBalance(retries: Int = 0)
    suspend fun reset()
}

class CrowdNodeException(message: String): Exception(message)

@ExperimentalCoroutinesApi
class CrowdNodeBlockchainApi @Inject constructor(
    private val crowdNodeWebApi: CrowdNodeWebApi,
    private val paymentService: SendPaymentService,
    private val walletDataProvider: WalletDataProvider,
    private val notificationService: NotificationService,
    private val analyticsService: AnalyticsService,
    private val config: ModuleConfiguration,
    @ApplicationContext private val appContext: Context
): CrowdNodeApi {
    companion object {
        private val log = LoggerFactory.getLogger(CrowdNodeBlockchainApi::class.java)
    }

    private val params = walletDataProvider.networkParameters
    private val configScope = CoroutineScope(Dispatchers.IO)
    private val responseScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    override val signUpStatus = MutableStateFlow(SignUpStatus.NotStarted)
    override val balance = MutableStateFlow(Resource.success(Coin.ZERO))
    override val apiError = MutableStateFlow<Exception?>(null)
    override var accountAddress: Address? = null
        private set
    override var notificationIntent: Intent? = null
    override var showNotificationOnResult = false

    init {
        restoreStatus()
        walletDataProvider.attachOnWalletWipedListener {
            configScope.launch { reset() }
        }
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
        this.accountAddress = accountAddress

        try {
            if (signUpStatus.value.ordinal < SignUpStatus.SigningUp.ordinal) {
                signUpStatus.value = SignUpStatus.FundingWallet
                val topUpTx = topUpAddress(accountAddress, CrowdNodeConstants.REQUIRED_FOR_SIGNUP)
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

            notifyIfNeeded(appContext.getString(R.string.crowdnode_account_ready), "crowdnode_ready")
        } catch (ex: Exception) {
            log.info("CrowdNode error: $ex")
            analyticsService.logError(ex, "status: ${signUpStatus.value}")

            apiError.value = ex
            signUpStatus.value = SignUpStatus.Error
            config.setSignUpError(ex.message ?: "")
            notifyIfNeeded(appContext.getString(R.string.crowdnode_signup_error), "crowdnode_error")
        }
    }

    override suspend fun deposit(accountAddress: Address, amount: Coin): Boolean {
        return try {
            apiError.value = null
            val topUpTx = topUpAddress(accountAddress, amount)
            log.info("topUpTx id: ${topUpTx.txId}")
            val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(params)
            val depositTx = paymentService.sendCoins(crowdNodeAddress, amount, accountAddress, true)
            log.info("depositTx id: ${depositTx.txId}")

            responseScope.launch {
                val errorResponse = CrowdNodeErrorResponse(params, amount)
                val tx = walletDataProvider.observeTransactions(
                    CrowdNodeDepositReceivedResponse(params),
                    errorResponse
                ).first()
                log.info("got deposit response: ${tx.txId}")

                if (errorResponse.matches(tx)) {
                    val ex = CrowdNodeException("Deposit error")
                    handleError(ex, appContext.getString(R.string.crowdnode_deposit_error))
                } else {
                    refreshBalance(retries = 3)
                }
            }

            true
        } catch (ex: Exception) {
            handleError(ex, appContext.getString(R.string.crowdnode_deposit_error))
            false
        }
    }

    override fun refreshBalance(retries: Int) {
        responseScope.launch {
            val lastBalance = config.lastBalance.first()

            for (i in 0..retries) {
                if (i != 0) {
                    delay(TimeUnit.SECONDS.toMillis(pow(5, i)))
                }

                balance.value = Resource.loading()
                val newBalance = resolveBalance()
                balance.value = newBalance

                if (lastBalance != newBalance.data?.value) {
                    // balance changed, no need to retry anymore
                    break
                }
            }
        }
    }

    override suspend fun reset() {
        log.info("reset is triggered")
        signUpStatus.value = SignUpStatus.NotStarted
        accountAddress = null
        apiError.value = null
        config.setSignUpError("")
    }

    private fun restoreStatus() {
        if (signUpStatus.value == SignUpStatus.NotStarted) {
            log.info("restoring CrowdNode status")
            val savedError = runBlocking { config.getSignUpError() }

            if (savedError.isNotEmpty()) {
                signUpStatus.value = SignUpStatus.Error
                apiError.value = CrowdNodeException(savedError)
                configScope.launch { config.setSignUpError("") }
                log.info("found an error: $savedError")
                return
            }

            val wrappedTransactions = walletDataProvider.wrapAllTransactions(CrowdNodeFullTxSet(params))
            val crowdNodeFullSet = wrappedTransactions.firstOrNull { it is CrowdNodeFullTxSet }
            (crowdNodeFullSet as? CrowdNodeFullTxSet)?.let { set ->
                accountAddress = set.accountAddress

                if (set.hasWelcomeToApiResponse) {
                    log.info("found finished sign up")
                    signUpStatus.value = SignUpStatus.Finished
                    return
                }

                if (set.hasAcceptTermsResponse) {
                    log.info("found accept terms response")
                    signUpStatus.value = SignUpStatus.AcceptingTerms
                    persistentSignUp(accountAddress!!)
                }
            }
        }
    }

    private suspend fun topUpAddress(accountAddress: Address, amount: Coin): Transaction {
        val topUpTx = paymentService.sendCoins(accountAddress, amount)
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

    private suspend fun resolveBalance(): Resource<Coin> {
        val address = accountAddress

        return if (address != null) {
            try {
                val balance = Coin.parseCoin(fetchBalance(address.toBase58()))
                config.setLastBalance(balance.value)
                Resource.success(balance)
            } catch (ex: HttpException) {
                Resource.error(ex)
            } catch (ex: Exception) {
                analyticsService.logError(ex)
                Resource.error(ex)
            }
        } else {
            Resource.success(Coin.ZERO)
        }
    }


    private suspend fun fetchBalance(address: String): String {
        val response = crowdNodeWebApi.getTransactions(address)
        var total = BigDecimal.ZERO
        response.body()?.value?.forEach { tx ->
            total += BigDecimal.valueOf(tx.amount)
        }
        return total.setScale(8, RoundingMode.HALF_UP).toString()
    }

    private fun handleError(ex: Exception, error: String) {
        apiError.value = ex
        notifyIfNeeded(error, "crowdnode_error")
        analyticsService.logError(ex)
    }

    private fun notifyIfNeeded(message: String, tag: String) {
        if (showNotificationOnResult) {
            notificationService.showNotification(
                tag,
                message,
                false,
                notificationIntent
            )
        }
    }
}