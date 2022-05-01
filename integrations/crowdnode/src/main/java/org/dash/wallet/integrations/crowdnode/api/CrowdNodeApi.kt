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
import android.util.Log
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
import org.dash.wallet.common.Constants
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.SendPaymentService
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.transactions.LockedTransaction
import org.dash.wallet.common.util.TickerFlow
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.model.*
import org.dash.wallet.integrations.crowdnode.transactions.*
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConfig
import org.slf4j.LoggerFactory
import retrofit2.HttpException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

interface CrowdNodeApi {
    val signUpStatus: StateFlow<SignUpStatus>
    val onlineAccountStatus: StateFlow<OnlineAccountStatus>
    val balance: StateFlow<Resource<Coin>>
    val apiError: MutableStateFlow<Exception?>

    val accountAddress: Address?
    var notificationIntent: Intent?
    var showNotificationOnResult: Boolean

    fun persistentSignUp(accountAddress: Address)
    suspend fun signUp(accountAddress: Address)
    suspend fun deposit(amount: Coin, emptyWallet: Boolean): Boolean
    suspend fun withdraw(amount: Coin): Boolean
    fun hasAnyDeposits(): Boolean
    fun refreshBalance(retries: Int = 0)
    fun startTrackingLinked(address: Address)
    fun stopTrackingLinked()
    suspend fun reset()
}

