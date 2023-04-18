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

import androidx.core.os.bundleOf
import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.WalletApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.utils.MonetaryFormat
import org.bitcoinj.wallet.Wallet
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.TaxCategory
import org.dash.wallet.common.data.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.services.analytics.AnalyticsConstants
import org.dash.wallet.common.services.analytics.AnalyticsService
import javax.inject.Inject

@HiltViewModel
class TransactionResultViewModel @Inject constructor(
    private val transactionMetadataProvider: TransactionMetadataProvider,
    private val walletData: WalletDataProvider,
    private val configuration: Configuration,
    private val analytics: AnalyticsService,
    private val walletApplication: WalletApplication
) : ViewModel() {

    val dashFormat: MonetaryFormat = configuration.format.noCode()

    val wallet: Wallet?
        get() = walletData.wallet

    var transaction: Transaction? = null
        private set

    private val _transactionMetadata: MutableStateFlow<TransactionMetadata?> = MutableStateFlow(null)
    val transactionMetadata
        get() = _transactionMetadata.asLiveData()

    fun init(txId: Sha256Hash?) {
        txId?.let {
            this.transaction = walletData.wallet!!.getTransaction(txId)
            this.transaction?.let {
                monitorTransactionMetadata(it.txId)
            }
        }
    }

    private fun monitorTransactionMetadata(txId: Sha256Hash) {
        viewModelScope.launch(Dispatchers.IO) {
            transactionMetadataProvider.importTransactionMetadata(txId)
            transactionMetadataProvider.observeTransactionMetadata(txId).collect {
                _transactionMetadata.value = it
            }
        }
    }

    fun toggleTaxCategory() {
        transaction?.let { tx ->
            val metadata = _transactionMetadata.value  // can be null if there is no metadata in the table

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

    fun rescanBlockchain() {
        analytics.logEvent(AnalyticsConstants.Settings.RESCAN_BLOCKCHAIN_RESET, bundleOf())
        walletApplication.resetBlockchain()
        configuration.updateLastBlockchainResetTime()
    }

    fun logEvent(eventName: String) {
        analytics.logEvent(eventName, bundleOf())
    }
}
