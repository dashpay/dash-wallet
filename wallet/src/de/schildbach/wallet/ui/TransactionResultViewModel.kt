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
import de.schildbach.wallet.service.platform.sdk.SdkTxDetail
import de.schildbach.wallet.service.platform.sdk.SdkTxDetailProvider
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
    val configuration: Configuration,
    private val dashPayProfileDao: DashPayProfileDao,
    private val topUpsDao: TopUpsDao,
    private val identityRepository: IdentityRepository,
    private val platformRepo: PlatformRepo,
    private val sdkTxDetailProvider: SdkTxDetailProvider,
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
                    monitorTransactionMetadata(tx.txId)
                    findContact(tx)
                } else {
                    // Not in the dashj wallet — post-cutover this is an
                    // SDK-only transaction (a receive the held dashj wallet
                    // never saw). Serve the detail from the SDK store via
                    // the transaction_decode binding instead of a blank sheet.
                    val detail = withContext(Dispatchers.IO) {
                        try {
                            sdkTxDetailProvider.load(txId.toString())
                        } catch (e: Exception) {
                            log.error("SDK tx-detail lookup failed for {}", txId, e)
                            null
                        }
                    }
                    if (detail != null) {
                        _sdkTxDetail.value = detail
                        monitorTransactionMetadata(txId)
                    }
                }
            }
        }
    }

    private fun monitorTransactionMetadata(txId: Sha256Hash) {
        // this might take some time, so let it run asynchronously
        viewModelScope.launch(Dispatchers.IO) {
            transactionMetadataProvider.importTransactionMetadata(txId.toTxId())
            transactionMetadataProvider.observeTransactionMetadata(txId.toTxId()).collect {
                _transactionMetadata.value = it
            }
        }
    }

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
            viewModelScope.launch(Dispatchers.IO) {
                transactionMetadataProvider.setTransactionTaxCategory(
                    txId.toTxId(),
                    newTaxCategory
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
