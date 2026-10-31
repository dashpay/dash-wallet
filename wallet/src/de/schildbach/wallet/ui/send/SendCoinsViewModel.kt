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
import androidx.lifecycle.SavedStateHandle
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
import de.schildbach.wallet.service.platform.sdk.ContactAddressAlreadyPaidException
import de.schildbach.wallet.service.platform.sdk.ContactPaymentAddress
import de.schildbach.wallet.service.platform.sdk.ContactPaymentAddressProvider
import de.schildbach.wallet.service.platform.sdk.ContactPaymentTarget
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.util.AnrException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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

/**
 * The payee could not be resolved; the screen shows it (SendCoinsFragment) and
 * nothing can be sent until a later resolution succeeds.
 */
class SendException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A send refused before anything was built or sent: an earlier send from this screen may have
 * gone out ([SendCoinsViewModel.paymentOutcomeUnknown]), or another is still in progress.
 */
class SendRefusedException(message: String) : Exception(message)

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
    private val dashPayContactRequestDao: DashPayContactRequestDao,
    private val contactPaymentAddressProvider: ContactPaymentAddressProvider,
    private val savedStateHandle: SavedStateHandle
) : SendCoinsBaseViewModel(walletDataProvider, configuration) {
    companion object {
        private val log = LoggerFactory.getLogger(SendCoinsViewModel::class.java)
        private const val KEY_PAYMENT_OUTCOME_UNKNOWN = "payment_outcome_unknown"
    }

    /**
     * True once a send from this screen may have gone out with its outcome unknown. Terminal:
     * the screen never returns to a confirmable INPUT and no further send is accepted. Kept in
     * [savedStateHandle] so a recreated screen (or process) still refuses instead of offering the
     * same payment again, which could pay twice once the SDK's reservation has expired.
     */
    val paymentOutcomeUnknown: StateFlow<Boolean> =
        savedStateHandle.getStateFlow(KEY_PAYMENT_OUTCOME_UNKNOWN, false)

    /** Held while one send is in progress (see [signAndSendPayment]). */
    private val sendInFlight = AtomicBoolean(false)

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

    /** The amount the screen shows — what the next dry run checks. */
    val currentAmount: Coin
        get() = _currentAmount.value

    /**
     * A successful dry run: what it checked, for the confirmation dialog and
     * the send. Immutable; only ever handed out by [currentDryRun] — i.e. while
     * it was built for the screen's current payee and amount.
     *
     * @property amount the amount it checked.
     * @property request the UNSIGNED request it built (never broadcast).
     * @property emptyWallet whether it is a send-all ("Max") request — taken
     *  from [request] when it was published, never from a later edit.
     * @property feeEstimate Phase 5d: a DISPLAY-only, deterministic fee for the
     *  confirm dialog when the post-cutover dry run does NOT complete the tx
     *  (so `request.tx.fee` is null — no inputs are attached). Null on the
     *  pre-cutover path, where `completeTx` sets the real dashj fee. The SDK
     *  computes and applies the actual (typically much smaller) fee at send.
     */
    class DryRunResult(
        val amount: Coin,
        val request: SendRequest,
        val emptyWallet: Boolean,
        val feeEstimate: Coin?
    )

    /**
     * What one [executeDryrun] concluded and the inputs it concluded it FOR —
     * published as one immutable value, so a reader never pairs one dry run's
     * request with another's inputs (PR #1608 review: after a "Max" amount was
     * edited down, the Max dry run's request stayed readable until the next
     * dry run started, and the send then carried its send-all flag — draining
     * the wallet for the smaller amount).
     *
     * @property payee the [ResolvedPayee] it checked, compared by identity:
     *  every resolution publishes a new one, even for the same address.
     * @property sendAllBalance for a post-cutover send-all, the SDK-overlaid
     *  balance it equalled — the send-all decision holds only while the
     *  balance is still that; null otherwise (pre-cutover, dashj decides
     *  send-all from its own balance at send time).
     * @property checkedBalance for a post-cutover dry run, the SDK-overlaid
     *  balance it decided on (send-all or plain, affordable or not); null
     *  pre-cutover, where the decision does not read it. While it is not the
     *  current balance the payment is checked again ([isBalanceStale]).
     * @property cutoverCommitted whether it took the post-cutover path.
     */
    private class DryRunOutcome(
        val payee: ResolvedPayee,
        val amount: Coin,
        val sendAllBalance: Coin?,
        val checkedBalance: Coin?,
        val cutoverCommitted: Boolean,
        val result: DryRunResult?,
        val exception: Exception?
    )

    /**
     * The latest dry run's outcome; null while one is running ([executeDryrun]
     * clears it first) or when there is nothing to check. Written under
     * [dryRunLock], read anywhere: readers take ONE snapshot of it, and use it
     * only if it [matches][isFor] what they are about to show or send.
     */
    @Volatile
    private var dryRunOutcome: DryRunOutcome? = null

    /**
     * Guards [dryRunGeneration], the writes of [dryRunOutcome], and the start
     * of a payee resolution ([initPaymentIntent] clears [payee] and the dry run
     * under it). A contact payment's pre-send claim runs under it too, after
     * checking that the payee and dry run it read are still current
     * ([signAndSendPayment]): a resolution either starts before that check —
     * and the send is refused — or after the claim.
     */
    private val dryRunLock = Any()

    /**
     * The engine mode as last observed ([SendCoinsTaskRunner.observeCutoverCommitted]);
     * null while it is UNKNOWN — before the first value, and while the state
     * cannot be read (the observation is retried until it can). A dry run for
     * the other mode is not for the payment the screen shows ([isFor]): its
     * send would be refused. While the mode is unknown no dry run is (fail
     * closed): the screen cannot continue and nothing is sent, until a read
     * succeeds and the dry run is vouched for again ([revalidateDryRunForUi]).
     */
    @Volatile
    private var engineCutoverCommitted: Boolean? = null

    /**
     * The mode a dry run published for the other mode was last re-run for
     * ([publishDryRun]): at most one re-run per observed mode, so a mode read
     * that keeps disagreeing with the observed one cannot loop dry runs.
     */
    @Volatile
    private var engineRerunFor: Boolean? = null

    /**
     * Numbers dry runs in the order they start; only the latest one may
     * publish its outcome, so a slow dry run for an earlier amount or payee
     * never replaces a later one's.
     */
    private var dryRunGeneration = 0L

    /** The last value posted to [dryRunSuccessful]; guarded by [dryRunLock]. */
    private var dryRunPostedSuccess = false

    /** The request of [currentDryRun], if any. */
    val dryrunSendRequest: SendRequest?
        get() = currentDryRun()?.request

    /** The last dry run's failure, for display; null while one runs or after a success. */
    val dryRunException: Exception?
        get() = dryRunOutcome?.exception

    /**
     * Whether the last dry run failed for lack of funds, as a plain boolean so
     * screens do not have to know the dashj exception type (Phase 3 keeps the
     * dashj surface inside the ViewModel).
     */
    val isInsufficientFunds: Boolean
        get() = dryRunException is InsufficientMoneyException

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

    /**
     * What [initPaymentIntent] resolved: the intent the screen pays and, for a
     * contact payment, the contact payment address it pays ([handleDashIdentity])
     * — always published together, so a contact intent never reaches
     * [signAndSendPayment] without the address the provider must claim.
     */
    private class ResolvedPayee(val intent: PaymentIntent, val contactPayment: ContactPaymentAddress?)

    /**
     * The payee the screen may confirm and send to; null until a resolution
     * completes and from the moment a new one starts (PR #1608 review, round 6:
     * the screen re-runs [initPaymentIntent] on every view creation, including
     * a configuration change with this retained ViewModel, and the previous
     * intent stayed sendable while the contact metadata was already cleared).
     * While null, [isPayeePlausible] is false and [signAndSendPayment] refuses.
     */
    private val payee = AtomicReference<ResolvedPayee?>(null)

    /** Numbers [initPaymentIntent] runs: only the latest one publishes [payee]. */
    private val payeeGeneration = AtomicLong(0)
    private val publishPayee = Mutex()

    /**
     * The intent the screen was opened with when it resolved to a contact
     * payment address — what [refreshContactPayment] resolves again.
     */
    @Volatile
    private var contactSourceIntent: PaymentIntent? = null

    /**
     * The contact payment that may have been broadcast — the send's broadcast
     * signal fired (including an unknown outcome), the send returned, or it
     * failed without proof that nothing was broadcast — or whose address was
     * refused as already paid or claimed by another send. While set, the resolved
     * contact intent is spent and never submitted again: [isPayeePlausible]
     * is false and [signAndSendPayment] refuses until [refreshContactPayment]
     * has resolved a new address (PR #1608 review: a bridge failure after the
     * broadcast left the intent usable, and the next confirmation paid the
     * same address). Non-contact payments never set it.
     */
    private val spentContactPayment = AtomicReference<ContactPaymentAddress?>(null)

    /** A failed send spent the contact intent; [resetState] resolves it again. */
    private val refreshContactOnReset = AtomicBoolean(false)

    private var contactRefreshJob: Job? = null


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
            .onEach { balance ->
                // Set (not posted): a dry run started from here reads it.
                _maxOutputAmount.value = balance
                // A post-cutover dry run was decided against the previous
                // balance: it no longer holds (a send-all of the old balance
                // is now a plain send; an amount equal to the new balance is
                // a send-all). Check the payment again. A dry run still
                // running is not seen here (no outcome yet): it checks the
                // balance itself when it publishes ([publishDryRun]).
                if (isBalanceStale(dryRunOutcome)) {
                    invalidateDryRunForUi()
                    scheduleDryRun()
                }
            }
            .launchIn(viewModelScope)

        // The engine mode changed (the cutover committed, or rolled back): a
        // dry run for the previous mode no longer holds — its send would be
        // refused, and nothing else would check the payment again. A dry run
        // still running is not seen here (no outcome yet): it checks the mode
        // itself when it publishes ([publishDryRun]).
        // An unknown mode (null: the state could not be read) vouches for no
        // dry run; when a read succeeds again, a dry run for that mode holds
        // again (PR #1608 review: a failed first read used to leave a
        // fabricated "not committed" here for the screen's lifetime).
        sendCoinsTaskRunner.observeCutoverCommitted()
            .distinctUntilChanged()
            .onEach { committed ->
                engineCutoverCommitted = committed
                when {
                    committed == null -> {
                        log.warn("the engine mode is unknown (the cutover state cannot be read); the payment cannot be sent")
                        invalidateDryRunForUi()
                    }
                    isEngineStale(dryRunOutcome) -> {
                        log.info("the engine mode changed (cutover committed: {}); checking the payment again", committed)
                        invalidateDryRunForUi()
                        scheduleDryRun()
                    }
                    else -> revalidateDryRunForUi()
                }
            }
            .launchIn(viewModelScope)

        _currentAmount
            .debounce(150)
            .onEach { amount ->
                withContext(Dispatchers.IO) {
                    executeDryrun(amount)
                }
            }
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

        // Resolution pending: nothing can be confirmed or sent until the
        // resolved intent and its contact metadata are published together.
        // The previous payee's dry run is not this payee's: drop it, and any
        // dry run still running for it (it would publish a stale outcome).
        // This resolution runs its own dry run once it has published. Under
        // dryRunLock, as a send's pre-send claim is: a send that read the
        // previous payee is refused unless it claimed before this point.
        val generation = synchronized(dryRunLock) {
            payee.set(null)
            clearDryRun()
            payeeGeneration.incrementAndGet()
        }
        invalidateDryRunForUi()
        log.info("got {}", paymentIntent)
        val resolved = withContext(Dispatchers.IO) {
            checkIdentity(paymentIntent)
        }

        val published = publishPayee.withLock {
            if (payeeGeneration.get() != generation) {
                false // a later initPaymentIntent started: it publishes
            } else {
                log.info("proceeding with {}", resolved.intent)
                super.initPaymentIntent(resolved.intent)
                // A freshly resolved contact address: payable again.
                contactSourceIntent = if (resolved.contactPayment != null) paymentIntent else null
                spentContactPayment.set(null)
                payee.set(resolved)
                true
            }
        }
        if (!published) {
            log.info("payment intent resolution superseded by a later one")
            return
        }
        withContext(Dispatchers.Main.immediate) {
            _state.value = State.INPUT
        }
        withContext(Dispatchers.IO) {
            executeDryrun(currentAmount)
        }
    }

    fun everythingPlausible(): Boolean {
        return !paymentOutcomeUnknown.value && state.value === State.INPUT &&
            isPayeePlausible() && isAmountPlausible()
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

    /**
     * Sends the confirmed payment. Refused with [SendRefusedException], nothing built or sent,
     * while another send is in progress (claimed before the first suspension, so two confirms
     * cannot both send) or once an earlier send's outcome is unknown ([paymentOutcomeUnknown],
     * set here when this send's outcome is unknown).
     */
    suspend fun signAndSendPayment(
        editedAmount: Coin,
        exchangeRate: ExchangeRate?,
        checkBalance: Boolean
    ): Transaction {
        if (paymentOutcomeUnknown.value) {
            throw SendRefusedException("an earlier send's outcome is unknown; nothing was sent")
        }
        if (!sendInFlight.compareAndSet(false, true)) {
            throw SendRefusedException("a send is already in progress; nothing was sent")
        }
        try {
            return sendPayment(editedAmount, exchangeRate, checkBalance)
        } catch (ex: Exception) {
            if (classifySendFailure(ex) == SendFailureKind.OUTCOME_UNKNOWN) {
                savedStateHandle[KEY_PAYMENT_OUTCOME_UNKNOWN] = true
            }
            throw ex
        } finally {
            // An unknown outcome has already set the terminal flag, which keeps refusing.
            sendInFlight.set(false)
        }
    }

    private suspend fun sendPayment(
        editedAmount: Coin,
        exchangeRate: ExchangeRate?,
        checkBalance: Boolean
    ): Transaction = withContext(Dispatchers.IO) {
        Context.propagate(wallet.context)
        _state.postValue(State.SENDING)
        // One consistent snapshot of what was resolved: its intent and its
        // contact payment address, never one without the other.
        val resolved = payee.get()

        // Before coin selection: after a restart the locks of an unresolved payment are gone
        // until restoration runs, and a transaction built now could spend its outpoints.
        sendCoinsTaskRunner.awaitPaymentReadiness()

        // The contact payment this send pays, once known (inside the try).
        var contactPayment: ContactPaymentAddress? = null
        val transaction = try {
            // A resolution is pending (or failed): the UI cannot confirm
            // (isPayeePlausible), and nothing is sent.
            if (resolved == null) {
                throw PayeeNotResolvedException()
            }
            // Defence in depth: the UI cannot confirm a spent contact intent
            // (isPayeePlausible), but never pay its address again.
            spentContactPayment.get()?.let { throw ContactAddressAlreadyPaidException(it) }
            // One snapshot of the dry run's outcome: a dry run started meanwhile
            // (an amount change, or resetState resolving the contact again)
            // clears it before publishing its own, so reading it twice could
            // see null — this used to throw a NullPointerException — or two
            // different dry runs. Without a successful one, nothing is sent.
            val outcome = dryRunOutcome
            val dryRun = outcome?.result ?: throw DryRunNotReadyException()
            val cutoverCommitted = sendCoinsTaskRunner.isCutoverCommitted()
            // Only a dry run of THIS send: this payee, this amount, the same
            // engine and — for a send-all — the same balance. One for the
            // previous amount (the debounced dry run of an edit has not run
            // yet) or the previous payee would hand this send its send-all
            // flag and minimum-fee rule (PR #1608 review: a "Max" amount
            // edited down was sent as a send-all). The screen re-checks it.
            if (!outcome.isFor(resolved, editedAmount) || outcome.cutoverCommitted != cutoverCommitted) {
                log.info(
                    "the dry run was for a different payment (amount {}, now {}); nothing was sent",
                    outcome.amount.toFriendlyString(),
                    editedAmount.toFriendlyString()
                )
                throw DryRunOutdatedException()
            }
            val intent = resolved.intent
            val finalPaymentIntent = intent.mergeWithEditedValues(editedAmount.toNeutralCoin(), null)
            val finalSendRequest = sendCoinsTaskRunner.createSendRequest(
                intent.mayEditAmount(),
                finalPaymentIntent,
                true,
                dryRun.request.ensureMinRequiredFee
            )
            // Post-cutover, carry the cutover-aware dry-run's send-all decision.
            // createSendRequest derives emptyWallet from the dashj max-output
            // balance, which is held at 0 while the engine is cut over, so it can
            // never flag a real send-max — that would route the Max send as a
            // plain SDK send of the full balance (no fee headroom → the SDK send
            // fails closed). executeDryrun is the authority on send-all here —
            // for exactly this amount and balance (checked above), so a
            // send-all is never carried onto an edited amount.
            // Pre-cutover both requests compute emptyWallet identically from the
            // same balance/amount, so this override is gated on the committed
            // cutover and the dashj path is otherwise untouched.
            if (cutoverCommitted) {
                finalSendRequest.emptyWallet = dryRun.emptyWallet
            }
            finalSendRequest.memo = intent.memo
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
            // A contact payment: the provider claims the address before sending
            // (a second send of it, e.g. from another send screen, is refused;
            // a claim that cannot be made durable refuses too) and marks it
            // paid once it may have gone out, so the next payment to this
            // contact gets a new one (DIP-15) — even if a later step of the
            // send (the display bridge) then fails. Once the broadcast signal
            // fires the intent is spent: whatever happens next (a bridge
            // failure), it is not submitted again.
            val payment = resolved.contactPayment
            if (payment != null && payment.address != intendedRecipient) {
                // Never pay a contact intent without claiming its address.
                throw IllegalStateException(
                    "the contact payment's request does not pay its resolved address; nothing was sent"
                )
            }
            // What this send read must still be current when it commits to
            // sending (PR #1608 review, round 9: a re-resolution that started
            // after the reads above cleared the payee and the dry run, and the
            // send went on with its copies and paid the old contact address).
            // Checked under dryRunLock — the lock a resolution takes to clear
            // them — and, for a contact payment, together with the claim.
            fun ensureStillCurrent() {
                if (payee.get() !== resolved) {
                    log.info("the payee is being resolved again; nothing was sent")
                    throw PayeeNotResolvedException()
                }
                spentContactPayment.get()?.let { throw ContactAddressAlreadyPaidException(it) }
                if (dryRunOutcome !== outcome || !outcome.isFor(resolved, editedAmount)) {
                    log.info("the payment was checked again meanwhile; nothing was sent")
                    throw DryRunOutdatedException()
                }
            }
            if (payment == null) {
                synchronized(dryRunLock) { ensureStillCurrent() }
            }
            contactPayment = payment
            contactPaymentAddressProvider.sendThenMarkUsed(
                payment,
                claimGate = { claim ->
                    synchronized(dryRunLock) {
                        ensureStillCurrent()
                        claim()
                    }
                }
            ) { signals ->
                sendCoinsTaskRunner.sendCoins(
                    finalSendRequest,
                    checkBalanceConditions = checkBalance,
                    intendedRecipient = intendedRecipient,
                    // the wallet this screen built and confirmed the payment on: a wipe since
                    // must not have the replacement pay it, on either route
                    originWallet = wallet,
                    onBroadcast = { txid ->
                        if (payment != null) spentContactPayment.set(payment)
                        signals.broadcast(txid)
                    },
                    onNotBroadcast = signals::notBroadcast
                )
            }.also {
                if (payment != null) spentContactPayment.set(payment)
            }
        } catch (ex: Exception) {
            if (ex is ContactAddressAlreadyPaidException) {
                spentContactPayment.set(ex.payment)
            }
            // A failed contact payment whose address is still claimed may have
            // gone out (outcome unknown, cancelled) or is being paid by another
            // send: spent. Only a proven pre-broadcast failure gives it back.
            val failedPayment = contactPayment
            if (failedPayment != null && contactPaymentAddressProvider.isClaimedOrPaid(failedPayment)) {
                spentContactPayment.set(failedPayment)
            }
            if (spentContactPayment.get() != null) {
                refreshContactOnReset.set(true)
            }
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
        // Only a failure of the CURRENT amount: an earlier amount's shortfall
        // subtracted from the amount now entered would be meaningless.
        return currentOutcome()?.exception is InsufficientMoneyException &&
            currentAmount.isLessThan(maxOutputAmount.value ?: Coin.ZERO) &&
            getAdjustedAmount().isGreaterThan(Coin.ZERO)
    }

    fun getAdjustedAmount(): Coin {
        val missing = (currentOutcome()?.exception as? InsufficientMoneyException)?.missing ?: Coin.ZERO
        val adjusted = currentAmount.subtract(missing)
        // Never return a negative amount — the caller feeds this straight into
        // AmountView.setAmount, and a negative snaps to "0" (clearing the field).
        return if (adjusted.isNegative) Coin.ZERO else adjusted
    }

    /**
     * Back to INPUT after a send (the screen calls it once the result or error
     * is shown). After a failed send that spent a contact intent, resolves the
     * contact's address again ([refreshContactPayment]); until that completes
     * nothing can be confirmed. Otherwise, when there is no dry run of the
     * current payment (a send refused as outdated, or a dry run skipped while
     * sending), checks it again so the screen can confirm it.
     */
    fun resetState() {
        if (paymentOutcomeUnknown.value) {
            // The payment may have gone out: never back to a confirmable INPUT with it.
            return
        }
        _state.postValue(State.INPUT)
        if (refreshContactOnReset.getAndSet(false)) {
            refreshContactPayment()
        } else if (currentDryRun() == null) {
            scheduleDryRun()
        }
    }

    /**
     * Re-runs the contact address lookup for the intent the screen was opened
     * with ([initPaymentIntent] → [checkIdentity] →
     * [ContactPaymentAddressProvider.nextAddress]), which never returns an
     * address recorded paid. On failure the spent intent stays blocked: no
     * address is better than a reused one.
     */
    private fun refreshContactPayment() {
        val source = contactSourceIntent ?: return
        if (contactRefreshJob?.isActive == true) return
        contactRefreshJob = viewModelScope.launch {
            try {
                log.info("contact payment was sent; resolving the contact's payment address again")
                initPaymentIntent(source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("could not resolve the contact's payment address again; sending stays blocked", e)
            }
        }
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
        val resolved = payee.get() ?: return false
        return isInitialized && resolved.intent.hasOutputs() && spentContactPayment.get() == null
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
        // The debounced dry run of this amount starts later: until it is done
        // the screen cannot continue with the previous amount's dry run.
        invalidateDryRunForUi()
    }

    /**
     * The successful dry run of the payment the screen shows now — its
     * resolved payee and [currentAmount] (and, for a post-cutover send-all,
     * the balance it was decided on) — or null while that is not checked yet:
     * a dry run is pending (the amount or payee just changed) or failed. The
     * screen continues and confirms only with it; [signAndSendPayment] checks
     * the same against the amount it sends.
     */
    fun currentDryRun(): DryRunResult? = currentOutcome()?.result

    private fun currentOutcome(): DryRunOutcome? =
        dryRunOutcome?.takeIf { it.isFor(payee.get(), currentAmount) }

    /**
     * Whether this outcome was concluded for paying [amount] to [resolved],
     * on the engine mode last observed — which must be known.
     */
    private fun DryRunOutcome.isFor(resolved: ResolvedPayee?, amount: Coin): Boolean =
        resolved != null &&
            payee === resolved &&
            this.amount == amount &&
            (sendAllBalance == null || sendAllBalance == maxOutputAmount.value) &&
            engineCutoverCommitted != null &&
            !isEngineStale(this)

    /**
     * Whether [outcome] was concluded on the other engine mode than the one
     * last observed. False while the mode is unknown: nothing to re-run for
     * ([isFor] refuses the outcome meanwhile).
     */
    private fun isEngineStale(outcome: DryRunOutcome?): Boolean {
        val observed = engineCutoverCommitted ?: return false
        return outcome != null && outcome.cutoverCommitted != observed
    }

    /**
     * Tells the screen (via [dryRunSuccessful]) that it cannot continue when
     * the dry run it last saw succeed is no longer for the current payment;
     * the screen re-reads [currentDryRun].
     */
    private fun invalidateDryRunForUi() = synchronized(dryRunLock) {
        if (dryRunPostedSuccess && currentDryRun() == null) {
            postDryRunSuccessful(false)
        }
    }

    /**
     * The converse of [invalidateDryRunForUi]: tells the screen it can
     * continue when the published dry run is for the current payment again —
     * the engine mode became known after the dry run was published while it
     * was unknown. The mode is written before this takes [dryRunLock], and
     * [publishDryRun] reads it under the lock, so a dry run published
     * meanwhile is told one way or the other.
     */
    private fun revalidateDryRunForUi() = synchronized(dryRunLock) {
        if (!dryRunPostedSuccess && currentDryRun() != null) {
            postDryRunSuccessful(true)
        }
    }

    /**
     * Posts [dryRunSuccessful]; called under [dryRunLock], so the posts are in
     * the order of the outcomes they describe (postValue keeps the last one).
     */
    private fun postDryRunSuccessful(success: Boolean) {
        dryRunPostedSuccess = success
        _dryRunSuccessful.postValue(success)
    }

    /**
     * Runs a dry run of the current payment. Dispatched on main, so it runs
     * after a state change [resetState] has just posted (the dry run is only
     * done in [State.INPUT]).
     */
    private fun scheduleDryRun() {
        viewModelScope.launch(Dispatchers.Main) {
            withContext(Dispatchers.IO) {
                executeDryrun(currentAmount)
            }
        }
    }

    /** Clears the outcome and supersedes any running dry run; returns the new generation. */
    private fun clearDryRun(): Long = synchronized(dryRunLock) {
        dryRunOutcome = null
        ++dryRunGeneration
    }

    /** The balance a post-cutover dry run decides on. */
    private fun currentBalance(): Coin = maxOutputAmount.value ?: Coin.ZERO

    /**
     * Whether [outcome] was decided on a balance that is no longer the
     * current one — it must be checked again.
     */
    private fun isBalanceStale(outcome: DryRunOutcome?): Boolean {
        val checked = outcome?.checkedBalance ?: return false
        return checked != currentBalance()
    }

    /**
     * Publishes [outcome] (null: nothing to check) and tells the screen,
     * unless a later dry run started — its outcome is the one that counts.
     *
     * If the balance changed after the dry run read it, its outcome is stale
     * and a replacement is scheduled (PR #1608 review: the balance observer
     * only sees a published outcome, so a change while a dry run was running
     * went unnoticed and a stale send-all blocked Continue until the user
     * edited the amount). The balance is written before the observer reads
     * [dryRunOutcome], and [dryRunOutcome] before this reads the balance (both
     * volatile), so at least one of the two sees the change.
     */
    private fun publishDryRun(generation: Long, outcome: DryRunOutcome?) {
        val (balanceStale, engineStale) = synchronized(dryRunLock) {
            if (generation != dryRunGeneration) {
                log.info("dry run superseded by a later one; its outcome is dropped")
                return
            }
            dryRunOutcome = outcome
            val engineStale = isEngineStale(outcome)
            // An unknown mode vouches for no dry run; the mode observer tells
            // the screen once it is known ([revalidateDryRunForUi]).
            val engineKnown = engineCutoverCommitted != null
            postDryRunSuccessful(outcome?.result != null && engineKnown && !engineStale)
            isBalanceStale(outcome) to engineStale
        }
        // Likewise for the engine mode, written by its observer before it
        // reads [dryRunOutcome]. Once per observed mode: a dry run that keeps
        // reading the other mode is not re-run in a loop.
        val observed = engineCutoverCommitted
        val rerunForEngine = engineStale && engineRerunFor != observed
        if (rerunForEngine) {
            engineRerunFor = observed
        }
        if (balanceStale || rerunForEngine) {
            log.info(
                "the {} changed during the dry run; checking the payment again",
                if (balanceStale) "balance" else "engine mode"
            )
            scheduleDryRun()
        }
    }

    private suspend fun executeDryrun(amount: Coin) {
        val generation = clearDryRun()
        // The payee this dry run checks — read after clearing: a resolution
        // that publishes later clears again and runs its own dry run.
        val resolved = payee.get()

        if (state.value != State.INPUT || amount == Coin.ZERO || resolved == null) {
            publishDryRun(generation, null)
            return
        }
        // The resolved payee's intent (what super.initPaymentIntent set as
        // basePaymentIntent when it was published).
        val basePaymentIntent = resolved.intent
        // Post-cutover, the balance this dry run decides on (read once).
        var checkedBalance: Coin? = null
        fun outcome(
            result: DryRunResult?,
            exception: Exception?,
            cutoverCommitted: Boolean,
            sendAllBalance: Coin? = null
        ) = DryRunOutcome(resolved, amount, sendAllBalance, checkedBalance, cutoverCommitted, result, exception)
        var cutoverCommitted = false
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
            // Phase 5d (Bug 4): see the cutover branch below. Decided — and,
            // post-cutover, the balance read — before the request is built, so
            // a balance change from here on is caught when this publishes.
            cutoverCommitted = sendCoinsTaskRunner.isCutoverCommitted()
            if (cutoverCommitted) {
                checkedBalance = currentBalance()
            }
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
            val maxOutput = checkedBalance
            if (maxOutput != null) {
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
                    log.info("executeDryRun finished (cutover-aware: SDK send-all / drain)")
                    monitorJob.cancel()
                    publishDryRun(
                        generation,
                        outcome(
                            // feeEstimate: display-only floor; the drain pays the smaller real fee
                            DryRunResult(amount, sendRequest, emptyWallet = true, feeEstimate = feeReserve),
                            exception = null,
                            cutoverCommitted = true,
                            // The send-all holds only while the balance is still this.
                            sendAllBalance = maxOutput
                        )
                    )
                    return
                }

                if (amount.add(feeReserve).isGreaterThan(maxOutput)) {
                    // Same signal the dashj path raises, so the existing
                    // insufficient-funds message and the (clamped) auto-adjust
                    // behave identically.
                    throw InsufficientMoneyException(amount.add(feeReserve).subtract(maxOutput))
                }
                log.info("executeDryRun finished (cutover-aware: SDK-overlaid affordability)")
                monitorJob.cancel()
                publishDryRun(
                    generation,
                    outcome(
                        // UNSIGNED, not completed — never broadcast
                        DryRunResult(amount, sendRequest, sendRequest.emptyWallet, feeEstimate = feeReserve),
                        exception = null,
                        cutoverCommitted = true
                    )
                )
                return
            }

            log.info("  start completeTx")
            wallet.completeTx(sendRequest)

            log.info("executeDryRun finished")
            monitorJob.cancel()
            publishDryRun(
                generation,
                outcome(
                    DryRunResult(amount, sendRequest, sendRequest.emptyWallet, feeEstimate = null),
                    exception = null,
                    cutoverCommitted = false
                )
            )
        } catch (ex: Exception) {
            monitorJob.cancel()
            publishDryRun(generation, outcome(result = null, exception = ex, cutoverCommitted = cutoverCommitted))
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

    private suspend fun checkIdentity(paymentIntent: PaymentIntent): ResolvedPayee {
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
            return ResolvedPayee(paymentIntent, contactPayment = null)
        }
    }

    private suspend fun handleDashIdentity(
        userData: UsernameSearchResult,
        paymentIntent: PaymentIntent
    ): ResolvedPayee {
        _contactData.postValue(userData)

        if (!userData.requestReceived) {
            return ResolvedPayee(paymentIntent, contactPayment = null)
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

        // Post-cutover from the SDK's sending account for this contact and
        // channel, else dashj — see ContactPaymentAddressProvider. A lookup
        // that fails (e.g. the cutover state cannot be read: the provider
        // fails closed) is an unresolvable contact address like a null one:
        // the screen shows the error and the payee stays unresolved, so
        // nothing can be sent (PR #1608 review: an IOException escaped
        // SendCoinsFragment, which handles SendException, and crashed the app).
        val target = ContactPaymentTarget(
            ourUserId = ourUserId,
            contactUserId = dashPayProfile.userId,
            accountReference = contactRequest.accountReference
        )
        val contactAddress = try {
            contactPaymentAddressProvider.nextAddress(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                "contact payment address for {} (accountReference {}) could not be looked up; nothing can be sent",
                target.contactUserId, target.accountReference, e
            )
            throw SendException("Failed to get contact address for ${dashPayProfile.userId}", e)
        }
        return if (contactAddress != null) {
            ResolvedPayee(
                PaymentIntent.fromAddressWithIdentity(
                    contactAddress.address.toBase58(),
                    dashPayProfile.userId,
                    paymentIntent.amount
                ),
                contactAddress
            )
        } else {
            throw SendException("Failed to get contact address for ${dashPayProfile.userId}")
        }
    }

}

/**
 * [SendCoinsViewModel.signAndSendPayment] was called while the payee was not
 * resolved — a resolution is pending (e.g. the screen re-initializing after a
 * configuration change) or failed. Nothing was sent.
 */
class PayeeNotResolvedException :
    IllegalStateException("the payee is not resolved yet; nothing was sent")

/**
 * [SendCoinsViewModel.signAndSendPayment] was called while no dry run result
 * was available — one is running (the amount or the payee just changed) or the
 * last one failed. Nothing was sent.
 */
class DryRunNotReadyException :
    IllegalStateException("the payment has not been checked yet; nothing was sent")

/**
 * [SendCoinsViewModel.signAndSendPayment] was called with a dry run that was
 * not for this send — a different amount (an edit whose dry run has not run
 * yet, e.g. a "Max" amount edited down), payee, engine, or a send-all whose
 * balance changed. Using it would carry its send-all flag and fee rule onto
 * this send. Nothing was sent; the screen checks the payment again.
 */
class DryRunOutdatedException :
    IllegalStateException("the payment was checked for a different amount or recipient; nothing was sent")