@FlowPreview
@ExperimentalTime
@ExperimentalCoroutinesApi
class CrowdNodeBlockchainApi @Inject constructor(
    private val crowdNodeWebApi: CrowdNodeWebApi,
    private val paymentService: SendPaymentService,
    private val walletDataProvider: WalletDataProvider,
    private val notificationService: NotificationService,
    private val analyticsService: AnalyticsService,
    private val config: CrowdNodeConfig,
    @ApplicationContext private val appContext: Context
): CrowdNodeApi {
    companion object {
        private val log = LoggerFactory.getLogger(CrowdNodeBlockchainApi::class.java)
    }

    private val params = walletDataProvider.networkParameters
    private var tickerJob: Job? = null
    private var trackingApiAddress: Address? = null
    private val configScope = CoroutineScope(Dispatchers.IO)
    private val responseScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )

    override val signUpStatus = MutableStateFlow(SignUpStatus.NotStarted)
    override val onlineAccountStatus = MutableStateFlow(OnlineAccountStatus.None)
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
            log.error("CrowdNode error: $ex")
            analyticsService.logError(ex, "status: ${signUpStatus.value}")

            apiError.value = ex
            signUpStatus.value = SignUpStatus.Error
            config.setPreference(CrowdNodeConfig.ERROR, ex.message ?: "")
            notifyIfNeeded(appContext.getString(R.string.crowdnode_signup_error), "crowdnode_error")
        }
    }

    override suspend fun deposit(amount: Coin, emptyWallet: Boolean): Boolean {
        val accountAddress = this.accountAddress
        requireNotNull(accountAddress) { "Account address is null, make sure to sign up" }

        return try {
            apiError.value = null
            val topUpTx = topUpAddress(accountAddress, amount + Constants.ECONOMIC_FEE, emptyWallet)
            log.info("topUpTx id: ${topUpTx.txId}")
            val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(params)
            val depositTx = paymentService.sendCoins(crowdNodeAddress, amount, accountAddress, emptyWallet)
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

    override suspend fun withdraw(amount: Coin): Boolean {
        val accountAddress = this.accountAddress
        requireNotNull(accountAddress) { "Account address is null, make sure to sign up" }

        val balance = this.balance.value.data ?: Coin.ZERO
        require(amount <= balance) { "Amount is larger than CrowdNode balance" }

        return try {
            apiError.value = null

            val maxPermil = ApiCode.WithdrawAll.code
            val requestPermil = min(amount.value * maxPermil / balance.value, maxPermil)
            val requestValue = CrowdNodeConstants.API_OFFSET + Coin.valueOf(requestPermil)
            val topUpTx = topUpAddress(accountAddress, requestValue + Constants.ECONOMIC_FEE)
            log.info("topUpTx id: ${topUpTx.txId}")

            val crowdNodeAddress = CrowdNodeConstants.getCrowdNodeAddress(params)
            val withdrawTx = paymentService.sendCoins(crowdNodeAddress, requestValue, accountAddress)
            log.info("withdrawTx id: ${withdrawTx.txId}")

            responseScope.launch {
                val errorResponse = CrowdNodeErrorResponse(params, requestValue)
                val tx = walletDataProvider.observeTransactions(
                    CrowdNodeWithdrawalQueueResponse(params),
                    errorResponse
                ).first()
                log.info("got withdrawal queue response: ${tx.txId}")

                if (errorResponse.matches(tx)) {
                    val ex = CrowdNodeException("Withdraw error")
                    handleError(ex, appContext.getString(R.string.crowdnode_withdraw_error))
                }
            }

            return true
        } catch (ex: Exception) {
            handleError(ex, appContext.getString(R.string.crowdnode_withdraw_error))
            false
        }
    }

    override fun hasAnyDeposits(): Boolean {
        val accountAddress = this.accountAddress
        requireNotNull(accountAddress) { "Account address is null, make sure to sign up" }
        val deposits = walletDataProvider.getTransactions(CrowdNodeDepositTx(accountAddress))

        return deposits.any()
    }

    override fun refreshBalance(retries: Int) {
        if (signUpStatus.value == SignUpStatus.NotStarted) {
            return
        }

        responseScope.launch {
            val lastBalance = config.getPreference(CrowdNodeConfig.LAST_BALANCE) ?: 0L
            var currentBalance = Resource.loading(Coin.valueOf(lastBalance))
            balance.value = currentBalance

            for (i in 0..retries) {
                if (i != 0) {
                    delay(pow(5, i).seconds)
                }

                currentBalance = resolveBalance()

                if (lastBalance != currentBalance.data?.value) {
                    // balance changed, no need to retry anymore
                    break
                }
            }

            balance.value = currentBalance
        }
    }

    override fun startTrackingLinked(address: Address) {
        changeOnlineStatus(OnlineAccountStatus.Linking)
        trackingApiAddress = address
        tickerJob = TickerFlow(period = 2.seconds, initialDelay = 10.seconds)
            .onEach { checkIfAddressIsInUse(address) }
            .launchIn(responseScope)
    }

    override fun stopTrackingLinked() {
        val address = trackingApiAddress
        cancelTrackingJob()

        address?.let {
            responseScope.launch {
                // One last check just in case
                delay(5.seconds)
                checkIfAddressIsInUse(address)
            }
        }
    }

    override suspend fun reset() {
        log.info("reset is triggered")
        signUpStatus.value = SignUpStatus.NotStarted
        accountAddress = null
        apiError.value = null
        config.clearAll()
    }

    private fun restoreStatus() {
        if (signUpStatus.value == SignUpStatus.NotStarted) {
            log.info("restoring CrowdNode status")

            if (isError()) {
                return
            }

            if (tryRestoreSignUp()) {
                return
            }

            val savedAddress = runBlocking { config.getPreference(CrowdNodeConfig.ACCOUNT_ADDRESS) }
            savedAddress?.let {
                accountAddress = Address.fromString(walletDataProvider.networkParameters, savedAddress)
                tryRestoreOnlineAccount(accountAddress!!)
            }
        }
    }

    private fun isError(): Boolean {
        val savedError = runBlocking { config.getPreference(CrowdNodeConfig.ERROR) ?: "" }

        if (savedError.isNotEmpty()) {
            signUpStatus.value = SignUpStatus.Error
            apiError.value = CrowdNodeException(savedError)
            configScope.launch { config.setPreference(CrowdNodeConfig.ERROR, "") }
            log.info("found an error: $savedError")
            return true
        }

        return false
    }

    private fun tryRestoreSignUp(): Boolean {
        val wrappedTransactions = walletDataProvider.wrapAllTransactions(CrowdNodeFullTxSet(params))
        val crowdNodeFullSet = wrappedTransactions.firstOrNull { it is CrowdNodeFullTxSet }
        (crowdNodeFullSet as? CrowdNodeFullTxSet)?.let { set ->
            accountAddress = set.accountAddress
            requireNotNull(accountAddress) { "Restored signup tx set but address is null" }
            configScope.launch { config.setPreference(CrowdNodeConfig.ACCOUNT_ADDRESS, accountAddress!!.toBase58()) }

            if (set.hasWelcomeToApiResponse) {
                log.info("found finished sign up")
                signUpStatus.value = SignUpStatus.Finished
                return true
            }

            if (set.hasAcceptTermsResponse) {
                log.info("found accept terms response")
                signUpStatus.value = SignUpStatus.AcceptingTerms
                persistentSignUp(accountAddress!!)
                return true
            }
        }

        return false
    }

    private fun tryRestoreOnlineAccount(address: Address) {
        val statusOrdinal = runBlocking {
            config.getPreference(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS) ?: OnlineAccountStatus.None.ordinal
        }

        when (val status = OnlineAccountStatus.values()[statusOrdinal]) {
            OnlineAccountStatus.Linking -> {
                log.info("found linking in process")
                responseScope.launch { checkIfAddressIsInUse(address) }
            }
            OnlineAccountStatus.Confirming, OnlineAccountStatus.Done -> {
                log.info("found a linked account")
                changeOnlineStatus(status, save = false)
            }
            else -> { }
        }
    }


    private suspend fun topUpAddress(accountAddress: Address, amount: Coin, emptyWallet: Boolean = false): Transaction {
        val topUpTx = paymentService.sendCoins(accountAddress, amount, null, emptyWallet)
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
                config.setPreference(CrowdNodeConfig.LAST_BALANCE, balance.value)
                Resource.success(balance)
            } catch (ex: HttpException) {
                Resource.error(ex)
            } catch (ex: Exception) {
                log.error("Error while resolving balance: $ex")
                analyticsService.logError(ex)
                Resource.error(ex)
            }
        } else {
            Resource.success(Coin.ZERO)
        }
    }

    private suspend fun fetchBalance(address: String): String {
        val response = crowdNodeWebApi.getBalance(address)
        val balance = BigDecimal.valueOf(response.body()?.totalBalance ?: 0.0)

        return balance.setScale(8, RoundingMode.HALF_UP).toString()
    }

    private suspend fun checkIfAddressIsInUse(address: Address) {
        val isInUse = resolveIsAddressInUse(address)

        if (isInUse && onlineAccountStatus.value.ordinal <= OnlineAccountStatus.Linking.ordinal) {
            changeOnlineStatus(OnlineAccountStatus.Confirming)
            cancelTrackingJob()
        }
    }

    private fun cancelTrackingJob() {
        tickerJob?.cancel()
        tickerJob = null
        trackingApiAddress = null

        if (onlineAccountStatus.value == OnlineAccountStatus.Linking) {
            changeOnlineStatus(OnlineAccountStatus.None)
        }
    }

    private suspend fun resolveIsAddressInUse(address: Address): Boolean {
        try {
            val result = crowdNodeWebApi.isAddressInUse(address.toString())

            if (result.isSuccessful && result.body() != null) {
                return result.body()!!.isInUse
            }
        } catch (ex: Exception) {
            log.error("Error while resolving isAddressInUse: $ex")

            if (ex !is HttpException) {
                analyticsService.logError(ex)
            }
        }

        return false
    }

    private fun handleError(ex: Exception, error: String) {
        apiError.value = ex
        notifyIfNeeded(error, "crowdnode_error")
        log.error("$error: $ex")
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

    private fun changeOnlineStatus(status: OnlineAccountStatus, save: Boolean = true) {
        signUpStatus.value = if (status.ordinal < OnlineAccountStatus.Confirming.ordinal) {
            SignUpStatus.NotStarted
        } else {
            SignUpStatus.LinkedOnline
        }
        onlineAccountStatus.value = status

        if (save) {
            configScope.launch { config.setPreference(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS, status.ordinal) }
        }
    }
}