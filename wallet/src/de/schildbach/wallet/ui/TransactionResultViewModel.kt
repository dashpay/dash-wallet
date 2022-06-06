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
import de.schildbach.wallet.util.isOutgoing
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.bitcoinj.core.Transaction
import org.dash.wallet.common.transactions.TaxCategory
import org.dash.wallet.common.transactions.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.slf4j.LoggerFactory
import javax.inject.Inject

@FlowPreview
@HiltViewModel
class TransactionResultViewModel @Inject constructor(var transactionMetadataProvider: TransactionMetadataProvider) : ViewModel() {

    companion object {
        val log = LoggerFactory.getLogger(TransactionResultViewModel::class.java)
    }

    private lateinit var transaction: Transaction

    private val _transactionMetadata: MutableStateFlow<TransactionMetadata?> = MutableStateFlow(null)
    val transactionMetadata
        get() = _transactionMetadata.asLiveData()

    fun setTransaction(transaction: Transaction) {
        this.transaction = transaction
        monitorTransactionMetadata()
    }

    private fun monitorTransactionMetadata() {
        viewModelScope.launch(Dispatchers.IO) {
            transactionMetadataProvider.importTransactionMetadata(transaction.txId)
            transactionMetadataProvider.observeTransactionMetadata(transaction.txId).collect {
                _transactionMetadata.value = it
            }
        }
    }

    fun toggleTaxCategory() {

        val metadata = _transactionMetadata.value  // can be null if there is no metadata in the table

        var currentTaxCategory = metadata?.taxCategory // can be null if user never specified a value

        if (currentTaxCategory == null) {
            currentTaxCategory = TaxCategory.getDefault(
                metadata?.value?.isPositive ?: !transaction.isOutgoing(),
                metadata?.isTransfer ?: false
            )
        }
        // toggle the tax category and save
        val newTaxCategory = currentTaxCategory.toggle()
        viewModelScope.launch(Dispatchers.IO) {
            transactionMetadataProvider.setTransactionTaxCategory(
                transaction.txId,
                newTaxCategory
            )
        }

    }
}
