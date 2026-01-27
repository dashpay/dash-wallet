/*
 * Copyright 2019 Dash Core Group.
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
package de.schildbach.wallet.ui.send

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.Constants
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.data.CoinJoinConfig
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.payments.MaxOutputAmountCoinJoinCoinSelector
import de.schildbach.wallet.payments.MaxOutputAmountCoinSelector
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.service.CoinJoinService
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import org.bitcoinj.coinjoin.CoinJoinCoinSelector
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.Context
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Transaction
import org.bitcoinj.evolution.AssetLockTransaction
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.wallet.AuthenticationKeyChain
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.bitcoinj.wallet.authentication.AuthenticationGroupExtension
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import javax.inject.Inject
import kotlin.math.min

class SendException(message: String) : Exception(message)
class InsufficientCoinJoinMoneyException(ex: InsufficientMoneyException) : InsufficientMoneyException(ex.missing, "${ex.message} [coinjoin]")

@HiltViewModel
class SendCoinsViewModel @Inject constructor(
    walletDataProvider: WalletDataProvider,
    walletApplication: WalletApplication,
    blockchainStateDao: BlockchainStateDao,
    val biometricHelper: BiometricHelper,
    private val analytics: AnalyticsService,
    private val configuration: Configuration,
    private val sendCoinsTaskRunner: SendCoinsTaskRunner,
    private val notificationService: NotificationService,
    private val platformRepo: PlatformRepo,
    private val dashPayContactRequestDao: DashPayContactRequestDao,
    coinJoinConfig: CoinJoinConfig,
    coinJoinService: CoinJoinService
) : SendCoinsBaseViewModel(walletDataProvider, configuration) {
    companion object {
        private val log = LoggerFactory.getLogger(SendCoinsViewModel::class.java)
        private val dryRunKey = ECKey()
    }

    enum class State {
        INPUT, // asks for confirmation
        SENDING, SENT, FAILED // sending states
    }

    var isQuickSend: Boolean = false

    private val _state = MutableLiveData(State.INPUT)
    val state: LiveData<State>
        get() = _state

    private val _maxOutputAmount = MutableLiveData<Coin>()
    val maxOutputAmount: LiveData<Coin>
        get() = _maxOutputAmount

    private val _currentAmount = MutableStateFlow(Coin.ZERO)
    var currentAmount: Coin = Coin.ZERO
        private set

    var dryrunSendRequest: SendRequest? = null
        private set
    var dryRunException: Exception? = null
        private set

    private val _dryRunSuccessful = MutableLiveData(false)
    val dryRunSuccessful: LiveData<Boolean>
        get() = _dryRunSuccessful
    private var dryRunGreedy: Boolean = true

    private val _isBlockchainReplaying = MutableLiveData<Boolean>()
    val isBlockchainReplaying: LiveData<Boolean>
        get() = _isBlockchainReplaying

    val isSpendingConfirmationEnabled: Boolean
        get() = configuration.spendingConfirmationEnabled

    var isDashToFiatPreferred: Boolean
        get() = configuration.isDashToFiatDirection
        set(value) { configuration.isDashToFiatDirection = value }

    val shouldPlaySounds: Boolean
        get() = !notificationService.isDoNotDisturb

    private val _contactData = MutableLiveData<UsernameSearchResult>()
    val contactData: LiveData<UsernameSearchResult>
        get() = _contactData

    private var _coinJoinActive = MutableStateFlow(false)
    val coinJoinActive: Flow<Boolean>
        get() = _coinJoinActive
    /** the resulting transaction is an asset lock transaction (default = false) */
    var isAssetLock = false

    init {
        blockchainStateDao.observeState()
            .filterNotNull()
            .onEach { state ->
                _isBlockchainReplaying.postValue(state.replaying)
            }
            .launchIn(viewModelScope)

        coinJoinService.observeMixing()
            .map { isMixing ->
                _coinJoinActive.value = isMixing
                if (!isMixing) {
                    MaxOutputAmountCoinSelector()
                } else {
                    MaxOutputAmountCoinJoinCoinSelector(wallet)
                }
            }
            .flatMapLatest { coinSelector ->
                walletDataProvider.observeBalance(Wallet.BalanceType.ESTIMATED, coinSelector)
            }
            .distinctUntilChanged()
            .onEach(_maxOutputAmount::postValue)
            .launchIn(viewModelScope)

        _currentAmount
            .debounce(150)
            .onEach { amount ->
                withContext(Dispatchers.IO) {
                    executeDryrun(amount)
                }
            }
            .launchIn(viewModelScope)

        _currentAmount.onEach { amount ->
            currentAmount = amount
        }.launchIn(viewModelScope)

        walletApplication.startBlockchainService(false)
    }

    override suspend fun initPaymentIntent(paymentIntent: PaymentIntent) {
        if (paymentIntent.hasPaymentRequestUrl()) {
            throw IllegalArgumentException(
                PaymentProtocolFragment::class.java.simpleName +
                        "class should be used to handle Payment requests (BIP70 and BIP270)"
            )
        }

        log.info("got {}", paymentIntent)
        val finalIntent = withContext(Dispatchers.IO) {
            checkIdentity(paymentIntent)
        }

        log.info("proceeding with {}", finalIntent)
        super.initPaymentIntent(finalIntent)
        _state.value = State.INPUT
        withContext(Dispatchers.IO) {
            executeDryrun(currentAmount)
        }
    }

    fun everythingPlausible(): Boolean {
        return state.value === State.INPUT && isPayeePlausible() && isAmountPlausible()
    }

    private suspend fun loadUserDataByUsername(username: String): UsernameSearchResult? {
        platformRepo.getLocalUserDataByUsername(username)?.run {
            return this
        }

        return try {
            platformRepo.searchUsernames(username, true).firstOrNull()
        } catch (ex: Exception) {
            analytics.logError(ex, "Failed to load user")
            null
        }
    }

    suspend fun loadUserDataByUserId(userId: String): UsernameSearchResult? {
        platformRepo.getLocalUserDataByUserId(userId)?.run {
            return this
        }

        return null
    }

    suspend fun signAndSendPayment(
        editedAmount: Coin,
        exchangeRate: ExchangeRate?,
        checkBalance: Boolean
    ): Transaction = withContext(Dispatchers.IO) {
        _state.postValue(State.SENDING)
        if (isAssetLock) {
            error("isAssetLock must be false, but is true")
        }
        val finalPaymentIntent = basePaymentIntent.mergeWithEditedValues(editedAmount, null)

        val transaction = try {
            val finalSendRequest = sendCoinsTaskRunner.createSendRequest(
                basePaymentIntent.mayEditAmount(),
                finalPaymentIntent,
                true,
                dryrunSendRequest!!.ensureMinRequiredFee,
                dryRunGreedy
            )
            finalSendRequest.memo = basePaymentIntent.memo
            finalSendRequest.exchangeRate = exchangeRate
            Context.propagate(wallet.context)
            sendCoinsTaskRunner.sendCoins(finalSendRequest, checkBalanceConditions = checkBalance)
        } catch (ex: Exception) {
            _state.postValue(State.FAILED)
            throw ex
        }

        _state.postValue(State.SENT)
        transaction
    }

    suspend fun signAndSendAssetLock(
        editedAmount: Coin,
        exchangeRate: ExchangeRate?,
        checkBalance: Boolean,
        key: ECKey,
        emptyWallet: Boolean
    ): Transaction = withContext(Dispatchers.IO) {
        _state.postValue(State.SENDING)
        if (!isAssetLock) {
            error("isAssetLock must be true, but is true")
        }
        val finalPaymentIntent = basePaymentIntent.mergeWithEditedValues(editedAmount, null)

        val transaction = try {
            var finalSendRequest = sendCoinsTaskRunner.createAssetLockSendRequest(
                basePaymentIntent.mayEditAmount(),
                finalPaymentIntent,
                true,
                dryrunSendRequest!!.ensureMinRequiredFee,
                key,
                dryRunGreedy
            )
            finalSendRequest.memo = basePaymentIntent.memo
            finalSendRequest.exchangeRate = exchangeRate
            Context.propagate(wallet.context)

            if (emptyWallet) {
                sendCoinsTaskRunner.signSendRequest(finalSendRequest)
                wallet.completeTx(finalSendRequest)

                // make sure that the asset lock payload matches the OP_RETURN output
                val outputValue = finalSendRequest.tx.outputs.first().value
                val assetLockedValue = (finalSendRequest.tx as AssetLockTransaction).assetLockPayload.creditOutputs.first().value
                if (assetLockedValue != outputValue) {
                    val newRequest = SendRequest.assetLock(wallet.params, key, outputValue, true)
                    newRequest.coinSelector = finalSendRequest.coinSelector
                    newRequest.returnChange = finalSendRequest.returnChange
                    newRequest.aesKey = finalSendRequest.aesKey
                    finalSendRequest = newRequest
                } else {
                    // this shouldn't happen
                    error("The asset lock value is the same as the output though emptying the wallet")
                }
            }

            sendCoinsTaskRunner.sendCoins(finalSendRequest, checkBalanceConditions = checkBalance)
        } catch (ex: Exception) {
            _state.postValue(State.FAILED)
            throw ex
        }

        _state.value = State.SENT
        transaction
    }

    fun allowBiometric(): Boolean {
        val thresholdAmount = Coin.parseCoin(configuration.biometricLimit.toString())
        return currentAmount.isLessThan(thresholdAmount)
    }

    suspend fun getPendingBalance(): Coin = withContext(Dispatchers.IO) {
        val estimated = wallet.getBalance(Wallet.BalanceType.ESTIMATED)
        val available = wallet.getBalance(Wallet.BalanceType.AVAILABLE)

        estimated.subtract(available)
    }

    fun shouldAdjustAmount(): Boolean {
        return dryRunException is InsufficientMoneyException &&
            currentAmount.isLessThan(maxOutputAmount.value ?: Coin.ZERO)
    }

    fun getAdjustedAmount(): Coin {
        val missing = (dryRunException as? InsufficientMoneyException)?.missing ?: Coin.ZERO
        return currentAmount.subtract(missing)
    }

    fun resetState() {
        _state.value = State.INPUT
    }

    fun logSendSuccess(dashToFiat: Boolean, source: String) {
        if (isQuickSend) {
            analytics.logEvent(AnalyticsConstants.LockScreen.SCAN_TO_SEND_SUCCESS, mapOf())
        } else if (source == "explore") {
            analytics.logEvent(AnalyticsConstants.Explore.PAY_WITH_DASH_SUCCESS, mapOf())
        } else {
            analytics.logEvent(if (contactData.value == null) {
                AnalyticsConstants.SendReceive.SEND_SUCCESS
            } else {
                AnalyticsConstants.SendReceive.SEND_USERNAME_SUCCESS
            }, mapOf())

            analytics.logEvent(if (dashToFiat) {
                AnalyticsConstants.SendReceive.ENTER_AMOUNT_DASH
            } else {
                AnalyticsConstants.SendReceive.ENTER_AMOUNT_FIAT
            }, mapOf())
        }
    }

    fun logSendError(source: String) {
         if (source == "explore") {
            analytics.logEvent(AnalyticsConstants.Explore.PAY_WITH_DASH_ERROR, mapOf())
        } else {
             analytics.logEvent(
                 if (contactData.value == null) {
                     AnalyticsConstants.SendReceive.SEND_ERROR
                 } else {
                     AnalyticsConstants.SendReceive.SEND_USERNAME_ERROR
                 }, mapOf()
             )
         }
    }

    fun logSend() {
        analytics.logEvent(if (isQuickSend) {
            AnalyticsConstants.LockScreen.SCAN_TO_SEND_SEND
        } else {
            AnalyticsConstants.SendReceive.ENTER_AMOUNT_SEND
        }, mapOf())
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }

    private fun isPayeePlausible(): Boolean {
        return isInitialized && basePaymentIntent.hasOutputs()
    }

    /** creates a send request using the payment intent and [isAssetLock] */
    private fun createSendRequest(
        mayEditAmount: Boolean,
        paymentIntent: PaymentIntent,
        signInputs: Boolean,
        forceEnsureMinRequiredFee: Boolean
        //useGreedyAlgorithm: Boolean = true
    ): SendRequest {
        return if (!isAssetLock) {
            sendCoinsTaskRunner.createSendRequest(
                mayEditAmount,
                paymentIntent,
                signInputs,
                forceEnsureMinRequiredFee
            )
        } else {
            sendCoinsTaskRunner.createAssetLockSendRequest(
                mayEditAmount,
                paymentIntent,
                signInputs,
                forceEnsureMinRequiredFee,
                dryRunKey
            )
        }
    }

    fun setAmount(amount: Coin) {
        _currentAmount.value = amount
    }

    private fun executeDryrun(amount: Coin) {
        dryrunSendRequest = null
        dryRunException = null
        dryRunGreedy = false

        if (state.value != State.INPUT || amount == Coin.ZERO) {
            _dryRunSuccessful.postValue(false)
            return
        }
        log.info("executeDryRun started")
        val dummyAddress = wallet.currentReceiveAddress() // won't be used, tx is never committed
        val finalPaymentIntent = basePaymentIntent.mergeWithEditedValues(amount, dummyAddress)

        try {
            Context.propagate(wallet.context)
            // check regular payment
            var sendRequest = createSendRequest(
                basePaymentIntent.mayEditAmount(),
                finalPaymentIntent,
                signInputs = false,
                forceEnsureMinRequiredFee = false
            )
            dryRunGreedy = true
            log.info("  start completeTx")
            wallet.completeTx(sendRequest)

//            if (checkDust(sendRequest)) {
//                sendRequest = createSendRequest(
//                    basePaymentIntent.mayEditAmount(),
//                    finalPaymentIntent,
//                    signInputs = false,
//                    forceEnsureMinRequiredFee = true
//                )
//                log.info("  start completeTx again")
//                wallet.completeTx(sendRequest)
//            }

//            if (sendCoinsTaskRunner.isFeeTooHigh(sendRequest.tx)) {
//                sendRequest = createSendRequest(
//                    basePaymentIntent.mayEditAmount(),
//                    finalPaymentIntent,
//                    signInputs = false,
//                    forceEnsureMinRequiredFee = true,
//                    useGreedyAlgorithm = false
//                )
//                log.info("  start completeTx again")
//                wallet.completeTx(sendRequest)
//                dryRunGreedy = false
//            }
            dryRunGreedy = sendRequest.coinSelector is CoinJoinCoinSelector && !sendRequest.returnChange
            dryrunSendRequest = sendRequest
            log.info("executeDryRun finished")
            _dryRunSuccessful.postValue(true)
        } catch (ex: Exception) {
            dryRunException = if (ex is InsufficientMoneyException && _coinJoinActive.value && !currentAmount.isGreaterThan(wallet.getBalance(MaxOutputAmountCoinSelector()))) {
                 InsufficientCoinJoinMoneyException(ex)
            } else {
                ex
            }
            _dryRunSuccessful.postValue(false)
        }
    }

    private fun isAmountPlausible(): Boolean {
        if (!isInitialized) {
            return false
        }

        return if (basePaymentIntent.mayEditAmount()) {
            currentAmount.isGreaterThan(Coin.ZERO)
        } else {
            basePaymentIntent.hasAmount()
        }
    }

    private suspend fun checkIdentity(paymentIntent: PaymentIntent): PaymentIntent {
        var isDashUserOrNotMe = platformRepo.hasIdentity()

        // make sure that this payment intent is not to me
        if (paymentIntent.isIdentityPaymentRequest &&
            paymentIntent.payeeUsername != null &&
            platformRepo.hasIdentity() &&
            platformRepo.hasUsername() &&
            paymentIntent.payeeUsername == platformRepo.getUsername()
        ) {
            isDashUserOrNotMe = false
        }

        if (isDashUserOrNotMe && paymentIntent.isIdentityPaymentRequest) {
            if (paymentIntent.payeeUsername != null) {
                val searchResult = loadUserDataByUsername(paymentIntent.payeeUsername)

                if (searchResult != null) {
                    return handleDashIdentity(searchResult, paymentIntent)
                } else {
                    log.error("error loading identity for username {}", paymentIntent.payeeUsername)
                    throw SendException("error loading identity for username ${paymentIntent.payeeUsername}")
                }
            } else if (paymentIntent.payeeUserId != null) {
                val searchResult = loadUserDataByUserId(paymentIntent.payeeUserId)

                if (searchResult != null) {
                    return handleDashIdentity(searchResult, paymentIntent)
                } else {
                    log.error("error loading identity for userId {}", paymentIntent.payeeUserId)
                    throw SendException("error loading identity for userId ${paymentIntent.payeeUserId}")
                }
            } else {
                throw IllegalStateException("not identity payment request")
            }
        } else {
            return paymentIntent
        }
    }

    private suspend fun handleDashIdentity(
        userData: UsernameSearchResult,
        paymentIntent: PaymentIntent
    ): PaymentIntent {
        _contactData.postValue(userData)

        if (!userData.requestReceived) {
            return paymentIntent
        }

        val dashPayProfile = userData.dashPayProfile
        val dashPayContactRequests = dashPayContactRequestDao.loadToOthers(dashPayProfile.userId)
        val map = HashMap<Long, DashPayContactRequest>(dashPayContactRequests.size)

        // This is currently using the first version, but it should use the version specified
        // in the ContactInfo.accountRef related to this contact.  Ideally the user should
        // approve of a change to the "accountReference" that is used.
        var firstTimestamp = Long.MAX_VALUE
        for (contactRequest in dashPayContactRequests) {
            map[contactRequest.timestamp] = contactRequest
            firstTimestamp = min(firstTimestamp, contactRequest.timestamp)
        }

        val mostRecentContactRequest = map[firstTimestamp]
        val address = platformRepo.getNextContactAddress(
            dashPayProfile.userId,
            mostRecentContactRequest!!.accountReference
        )
        return if (address != null) {
            PaymentIntent.fromAddressWithIdentity(
                Address.fromBase58(Constants.NETWORK_PARAMETERS, address.toBase58()),
                dashPayProfile.userId,
                paymentIntent.amount
            )
        } else {
            throw SendException("Failed to get contact address for ${dashPayProfile.userId}")
        }
    }

    fun getNextKey(): ECKey {
        val authGroup = wallet.getKeyChainExtension(
            AuthenticationGroupExtension.EXTENSION_ID
        ) as AuthenticationGroupExtension
        return authGroup.freshKey(
            AuthenticationKeyChain.KeyChainType.BLOCKCHAIN_IDENTITY_TOPUP
        ) as ECKey
    }
}
