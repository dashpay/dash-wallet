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
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.database.entity.DashPayProfile
import de.schildbach.wallet.database.dao.DashPayProfileDao
import de.schildbach.wallet.ui.dashpay.PlatformRepo
import de.schildbach.wallet.WalletApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.ServiceName
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.features.exploredash.data.dashdirect.GiftCardDao
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@HiltViewModel
class TransactionResultViewModel @Inject constructor(
    private val transactionMetadataProvider: TransactionMetadataProvider,
    private val giftCardDao: GiftCardDao,
    val walletData: WalletDataProvider,
    val configuration: Configuration,
    private val dashPayProfileDao: DashPayProfileDao,
    private val platformRepo: PlatformRepo,
    private val analytics: AnalyticsService,
    val walletApplication: WalletApplication
) : ViewModel() {
    val dashFormat: MonetaryFormat = configuration.format.noCode()

    val wallet: Wallet?
        get() = walletData.wallet

    private val _transaction = MutableStateFlow<Transaction?>(null)
    val transaction: StateFlow<Transaction?>
        get() = _transaction

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
        .filter { it.service == ServiceName.DashDirect }
        .map { giftCardDao.getCardForTransaction(it.txId)?.merchantName }
        .filterNotNull()
        .asLiveData()

    private val _contact = MutableLiveData<DashPayProfile?>()
    val contact: LiveData<DashPayProfile?>
        get() = _contact

    fun init(txId: Sha256Hash?) {
        txId?.let {
            // should this be viewModelScope.launch(Dispatchers.IO) and not use withContext
            viewModelScope.launch {
                val tx = withContext(Dispatchers.IO) { walletData.wallet!!.getTransaction(txId) }
                tx?.let {
                    _transaction.value = tx
                    monitorTransactionMetadata(it.txId)
                    findContact(it)
                }
            }
        }
    }

    private suspend fun monitorTransactionMetadata(txId: Sha256Hash) {
        withContext(Dispatchers.IO) {
            transactionMetadataProvider.importTransactionMetadata(txId)
            transactionMetadataProvider.observeTransactionMetadata(txId).collect {
                _transactionMetadata.value = it
            }
        }
    }

    fun toggleTaxCategory() {
        transaction.value?.let { tx ->
            val metadata = _transactionMetadata.value // can be null if there is no metadata in the table

            var currentTaxCategory = metadata?.taxCategory // can be null if user never specified a value

            if (currentTaxCategory == null) {
                val isOutgoing = tx.getValue(walletData.transactionBag).signum() < 0
                currentTaxCategory = TaxCategory.getDefault(
                    metadata?.value?.isPositive ?: !isOutgoing,
                    metadata?.isTransfer ?: false
                )
            }
            // toggle the tax category and save
            val newTaxCategory = currentTaxCategory.toggle()
            viewModelScope.launch(Dispatchers.IO) {
                transactionMetadataProvider.setTransactionTaxCategory(
                    tx.txId,
                    newTaxCategory
                )
            }
        }
    }

    private suspend fun findContact(tx: Transaction) {
        if (!platformRepo.hasIdentity) {
            _contact.postValue(null)
            return
        }

        val userId = withContext(Dispatchers.IO) {
            platformRepo.blockchainIdentity.getContactForTransaction(tx)
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
}
