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
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.Constants
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.services.ISecurityFunctions
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.common.util.TickerFlow
import org.dash.wallet.integrations.crowdnode.R
import org.dash.wallet.integrations.crowdnode.model.*
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConfig
import org.dash.wallet.integrations.crowdnode.utils.CrowdNodeConstants
import org.slf4j.LoggerFactory
import retrofit2.HttpException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

interface CrowdNodeApi {
    val signUpStatus: StateFlow<SignUpStatus>
    val onlineAccountStatus: StateFlow<OnlineAccountStatus>
    val balance: StateFlow<Resource<Coin>>
    val apiError: MutableStateFlow<Exception?>

    val primaryAddress: Address?
    val accountAddress: Address?
    var notificationIntent: Intent?
    var showNotificationOnResult: Boolean

    fun persistentSignUp(accountAddress: Address)
    suspend fun signUp(accountAddress: Address)
    suspend fun deposit(amount: Coin, emptyWallet: Boolean): Boolean
    suspend fun withdraw(amount: Coin): Boolean
    fun hasAnyDeposits(): Boolean
    fun refreshBalance(retries: Int = 0)
    fun trackLinkingAccount(address: Address)
    fun stopTrackingLinked()
    suspend fun registerEmailForAccount(email: String)
    suspend fun reset()
}

