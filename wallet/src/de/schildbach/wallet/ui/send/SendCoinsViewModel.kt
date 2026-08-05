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
import org.dash.wallet.common.data.PaymentIntent
import de.schildbach.wallet.data.UsernameSearchResult
import de.schildbach.wallet.database.dao.BlockchainStateDao
import de.schildbach.wallet.database.dao.DashPayContactRequestDao
import de.schildbach.wallet.payments.SendCoinsTaskRunner
import de.schildbach.wallet.security.BiometricHelper
import de.schildbach.wallet.service.platform.sdk.SEND_ALL_FEE_RESERVE_DUFFS
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.util.AnrException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
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
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.services.NotificationService
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.slf4j.LoggerFactory
import javax.inject.Inject
import de.schildbach.wallet.util.format
import de.schildbach.wallet.util.setAmount
import de.schildbach.wallet.util.setFiatAmount
import de.schildbach.wallet.util.toDashjFiat
import de.schildbach.wallet.util.toDashjCoin
import de.schildbach.wallet.util.toNeutralCoin
import de.schildbach.wallet.util.toNeutralFiat
import de.schildbach.wallet.util.toTxId
import de.schildbach.wallet.util.toSha256Hash

class SendException(message: String) : Exception(message)

@HiltViewModel
class SendCoinsViewModel @Inject constructor(
    walletDataProvider: WalletData,
    walletApplication: WalletApplication,
    blockchainStateDao: BlockchainStateDao,
    val biometricHelper: BiometricHelper,
    private val analytics: AnalyticsService,
    private val configuration: Configuration,
    private val sendCoinsTaskRunner: SendCoinsTaskRunner,
    private val notificationService: NotificationService,
    private val identityRepository: IdentityRepository,
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

    /**
     * Whether the last dry run failed for lack of funds, as a plain boolean so
     * screens do not have to know the dashj exception type (Phase 3 keeps the
     * dashj surface inside the ViewModel).
     */
    val isInsufficientFunds: Boolean
        get() = dryRunException is InsufficientMoneyException

    /**
     * Phase 5d: a DISPLAY-only, deterministic fee estimate for the confirm
     * dialog when the post-cutover dry-run does NOT complete the tx (so
     * `dryrunSendRequest.tx.fee` is null — no inputs are attached). Null on the
     * pre-cutover path, where `completeTx` sets the real dashj fee. The SDK
     * computes and applies the actual (typically much smaller) fee at send.
     */
    var dryRunFeeEstimate: Coin? = null
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

    val shouldPlaySounds: Boolean
        get() = !notificationService.isDoNotDisturb

    private val _contactData = MutableLiveData<UsernameSearchResult>()
    val contactData: LiveData<UsernameSearchResult>
        get() = _contactData


    init {
        blockchainStateDao.observeState()
            .filterNotNull()
            .onEach { state ->
                // NOT-SYNCED is folded into the same UI signal as REPLAYING.
                // Post-cutover the SDK does a from-scratch compact-filter scan
                // on first launch; a send attempted inside that window failed
                // deep in the SDK send path and surfaced the raw exception
                // text ("…ROLLBACK_CUTOVER…") under "Problem sending coins!".
                // Gating here reuses the existing, translated
                // send_coins_fragment_hint_replaying hint and the existing
                // blockContinue wiring in SendCoinsFragment.
                //
                // Deliberately NOT done by flipping the persisted `replaying`
                // flag: that flag has 15+ consumers (shielded transfers,
                // mixed-funds migration, exchange-rate stamping, DashPay
                // contact payments) and must keep its own meaning.
                _isBlockchainReplaying.postValue(state.replaying || !state.isSynced())
            }
            .launchIn(viewModelScope)

        // DISPLAY-only "max sendable" feed. Post-cutover the dashj max-output balance
        // freezes at 0 (dashj is held), so this routes through the cutover overlay in
        // WalletApplication.observeMaxOutputBalance() and shows the SDK's live total;
        // pre-cutover it is the dashj max-output value unchanged. The real send's coin
        // selection is unaffected — SendCoinsTaskRunner owns that separately.
        walletDataProvider.observeMaxOutputBalance()
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
        withContext(Dispatchers.Main.immediate) {
            _state.value = State.INPUT
        }
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
            identityRepository.searchUsernames(username, true).firstOrNull()
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
        Context.propagate(wallet.context)
        _state.postValue(State.SENDING)
        val finalPaymentIntent = basePaymentIntent.mergeWithEditedValues(editedAmount.toNeutralCoin(), null)

        val transaction = try {
            val finalSendRequest = sendCoinsTaskRunner.createSendRequest(
                basePaymentIntent.mayEditAmount(),
                finalPaymentIntent,
                true,
                dryrunSendRequest!!.ensureMinRequiredFee
            )
            // Post-cutover, carry the cutover-aware dry-run's send-all decision.
            // createSendRequest derives emptyWallet from the dashj max-output
            // balance, which is held at 0 while the engine is cut over, so it can
            // never flag a real send-max — that would route the Max send as a
            // plain SDK send of the full balance (no fee headroom → the SDK send
            // fails closed). executeDryrun is the authority on send-all here.
            // Pre-cutover both requests compute emptyWallet identically from the
            // same balance/amount, so this override is gated on the committed
            // cutover and the dashj path is otherwise untouched.
            if (sendCoinsTaskRunner.isCutoverCommitted()) {
                finalSendRequest.emptyWallet = dryrunSendRequest!!.emptyWallet
            }
            finalSendRequest.memo = basePaymentIntent.memo
            finalSendRequest.exchangeRate = exchangeRate
            Context.propagate(wallet.context)
            // Thread the payment intent's address — WHO the user is paying —
            // to the send funnel. Engine-neutral: the funnel needs it to keep
            // the payment identifiable when the recipient is one of this
            // wallet's OWN addresses (a self-send: recipient and change are
            // both "mine", so the request's outputs alone can't name the
            // payment — the on-device 11.10.44 self-send failure). Null when
            // the intent has no plain address; the funnel then applies its
            // usual conservative rules.
            val intendedRecipient = if (finalPaymentIntent.hasAddress()) {
                try {
                    Address.fromString(
                        Constants.NETWORK_PARAMETERS,
                        finalPaymentIntent.getAddress(Constants.ADDRESS_NETWORK)
                    )
                } catch (ex: Exception) {
                    null
                }
            } else {
                null
            }
            sendCoinsTaskRunner.sendCoins(
                finalSendRequest,
                checkBalanceConditions = checkBalance,
                intendedRecipient = intendedRecipient
            )
        } catch (ex: Exception) {
            _state.postValue(State.FAILED)
            throw ex
        }

        _state.postValue(State.SENT)
        transaction
    }


    fun allowBiometric(): Boolean {
        val thresholdAmount = Coin.parseCoin(configuration.biometricLimit.toString())
        return currentAmount.isLessThan(thresholdAmount)
    }

    suspend fun getPendingBalance(): Coin = withContext(Dispatchers.IO) {
        Context.propagate(wallet.context)
        val estimated = wallet.getBalance(Wallet.BalanceType.ESTIMATED)
        val available = wallet.getBalance(Wallet.BalanceType.AVAILABLE)

        estimated.subtract(available)
    }

    fun shouldAdjustAmount(): Boolean {
        // Fix 5 (belt-and-suspenders): only auto-adjust when the corrected
        // amount is strictly positive. A bad `missing` (e.g. missing >= amount)
        // would otherwise yield a non-positive value that AmountView snaps to
        // "0", blanking the field mid-entry.
        return dryRunException is InsufficientMoneyException &&
            currentAmount.isLessThan(maxOutputAmount.value ?: Coin.ZERO) &&
            getAdjustedAmount().isGreaterThan(Coin.ZERO)
    }

    fun getAdjustedAmount(): Coin {
        val missing = (dryRunException as? InsufficientMoneyException)?.missing ?: Coin.ZERO
        val adjusted = currentAmount.subtract(missing)
        // Never return a negative amount — the caller feeds this straight into
        // AmountView.setAmount, and a negative snaps to "0" (clearing the field).
        return if (adjusted.isNegative) Coin.ZERO else adjusted
    }

    fun resetState() {
        _state.postValue(State.INPUT)
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

    /** creates a send request using the payment intent */
    private fun createSendRequest(
        mayEditAmount: Boolean,
        paymentIntent: PaymentIntent,
        signInputs: Boolean,
        forceEnsureMinRequiredFee: Boolean
        //useGreedyAlgorithm: Boolean = true
    ): SendRequest {
        return sendCoinsTaskRunner.createSendRequest(
            mayEditAmount,
            paymentIntent,
            signInputs,
            forceEnsureMinRequiredFee
        )
    }

    fun setAmount(amount: Coin) {
        _currentAmount.value = amount
    }

    private suspend fun executeDryrun(amount: Coin) {
        dryrunSendRequest = null
        dryRunException = null
        dryRunFeeEstimate = null

        if (state.value != State.INPUT || amount == Coin.ZERO) {
            _dryRunSuccessful.postValue(false)
            return
        }
        log.info("executeDryRun started")
        val currentThread = Thread.currentThread()
        val monitorJob = viewModelScope.launch(Dispatchers.Default) {
            delay(1000)
            log.warn("executeDryrun is taking longer than 1 second")
            try {
                val anrException = AnrException(currentThread)
                anrException.logProcessMap()
            } catch (e: Exception) {
                log.error("Failed to dump thread traces during executeDryrun", e)
            }
        }
        val dummyAddress = wallet.currentReceiveAddress() // won't be used, tx is never committed
        val finalPaymentIntent = basePaymentIntent.mergeWithEditedValues(amount.toNeutralCoin(), dummyAddress.toBase58())

        try {
            Context.propagate(wallet.context)
            // check regular payment
            var sendRequest = createSendRequest(
                basePaymentIntent.mayEditAmount(),
                finalPaymentIntent,
                signInputs = false,
                forceEnsureMinRequiredFee = false
            )

            // Phase 5d (Bug 4): once the cutover is committed the dashj engine is
            // HELD with 0 UTXOs, so wallet.completeTx below would ALWAYS throw
            // InsufficientMoneyException and wrongly block the Send button — even
            // though the real send routes through the SDK (SendCoinsTaskRunner /
            // SdkL1SendService) which owns its own funds. Validate affordability
            // against the SDK-overlaid maxOutputAmount instead. This stays a
            // NON-COMMITTING dry-run: it only reads a balance and builds an
            // UNSIGNED request — no completeTx / signSendRequest / broadcast — so
            // the real SDK send (which does its own selection, signing and the
            // funding-gate/send-all-floor preflight) remains the sole authority
            // and any true shortfall there still fails closed (NotBroadcast,
            // pre-broadcast, no double-pay). Pre-cutover this branch is skipped
            // and the dashj completeTx path below is byte-identical to before.
            if (sendCoinsTaskRunner.isCutoverCommitted()) {
                val maxOutput = maxOutputAmount.value ?: Coin.ZERO
                // Reserve = the SDK's own send-all fee reserve
                // (SEND_ALL_FEE_RESERVE_DUFFS, SdkL1SendService.kt) — ~40x a
                // typical 1-in/2-out fee, so the gate is strictly NO LOOSER than
                // the SDK plain-send precondition (amount + actual fee <=
                // spendable): a dry-run "pass" cannot reach an SDK send that then
                // reports insufficient funds. It doubles as the deterministic,
                // display-only fee preview shown in the confirm dialog.
                val feeReserve = Coin.valueOf(SEND_ALL_FEE_RESERVE_DUFFS)

                // Send-max (drain) detection. An editable amount equal to the
                // full SDK-overlaid balance is a send-all: mirrors the
                // pre-cutover dashj emptyWallet rule (maxOutputBalance == amount,
                // SendCoinsTaskRunner.createSendRequest) but keyed off the
                // SDK-overlaid balance — dashj's max-output balance is 0 while the
                // engine is held, so that rule can never fire post-cutover and the
                // Max/send-all path would otherwise be routed as a plain send of
                // the FULL balance (no room for the fee → SDK insufficient-funds
                // → the send fails). A drain takes the fee OUT of the amount
                // (delivered = total − fee), so the plain-send reserve-headroom
                // gate below does NOT apply here. The SDK drain's own send-all
                // floor (spendable − reserve, SdkL1SendService.kt:602-660) is the
                // real gate: it fails closed (NotBroadcast, pre-broadcast, no
                // double-pay) on any shortfall and refuses outright while any
                // app-locked (CrowdNode) output exists — so passing the dry-run
                // here can never be looser than what the drain actually accepts.
                val isSendAll = basePaymentIntent.mayEditAmount() &&
                    maxOutput.isPositive &&
                    amount == maxOutput

                if (isSendAll) {
                    // Flag the UNSIGNED dry-run request as emptyWallet so both the
                    // confirm dialog (gross/delivered split) and the real send
                    // (which carries this decision — see signAndSendPayment) route
                    // through the SDK drain. Still non-committing: no completeTx /
                    // signSendRequest / broadcast happens here.
                    sendRequest.emptyWallet = true
                    dryRunFeeEstimate = feeReserve // display-only floor; the drain pays the smaller real fee
                    dryrunSendRequest = sendRequest
                    log.info("executeDryRun finished (cutover-aware: SDK send-all / drain)")
                    monitorJob.cancel()
                    _dryRunSuccessful.postValue(true)
                    return
                }

                if (amount.add(feeReserve).isGreaterThan(maxOutput)) {
                    // Same signal the dashj path raises, so the existing
                    // insufficient-funds message and the (clamped) auto-adjust
                    // behave identically.
                    throw InsufficientMoneyException(amount.add(feeReserve).subtract(maxOutput))
                }
                dryRunFeeEstimate = feeReserve
                dryrunSendRequest = sendRequest // UNSIGNED, not completed — never broadcast
                log.info("executeDryRun finished (cutover-aware: SDK-overlaid affordability)")
                monitorJob.cancel()
                _dryRunSuccessful.postValue(true)
                return
            }

            log.info("  start completeTx")
            wallet.completeTx(sendRequest)

            dryrunSendRequest = sendRequest
            log.info("executeDryRun finished")
            monitorJob.cancel()
            _dryRunSuccessful.postValue(true)
        } catch (ex: Exception) {
            dryRunException = ex
            monitorJob.cancel()
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
        var isDashUserOrNotMe = identityRepository.hasIdentity()

        // make sure that this payment intent is not to me
        if (paymentIntent.isIdentityPaymentRequest &&
            paymentIntent.payeeUsername != null &&
            identityRepository.hasIdentity() &&
            identityRepository.hasUsername() &&
            paymentIntent.payeeUsername == identityRepository.getUsername()
        ) {
            isDashUserOrNotMe = false
        }

        if (isDashUserOrNotMe && paymentIntent.isIdentityPaymentRequest) {
            if (paymentIntent.payeeUsername != null) {
                val searchResult = loadUserDataByUsername(paymentIntent.payeeUsername!!)

                if (searchResult != null) {
                    return handleDashIdentity(searchResult, paymentIntent)
                } else {
                    log.error("error loading identity for username {}", paymentIntent.payeeUsername)
                    throw SendException("error loading identity for username ${paymentIntent.payeeUsername}")
                }
            } else if (paymentIntent.payeeUserId != null) {
                val searchResult = loadUserDataByUserId(paymentIntent.payeeUserId!!)

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

        // The accountReference must come from the contact request THIS contact addressed to US:
        // that request carries their incoming-funds xpub for our shared friendship chain, and it is
        // the chain we pay them on. `requestReceived` above guarantees it exists.
        //
        // Two things this must not do, because both send money to the wrong person:
        //  - loadToOthers() is `WHERE userId = :userId`, i.e. EVERY request this contact authored,
        //    to anyone. It must be narrowed to the ones addressed to us before choosing.
        //  - when several remain (a re-issued request, a restored wallet with a fuller contact
        //    graph), the newest is the live channel. The previous code took min(timestamp) under
        //    the name `mostRecentContactRequest` — the OLDEST — which is how an iOS build with the
        //    same defect paid a 0.3 tDASH contact payment to a different contact entirely
        //    (2026-08-06 QA, tx 8d2a994c…).
        val receivedFromContact = userData.fromContactRequest!!
        val ourUserId = receivedFromContact.toUserId
        val contactRequest = dashPayContactRequestDao.loadToOthers(dashPayProfile.userId)
            .filter { it.toUserId == ourUserId }
            .maxByOrNull { it.timestamp }
            ?: receivedFromContact

        val address = identityRepository.getNextContactAddress(
            dashPayProfile.userId,
            contactRequest.accountReference
        )
        return if (address != null) {
            PaymentIntent.fromAddressWithIdentity(
                address.toBase58(),
                dashPayProfile.userId,
                paymentIntent.amount
            )
        } else {
            throw SendException("Failed to get contact address for ${dashPayProfile.userId}")
        }
    }

}
