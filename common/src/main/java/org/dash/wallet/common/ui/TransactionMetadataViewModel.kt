/*
 * Copyright 2020 Dash Core Group.
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

package org.dash.wallet.common.ui

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.transactions.TaxCategory
import javax.inject.Inject

@HiltViewModel
class TransactionMetadataViewModel @Inject constructor (private val transactionMetadataProvider: TransactionMetadataProvider) : ViewModel() {

    private fun markAddressAsync(address: String, sendTo: Boolean, taxCategory: TaxCategory) {
        viewModelScope.launch {
            transactionMetadataProvider.markAddressWithTaxCategory(address, sendTo, taxCategory)
        }
    }
    fun markAddressAsTransferOutAsync(address: String) {
        markAddressAsync(address, true, TaxCategory.TransferOut)
    }

    fun markAddressAsTransferInAsync(address: String) {
        markAddressAsync(address, false, TaxCategory.TransferIn)
    }
}
