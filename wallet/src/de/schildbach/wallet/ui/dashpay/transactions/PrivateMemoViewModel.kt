/*
 * Copyright 2022 Dash Core Group.
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

package de.schildbach.wallet.ui.dashpay.transactions

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.services.TransactionMetadataProvider
import javax.inject.Inject

@HiltViewModel
class PrivateMemoViewModel @Inject constructor(
    private val metadataProvider: TransactionMetadataProvider
): ViewModel() {
    companion object {
        const val MAX_MEMO_CHARS = 25
    }

    private var txId: Sha256Hash? = null
    private var initialMemo = ""

    val memo = MutableLiveData("")
    val canSave = MutableLiveData(false)

    private val isMemoChanged: Boolean
        get() = memo.value != initialMemo


    fun init(txId: Sha256Hash) {
        this.txId = txId

        memo.observeForever {
            canSave.value = isMemoChanged && it.length <= MAX_MEMO_CHARS
        }

        viewModelScope.launch {
            metadataProvider.getTransactionMetadata(txId)?.let {
                initialMemo = it.memo
                memo.value = initialMemo
            }
        }
    }

    suspend fun saveMemo() {
        if (isMemoChanged) {
            val txId = this.txId
            val memo = memo.value

            if (txId != null && memo != null && memo.length <= MAX_MEMO_CHARS) {
                metadataProvider.setTransactionMemo(txId, memo)
            }
        }
    }
}