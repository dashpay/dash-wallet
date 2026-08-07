/*
 * Copyright (c) 2022. Dash Core Group.
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

package de.schildbach.wallet.ui

import androidx.lifecycle.*
import androidx.work.WorkInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.WalletApplication
import de.schildbach.wallet.database.dao.TopUpsDao
import de.schildbach.wallet.database.entity.TopUp
import de.schildbach.wallet.service.platform.IdentityRepository
import de.schildbach.wallet.service.platform.sdk.CutoverUiDataService
import de.schildbach.wallet.service.platform.sdk.SdkTxDetail
import de.schildbach.wallet.service.platform.sdk.SdkTxDetailProvider
import de.schildbach.wallet.service.platform.sdk.toDefaultMetadata
import de.schildbach.wallet.service.platform.work.TopupIdentityOperation
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.money.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import de.schildbach.wallet.data.WalletData
import org.dash.wallet.common.data.Resource
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import org.dash.wallet.integrations.maya.data.SwapOrderDao
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

@HiltViewModel
class TransactionResultViewModel @Inject constructor(
    private val transactionMetadataProvider: TransactionMetadataProvider,
    private val giftCardDao: GiftCardDao,
    val walletData: WalletData,
    private val swapOrderDao: SwapOrderDao,
    val configuration: Configuration,
    private val dashPayProfileDao: DashPayProfileDao,
    private val topUpsDao: TopUpsDao,
    private val identityRepository: IdentityRepository,
    private val platformRepo: PlatformRepo,
    private val sdkTxDetailProvider: SdkTxDetailProvider,
    private val cutoverUiDataService: CutoverUiDataService,
    val analytics: AnalyticsService,
    val walletApplication: WalletApplication
) : ViewModel() {
    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(TransactionResultViewModel::class.java)
    }

    val dashFormat: MonetaryFormat = configuration.format.noCode()

    val wallet: Wallet?
        get() = walletData.wallet

    private val _transaction = MutableStateFlow<Transaction?>(null)
    val transaction: StateFlow<Transaction?>
        get() = _transaction

    /**
     * Step B1 fallback: the neutral SDK-sourced detail for a transaction
     * the dashj wallet does NOT hold (post-cutover SDK-only txs — the
     * blank-detail-sheet gap). Non-null only when [transaction] stayed
     * null and the SDK store had the row.
     */
    private val _sdkTxDetail = MutableStateFlow<SdkTxDetail?>(null)
    val sdkTxDetail: StateFlow<SdkTxDetail?>
        get() = _sdkTxDetail

    /**
     * Bug A: a direction/amount OVERRIDE for a transaction the dashj wallet
     * DOES hold but reads wrong post-cutover. An SDK-authored send is committed
     * into the held/frozen dashj wallet (rollback coherence), but that wallet
     * doesn't recognize the SDK send's inputs/outputs, so `tx.getValue(wallet)`
     * is 0 → the sheet mislabels it "Amount Received +0.00". When the cutover is
     * active we load the authoritative SDK row and expose it here; the binder
     * drives `isSent` + the net amount from it instead of `tx.getValue(wallet)`.
     * Non-null only when [transaction] IS non-null (the SDK-only, blank-sheet
     * case stays on [sdkTxDetail]). Permanently null pre-cutover.
     */
    private val _sdkDirectionOverride = MutableStateFlow<SdkTxDetail?>(null)
    val sdkDirectionOverride: StateFlow<SdkTxDetail?>
        get() = _sdkDirectionOverride

    private val _transactionMetadata: MutableStateFlow<TransactionMetadata?> = MutableStateFlow(null)
    val transactionMetadata
        get() = _transactionMetadata.filterNotNull()

    val transactionIcon = _transactionMetadata
        .filterNotNull()
        .map { it.customIconId }
        .filterNotNull()
        .map { transactionMetadataProvider.getIcon(it) }
        .filterNotNull()
        .asLiveData()

    val merchantName = _transactionMetadata
        .filterNotNull()
        .filter { ServiceName.isDashSpend(it.service) }
        .map { giftCardDao.getCardForTransaction(it.txId.bytes).firstOrNull()?.merchantName }
        .filterNotNull()
        .asLiveData()

    /** The DEX swap this tx funded, or null if it isn't a swap. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val swapOrder = _transaction
        .filterNotNull()
        .flatMapLatest { swapOrderDao.observeOrder(it.txId.toTxId()) }
        .asLiveData()

    private val _contact = MutableLiveData<DashPayProfile?>()
    val contact: LiveData<DashPayProfile?>
        get() = _contact

    var topUpError: Boolean = false
    var topUpComplete: Boolean = false
    fun init(txId: Sha256Hash?) {
        txId?.let {
            // should this be viewModelScope.launch(Dispatchers.IO) and not use withContext
            viewModelScope.launch {
                val tx = withContext(Dispatchers.IO) { walletData.wallet!!.getTransaction(txId) }
                if (tx != null) {
                    _transaction.value = tx
                    // Bug A: post-cutover the held dashj wallet can misread an
                    // SDK-authored send (value==0 → "Received +0.00"). When the
                    // cutover is active, load the authoritative SDK row and expose
                    // it as a direction/amount override the binder reads.
                    // Reads the EXPLICIT cutover gate — this used to test
                    // `sdkBalanceOrNull() != null`, which is equivalent only by
                    // accident of that flow being pinned to null pre-cutover.
                    if (cutoverUiDataService.isCutoverActive()) {
                        val override = withContext(Dispatchers.IO) { loadSdkDetailOrNull(txId) }
                        if (override != null) {
                            _sdkDirectionOverride.value = override
                        }
                    }
                    monitorTransactionMetadata(tx.txId)
                    findContact(tx)
                } else {
                    // Not in the dashj wallet — post-cutover this is an
                    // SDK-only transaction (a receive the held dashj wallet
                    // never saw). Serve the detail from the SDK store via
                    // the transaction_decode binding instead of a blank sheet.
                    val detail = withContext(Dispatchers.IO) { loadSdkDetailOrNull(txId) }
                    if (detail != null) {
                        _sdkTxDetail.value = detail
                        monitorTransactionMetadata(txId)
                    }
                }
            }
        }
    }

    private suspend fun loadSdkDetailOrNull(txId: Sha256Hash): SdkTxDetail? =
        try {
            sdkTxDetailProvider.load(txId.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Throwable, not Exception: the SDK's native-lib load can throw
            // UnsatisfiedLinkError / ExceptionInInitializerError on unsupported
            // ABIs — degrade to the plain sheet, don't crash.
            log.error("SDK tx-detail lookup failed for {}", txId, t)
            null
        }

    private fun monitorTransactionMetadata(txId: Sha256Hash) {
        // this might take some time, so let it run asynchronously
        viewModelScope.launch(Dispatchers.IO) {
            transactionMetadataProvider.importTransactionMetadata(txId.toTxId())
            transactionMetadataProvider.observeTransactionMetadata(txId.toTxId()).collect { metadata ->
                // For an SDK-only transaction (one the dashj wallet does not
                // hold) the provider has no wallet tx to import from, so it can
                // neither insert nor observe a row and this stream stays null
                // forever — leaving the tax-category field stuck on "Loading".
                // Fall back to a default-bearing metadata built from the SDK
                // detail so the sheet shows the default label promptly. dashj
                // txs always have a row, so this fallback is never hit for them.
                _transactionMetadata.value = metadata ?: defaultSdkTxMetadata()
            }
        }
    }

    /**
     * A default (no user-set category) [TransactionMetadata] for the current
     * SDK-only transaction, derived from [sdkTxDetail]. Used both to seed the
     * sheet's display promptly and as the row to persist a user's edit against
     * (the dashj wallet has no Transaction to create one from). Its null
     * taxCategory makes the binder show the default label — Income for a
     * receive, Expense for a send — matching dashj txs. Null when there is no
     * SDK detail (a dashj tx).
     */
    private fun defaultSdkTxMetadata(): TransactionMetadata? = _sdkTxDetail.value?.toDefaultMetadata()

    fun toggleTaxCategory() {
        // (txId, isOutgoing) from whichever source served the sheet —
        // the dashj transaction or the SDK-only detail fallback.
        val txIdAndOutgoing = transaction.value?.let {
            it.txId to (it.getValue(walletData.transactionBag).signum() < 0)
        } ?: sdkTxDetail.value?.let {
            Sha256Hash.wrap(it.txIdDisplayHex) to it.isSent
        }

        txIdAndOutgoing?.let { (txId, isOutgoing) ->
            val metadata = _transactionMetadata.value // can be null if there is no metadata in the table

            var currentTaxCategory = metadata?.taxCategory // can be null if user never specified a value

            if (currentTaxCategory == null) {
                currentTaxCategory = TaxCategory.getDefault(
                    metadata?.value?.isPositive ?: !isOutgoing,
                    metadata?.isTransfer ?: false
                )
            }
            // toggle the tax category and save
            val newTaxCategory = currentTaxCategory.toggle()
            // For an SDK-only tx (no dashj Transaction) the provider has no
            // wallet tx to create the row from, so hand it a fallback row built
            // from the SDK detail — otherwise the toggle would be dropped and
            // the category would not survive reopening the sheet.
            val fallback = if (transaction.value == null) defaultSdkTxMetadata() else null
            viewModelScope.launch(Dispatchers.IO) {
                transactionMetadataProvider.setTransactionTaxCategory(
                    txId.toTxId(),
                    newTaxCategory,
                    fallbackMetadata = fallback
                )
            }
        }
    }

    private suspend fun findContact(tx: Transaction) {
        // check hasIdentity since later we need blockchainIdentity
        if (!identityRepository.hasBlockchainIdentity) {
            _contact.postValue(null)
            return
        }

        val userId = withContext(Dispatchers.IO) {
            identityRepository.blockchainIdentity!!.getContactForTransaction(tx)
        }

        if (userId == null) {
            _contact.postValue(null)
            return
        }

        dashPayProfileDao.observeByUserId(userId)
            .distinctUntilChanged()
            .onEach(_contact::postValue)
            .launchIn(viewModelScope)
    }
    
    fun rescanBlockchain() {
        analytics.logEvent(AnalyticsConstants.Settings.RESCAN_BLOCKCHAIN_RESET, mapOf())
        walletApplication.resetBlockchain()
        configuration.updateLastBlockchainResetTime()
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, mapOf())
    }

    fun topUpStatus(txId: Sha256Hash): Flow<TopUp?> = topUpsDao.observe(txId)
    fun topUpWork(txId: Sha256Hash): LiveData<Resource<WorkInfo>> =
        TopupIdentityOperation.operationStatus(walletApplication, txId, analytics)
}