@FlowPreview
@ExperimentalTime
@ExperimentalCoroutinesApi
class CrowdNodeApiAggregator @Inject constructor(
    private val webApi: CrowdNodeWebApi,
    private val blockchainApi: CrowdNodeBlockchainApi,
    walletDataProvider: WalletDataProvider,
    private val notificationService: NotificationService,
    private val analyticsService: AnalyticsService,
    private val config: CrowdNodeConfig,
    private val globalConfig: Configuration,
    private val securityFunctions: ISecurityFunctions,
    @ApplicationContext private val appContext: Context
): CrowdNodeApi {
    companion object {
        private val log = LoggerFactory.getLogger(CrowdNodeApiAggregator::class.java)
        private const val CONFIRMED_STATUS = "confirmed"
        private const val VALID_STATUS = "valid"
        private const val MESSAGE_RECEIVED_STATUS = "received"
    }

    private val params = walletDataProvider.networkParameters
    private var tickerJob: Job? = null
    private var linkingApiAddress: Address? = null
    private val configScope = CoroutineScope(Dispatchers.IO)
    private val responseScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )
    private val statusScope = CoroutineScope(
        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    )
    private var isOnlineStatusRestored: Boolean = false

    override val signUpStatus = MutableStateFlow(SignUpStatus.NotStarted)
    override val onlineAccountStatus = MutableStateFlow(OnlineAccountStatus.None)
    override val balance = MutableStateFlow(Resource.success(Coin.ZERO))
    override val apiError = MutableStateFlow<Exception?>(null)
    override var primaryAddress: Address? = null
        private set
    override var accountAddress: Address? = null
        private set
    override var notificationIntent: Intent? = null
    override var showNotificationOnResult = false

    init {
        restoreStatus()
        walletDataProvider.attachOnWalletWipedListener {
            configScope.launch { reset() }
        }

        onlineAccountStatus
            .onEach { status ->
                Log.i("CROWDNODE", "CrowdNodeApi observe status: ${status}")
                cancelTrackingJob()
                val initialDelay = if (isOnlineStatusRestored) 0.seconds else 10.seconds
                when(status) {
                    OnlineAccountStatus.Linking -> startTrackingLinked(linkingApiAddress!!)
                    OnlineAccountStatus.Validating -> startTrackingValidated(accountAddress!!, initialDelay)
                    OnlineAccountStatus.Confirming -> startTrackingConfirmed(accountAddress!!, initialDelay)
                    OnlineAccountStatus.Creating -> startTrackingCreating(accountAddress!!, initialDelay)
                    else -> { }
                }
            }
            .launchIn(statusScope)

        config.observePreference(CrowdNodeConfig.BACKGROUND_ERROR)
            .filterNot { it.isNullOrEmpty() }
            .onEach {
                if (apiError.value == null) {
                    apiError.value = CrowdNodeException(it ?: "")
                    config.setPreference(CrowdNodeConfig.BACKGROUND_ERROR, "")
                }
            }
            .launchIn(configScope)
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
                val topUpTx = blockchainApi.topUpAddress(accountAddress, CrowdNodeConstants.REQUIRED_FOR_SIGNUP)
                log.info("topUpTx id: ${topUpTx.txId}")
            }

            if (signUpStatus.value.ordinal < SignUpStatus.AcceptingTerms.ordinal) {
                signUpStatus.value = SignUpStatus.SigningUp
                val signUpResponseTx = blockchainApi.makeSignUpRequest(accountAddress)
                log.info("signUpResponseTx id: ${signUpResponseTx.txId}")
            }

            signUpStatus.value = SignUpStatus.AcceptingTerms
            val acceptTermsResponseTx = blockchainApi.acceptTerms(accountAddress)
            log.info("acceptTermsResponseTx id: ${acceptTermsResponseTx.txId}")

            signUpStatus.value = SignUpStatus.Finished
            log.info("CrowdNode sign up finished")

            notifyIfNeeded(appContext.getString(R.string.crowdnode_account_ready), "crowdnode_ready")
        } catch (ex: Exception) {
            log.error("CrowdNode error: $ex")
            analyticsService.logError(ex, "status: ${signUpStatus.value}")

            apiError.value = ex
            signUpStatus.value = SignUpStatus.Error
            config.setPreference(CrowdNodeConfig.BACKGROUND_ERROR, ex.message ?: "")
            notifyIfNeeded(appContext.getString(R.string.crowdnode_signup_error), "crowdnode_error")
        }
    }

    override suspend fun deposit(amount: Coin, emptyWallet: Boolean): Boolean {
        val accountAddress = this.accountAddress
        requireNotNull(accountAddress) { "Account address is null, make sure to sign up" }

        return try {
            apiError.value = null
            val topUpTx = blockchainApi.topUpAddress(accountAddress, amount + Constants.ECONOMIC_FEE, emptyWallet)
            log.info("topUpTx id: ${topUpTx.txId}")
            val depositTx = blockchainApi.deposit(accountAddress, amount, emptyWallet)
            log.info("depositTx id: ${depositTx.txId}")

            responseScope.launch {
                try {
                    val tx = blockchainApi.waitForDepositResponse(amount)
                    log.info("got deposit response: ${tx.txId}")
                } catch (ex: Exception) {
                    handleError(ex, appContext.getString(R.string.crowdnode_deposit_error))
                    return@launch
                }

                refreshBalance(retries = 3)
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
            val topUpTx = blockchainApi.topUpAddress(accountAddress, requestValue + Constants.ECONOMIC_FEE)
            log.info("topUpTx id: ${topUpTx.txId}")
            val withdrawTx = blockchainApi.requestWithdrawal(accountAddress, requestValue)
            log.info("withdrawTx id: ${withdrawTx.txId}")

            responseScope.launch {
                try {
                    val tx = blockchainApi.waitForWithdrawalResponse(requestValue)
                    log.info("got withdrawal queue response: ${tx.txId}")
                } catch (ex: Exception) {
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
        val deposits = blockchainApi.getDeposits(accountAddress)

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

    override fun trackLinkingAccount(address: Address) {
        linkingApiAddress = address
        changeOnlineStatus(OnlineAccountStatus.Linking)
    }

    override fun stopTrackingLinked() {
        val address = linkingApiAddress

        if (signUpStatus.value == SignUpStatus.NotStarted &&
            onlineAccountStatus.value.ordinal <= OnlineAccountStatus.Linking.ordinal
        ) {
            log.info("stopTrackingLinked")
            changeOnlineStatus(OnlineAccountStatus.None)

            address?.let {
                responseScope.launch {
                    // One last check just in case
                    delay(5.seconds)
                    checkIfAddressIsInUse(address)
                }
            }
        }
    }

    override suspend fun registerEmailForAccount(email: String) {
        val address = accountAddress
        requireNotNull(address) { "Account address is null, make sure to sign up" }
        val signature = securityFunctions.signMessage(address, email)

        if (sendSignedEmailMessage(address, email, signature)) {
            changeOnlineStatus(OnlineAccountStatus.Creating)
        }
    }

    private fun startTrackingLinked(address: Address) {
        log.info("startTrackingLinked, account: ${address.toBase58()}")
        tickerJob = TickerFlow(period = 2.seconds, initialDelay = 5.seconds)
            .cancellable()
            .onEach { checkIfAddressIsInUse(address) }
            .launchIn(responseScope)
    }

    private suspend fun startTrackingValidated(accountAddress: Address, initialDelay: Duration) {
        log.info("startTrackingValidated, account: ${accountAddress.toBase58()}")
        tickerJob = TickerFlow(period = 30.seconds, initialDelay = initialDelay)
            .cancellable()
            .onEach { checkAddressStatus(accountAddress) }
            .launchIn(statusScope)
    }

    private suspend fun startTrackingConfirmed(accountAddress: Address, initialDelay: Duration) {
        log.info("startTrackingConfirmed, account: ${accountAddress.toBase58()}")
        // First check or wait for the confirmation tx.
        // No need to make web requests if it isn't found.
        val confirmationTx = blockchainApi.waitForApiAddressConfirmation(accountAddress)
        log.info("Confirmation tx found: ${confirmationTx.txId}")

        if (blockchainApi.getDepositConfirmations().any()) {
            // If a deposit confirmation was received, the address has been confirmed already
            changeOnlineStatus(OnlineAccountStatus.Done)
            notifyIfNeeded(appContext.getString(R.string.crowdnode_address_confirmed), "crowdnode_confirmed")
            return
        }

        tickerJob = TickerFlow(period = 20.seconds, initialDelay = initialDelay)
            .cancellable()
            .onEach { checkAddressStatus(accountAddress) }
            .launchIn(statusScope)
    }

    private suspend fun startTrackingCreating(accountAddress: Address, initialDelay: Duration) {
        log.info("startTrackingEmailStatus, account: ${accountAddress.toBase58()}")
        tickerJob = TickerFlow(period = 20.seconds, initialDelay = initialDelay)
            .cancellable()
            .onEach { checkIfEmailRegistered(accountAddress) }
            .launchIn(statusScope)
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun sendSignedEmailMessage(
        address: Address,
        email: String,
        signature: String
    ): Boolean {
        log.info("Sending signed email message")
        val encodedSignature = URLEncoder.encode(signature, "utf-8")
        val result = webApi.sendSignedMessage(address.toBase58(), email, encodedSignature)

        if (result.isSuccessful && result.body()?.messageStatus?.lowercase() == MESSAGE_RECEIVED_STATUS) {
            log.info("Signed email sent successfully")
            config.setPreference(CrowdNodeConfig.SIGNED_EMAIL_SENT, true)
            return true
        }

        if (result.isSuccessful) {
            log.info("SendMessage result successful, but wrong status: ${result.body()?.messageStatus ?: "null"}")
            apiError.value = CrowdNodeException(CrowdNodeException.SEND_MESSAGE_ERROR)
            return false
        }

        log.info("SendMessage error, code: ${result.code()}, error: ${result.errorBody()?.string()}")
        apiError.value = CrowdNodeException(CrowdNodeException.SEND_MESSAGE_ERROR)
        return false
    }

    override suspend fun reset() {
        log.info("reset is triggered")
        signUpStatus.value = SignUpStatus.NotStarted
        onlineAccountStatus.value = OnlineAccountStatus.None
        accountAddress = null
        primaryAddress = null
        linkingApiAddress = null
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
                restoreCreatedOnlineAccount(accountAddress!!)
                return
            }

            val savedAddress = globalConfig.crowdNodeAccountAddress

            if (savedAddress.isNotEmpty()) {
                accountAddress = Address.fromString(params, savedAddress)
                tryRestoreLinkedOnlineAccount(accountAddress!!)
            }
        }
    }

    private fun isError(): Boolean {
        val savedError = runBlocking { config.getPreference(CrowdNodeConfig.BACKGROUND_ERROR) ?: "" }

        if (savedError.isNotEmpty()) {
            signUpStatus.value = SignUpStatus.Error
            apiError.value = CrowdNodeException(savedError)
            configScope.launch { config.setPreference(CrowdNodeConfig.BACKGROUND_ERROR, "") }
            log.info("found an error: $savedError")
            return true
        }

        return false
    }

    private fun tryRestoreSignUp(): Boolean {
        val fullSignUpSet = blockchainApi.getFullSignUpTxSet()
        fullSignUpSet?.let { set ->
            accountAddress = set.accountAddress
            requireNotNull(accountAddress) { "Restored signup tx set but address is null" }
            configScope.launch { globalConfig.crowdNodeAccountAddress = accountAddress!!.toBase58() }

            if (set.hasWelcomeToApiResponse) {
                log.info("found finished sign up, account: ${set.accountAddress?.toBase58() ?: "null"}")
                signUpStatus.value = SignUpStatus.Finished
                return true
            }

            if (set.hasAcceptTermsResponse) {
                log.info("found accept terms response, account: ${set.accountAddress?.toBase58() ?: "null"}")
                signUpStatus.value = SignUpStatus.AcceptingTerms
                persistentSignUp(accountAddress!!)
                return true
            }
        }

        return false
    }

    private fun tryRestoreLinkedOnlineAccount(address: Address) {
        var statusOrdinal: Int
        var primaryAddressStr: String

        runBlocking {
            statusOrdinal = config.getPreference(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS) ?: OnlineAccountStatus.None.ordinal
            primaryAddressStr = globalConfig.crowdNodePrimaryAddress
        }

        if (primaryAddressStr.isNotEmpty()) {
            primaryAddress = Address.fromBase58(params, primaryAddressStr)
        }

        when (val status = OnlineAccountStatus.values()[statusOrdinal]) {
            OnlineAccountStatus.None -> { }
            OnlineAccountStatus.Linking -> {
                log.info("found linking online account in progress, account: ${address.toBase58()}, primary: $primaryAddressStr")
                responseScope.launch { checkIfAddressIsInUse(address) }
            }
            OnlineAccountStatus.Creating, OnlineAccountStatus.SigningUp -> {
                // This should not happen - this method is reachable only for a linked account case
                throw IllegalStateException("Creating state found in tryRestoreOnlineAccount")
            }
            else -> {
                changeOnlineStatus(status, save = false)
                log.info("found online account, status: ${status.name}, account: ${address.toBase58()}, primary: $primaryAddressStr")
            }
        }
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
        val response = webApi.getBalance(address)
        val balance = BigDecimal.valueOf(response.body()?.totalBalance ?: 0.0)

        return balance.setScale(8, RoundingMode.HALF_UP).toString()
    }

    private suspend fun checkIfAddressIsInUse(address: Address) {
        val isInUse = resolveIsAddressInUse(address)

        if (isInUse && onlineAccountStatus.value.ordinal <= OnlineAccountStatus.Linking.ordinal) {
            val primary = primaryAddress

            if (primary == null) {
                apiError.value = CrowdNodeException(CrowdNodeException.MISSING_PRIMARY)
                changeOnlineStatus(OnlineAccountStatus.None)
            } else {
                accountAddress = address
                globalConfig.crowdNodeAccountAddress = address.toBase58()
                globalConfig.crowdNodePrimaryAddress = primary.toBase58()
                changeOnlineStatus(OnlineAccountStatus.Validating)
            }
        }
    }

    private suspend fun checkAddressStatus(address: Address) {
        val status = resolveAddressStatus(address)

        if (status?.lowercase() == VALID_STATUS && onlineAccountStatus.value != OnlineAccountStatus.Confirming) {
            changeOnlineStatus(OnlineAccountStatus.Confirming)
            notifyIfNeeded(appContext.getString(R.string.crowdnode_address_validated), "crowdnode_validated")
        } else if (status?.lowercase() == CONFIRMED_STATUS) {
            changeOnlineStatus(OnlineAccountStatus.Done)
            notifyIfNeeded(appContext.getString(R.string.crowdnode_address_confirmed), "crowdnode_confirmed")
            refreshBalance(3)
        }
    }

    private fun restoreCreatedOnlineAccount(address: Address) {
        val statusOrdinal = runBlocking { config.getPreference(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS)
                ?: OnlineAccountStatus.None.ordinal }

        Log.i("CROWDNODE", "restored status: ${OnlineAccountStatus.values()[statusOrdinal]}")

        when (val status = OnlineAccountStatus.values()[statusOrdinal]) {
            OnlineAccountStatus.None -> statusScope.launch { checkIfEmailRegistered(address) }
            OnlineAccountStatus.Creating, OnlineAccountStatus.SigningUp, OnlineAccountStatus.Done -> {
                changeOnlineStatus(status, false)
            }
            else -> {}
        }
    }

    private suspend fun checkIfEmailRegistered(address: Address) {
        val isDefaultEmail = resolveIsDefaultEmail(address)

        if (!isDefaultEmail) {
            changeOnlineStatus(OnlineAccountStatus.SigningUp)
        }
    }

    private suspend fun resolveIsAddressInUse(address: Address): Boolean {
        return try {
            val result = webApi.isAddressInUse(address.toBase58())
            val isSuccess = result.isSuccessful && result.body()?.isInUse == true

            if (isSuccess) {
                val primary = result.body()!!.primaryAddress
                requireNotNull(primary) { "isAddressInUse returns true but missing primary address" }
                primaryAddress = Address.fromBase58(params, primary)
            }

            isSuccess
        } catch (ex: Exception) {
            log.error("Error in resolveIsAddressInUse: $ex")

            if (ex !is HttpException) {
                analyticsService.logError(ex)
            }

            false
        }
    }

    private suspend fun resolveAddressStatus(address: Address): String? {
        return try {
            val result = webApi.addressStatus(address.toBase58())
            result.body()?.status
        } catch (ex: Exception) {
            log.error("Error in resolveAddressStatus: $ex")

            if (ex !is HttpException) {
                analyticsService.logError(ex)
            }

            null
        }
    }

    private suspend fun resolveIsDefaultEmail(address: Address): Boolean {
        return try {
            val result = webApi.hasDefaultEmail(address.toBase58())
            result.isSuccessful && result.body()?.isDefault != false
        } catch (ex: Exception) {
            log.error("Error in resolveIsDefaultEmail: $ex")

            if (ex !is HttpException) {
                analyticsService.logError(ex)
            }

            true
        }
    }

    private fun cancelTrackingJob() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun handleError(ex: Exception, error: String) {
        apiError.value = ex
        notifyIfNeeded(error, "crowdnode_error")
        log.error("$error: $ex")
        analyticsService.logError(ex)
    }

    private fun changeOnlineStatus(status: OnlineAccountStatus, save: Boolean = true) {
        if (signUpStatus.value != SignUpStatus.Finished) {
            signUpStatus.value = if (status.ordinal < OnlineAccountStatus.Validating.ordinal) {
                SignUpStatus.NotStarted
            } else {
                SignUpStatus.LinkedOnline
            }
        }

        if (onlineAccountStatus.value == OnlineAccountStatus.None) {
            isOnlineStatusRestored = true
        }

        onlineAccountStatus.value = status

        if (save) {
            configScope.launch { config.setPreference(CrowdNodeConfig.ONLINE_ACCOUNT_STATUS, status.ordinal) }
        }
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