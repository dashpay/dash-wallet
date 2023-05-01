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
import de.schildbach.wallet.data.PaymentIntent
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.database.entity.DashPayContactRequest
import de.schildbach.wallet.payments.MaxOutputAmountCoinSelector
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Address
import org.bitcoinj.core.Coin
import org.bitcoinj.core.InsufficientMoneyException
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.wallet.SendRequest
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import javax.inject.Inject

class SendException(message: String) : Exception(message)

@HiltViewModel
class SendCoinsViewModel @Inject constructor(
    walletDataProvider: WalletDataProvider,
    walletApplication: WalletApplication,
    blockchainStateDao: BlockchainStateDao,
    val biometricHelper: BiometricHelper,
    private val analytics: AnalyticsService,
    private val configuration: Configuration,
    private val sendCoinsTaskRunner: SendCoinsTaskRunner,
    private val platformRepo: PlatformRepo,
    private val dashPayContactRequestDao: DashPayContactRequestDao
) : SendCoinsBaseViewModel(walletDataProvider, configuration) {
    companion object {
        private val log = LoggerFactory.getLogger(SendCoinsViewModel::class.java)
    }

    enum class State {
        INPUT, // asks for confirmation
        SENDING, SENT, FAILED // sending states
    }

    private val _state = MutableLiveData(State.INPUT)
    val state: LiveData<State>
        get() = _state

    private val _maxOutputAmount = MutableLiveData<Coin>()
    val maxOutputAmount: LiveData<Coin>
        get() = _maxOutputAmount

    var currentAmount: Coin = Coin.ZERO
        set(value) {
            field = value
            executeDryrun(value)
        }

    var dryrunSendRequest: SendRequest? = null
        private set
    var dryRunException: Exception? = null
        private set

    private val _dryRunSuccessful = MutableLiveData(false)
    val dryRunSuccessful: LiveData<Boolean>
        get() = _dryRunSuccessful

    private val _isBlockchainReplaying = MutableLiveData<Boolean>()
    val isBlockchainReplaying: LiveData<Boolean>
        get() = _isBlockchainReplaying

    val isSpendingConfirmationEnabled: Boolean
        get() = configuration.spendingConfirmationEnabled

    var isDashToFiatPreferred: Boolean
        get() = configuration.isDashToFiatDirection
        set(value) { configuration.isDashToFiatDirection = value }

    private val _contactData = MutableLiveData<UsernameSearchResult>()
    val contactData: LiveData<UsernameSearchResult>
        get() = _contactData

    init {
        blockchainStateDao.observeState()
            .filterNotNull()
            .onEach { state ->
                _isBlockchainReplaying.postValue(state.replaying)
            }
            .launchIn(viewModelScope)

        walletDataProvider.observeBalance(coinSelector = MaxOutputAmountCoinSelector())
            .distinctUntilChanged()
            .onEach(_maxOutputAmount::postValue)
            .launchIn(viewModelScope)

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
        val finalIntent = checkIdentity(paymentIntent)

        log.info("proceeding with {}", finalIntent)
        super.initPaymentIntent(finalIntent)
        _state.value = State.INPUT
        executeDryrun(currentAmount)
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
    ): Transaction {
        _state.value = State.SENDING
        val finalPaymentIntent = basePaymentIntent.mergeWithEditedValues(editedAmount, null)

        val transaction = try {
            val finalSendRequest = sendCoinsTaskRunner.createSendRequest(
                basePaymentIntent.mayEditAmount(),
                finalPaymentIntent,
                true,
                dryrunSendRequest!!.ensureMinRequiredFee
            )
            finalSendRequest.memo = basePaymentIntent.memo
            finalSendRequest.exchangeRate = exchangeRate

            sendCoinsTaskRunner.sendCoins(finalSendRequest, checkBalanceConditions = checkBalance)
        } catch (ex: Exception) {
            _state.value = State.FAILED
            throw ex
        }

        _state.value = State.SENT
        return transaction
    }

    fun allowBiometric(): Boolean {
        val thresholdAmount = Coin.parseCoin(configuration.biometricLimit.toString())
        return currentAmount.isLessThan(thresholdAmount)
    }

    fun getPendingBalance(): Coin {
        val estimated = wallet.getBalance(Wallet.BalanceType.ESTIMATED)
        val available = wallet.getBalance(Wallet.BalanceType.AVAILABLE)

        return estimated.subtract(available)
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

    fun logSentEvent(dashToFiat: Boolean) {
        if (dashToFiat) {
            analytics.logEvent(AnalyticsConstants.SendReceive.ENTER_AMOUNT_DASH, mapOf())
        } else {
            analytics.logEvent(AnalyticsConstants.SendReceive.ENTER_AMOUNT_FIAT, mapOf())
        }
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }

    fun shouldConfirm(): Boolean {
        return basePaymentIntent.amount != null && basePaymentIntent.amount.isGreaterThan(Coin.ZERO) &&
            _isBlockchainReplaying.value != true
    }

    private fun isPayeePlausible(): Boolean {
        return isInitialized && basePaymentIntent.hasOutputs()
    }

    private fun executeDryrun(amount: Coin) {
        dryrunSendRequest = null
        dryRunException = null

        if (state.value != State.INPUT || amount == Coin.ZERO) {
            _dryRunSuccessful.value = false
            return
        }

        val dummyAddress = wallet.currentReceiveAddress() // won't be used, tx is never committed
        val finalPaymentIntent = basePaymentIntent.mergeWithEditedValues(amount, dummyAddress)

        try {
            // check regular payment
            var sendRequest = sendCoinsTaskRunner.createSendRequest(
                basePaymentIntent.mayEditAmount(),
                finalPaymentIntent,
                signInputs = false,
                forceEnsureMinRequiredFee = false
            )
            wallet.completeTx(sendRequest)

            if (checkDust(sendRequest)) {
                sendRequest = sendCoinsTaskRunner.createSendRequest(
                    basePaymentIntent.mayEditAmount(),
                    finalPaymentIntent,
                    signInputs = false,
                    forceEnsureMinRequiredFee = true
                )
                wallet.completeTx(sendRequest)
            }

            dryrunSendRequest = sendRequest
            _dryRunSuccessful.value = true
        } catch (ex: Exception) {
            dryRunException = ex
            _dryRunSuccessful.value = false
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
        var isDashUserOrNotMe = platformRepo.hasIdentity

        // make sure that this payment intent is not to me
        if (paymentIntent.isIdentityPaymentRequest &&
            paymentIntent.payeeUsername != null &&
            platformRepo.hasIdentity &&
            platformRepo.blockchainIdentity.currentUsername != null &&
            paymentIntent.payeeUsername == platformRepo.blockchainIdentity.currentUsername
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
        var firstTimestamp = System.currentTimeMillis()
        for (contactRequest in dashPayContactRequests) {
            map[contactRequest.timestamp] = contactRequest
            firstTimestamp = contactRequest.timestamp.coerceAtMost(firstTimestamp)
        }
        val mostRecentContactRequest = map[firstTimestamp]
        val address = platformRepo.getNextContactAddress(
            dashPayProfile.userId,
            mostRecentContactRequest!!.accountReference
        )
        return PaymentIntent.fromAddressWithIdentity(
            Address.fromBase58(Constants.NETWORK_PARAMETERS, address.toBase58()),
            dashPayProfile.userId,
            paymentIntent.amount
        )
    }
}
