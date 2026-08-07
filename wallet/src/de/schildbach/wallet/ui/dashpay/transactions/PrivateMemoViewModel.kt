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
import de.schildbach.wallet.service.platform.sdk.SdkTxDetailProvider
import de.schildbach.wallet.service.platform.sdk.toDefaultMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.entity.TransactionMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
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

@HiltViewModel
class PrivateMemoViewModel @Inject constructor(
    private val metadataProvider: TransactionMetadataProvider,
    private val sdkTxDetailProvider: SdkTxDetailProvider
): ViewModel() {
    companion object {
        const val MAX_MEMO_CHARS = 25
        private val log = LoggerFactory.getLogger(PrivateMemoViewModel::class.java)
    }

    private var txId: Sha256Hash? = null
    private var initialMemo = ""

    // Set only for an SDK-only tx (one the dashj wallet does not hold, so the
    // provider has no wallet Transaction to create the metadata row from). It
    // is the minimal row to persist the memo against. Null for dashj txs, whose
    // row the provider creates itself — so no SDK lookup happens for them.
    private var sdkFallback: TransactionMetadata? = null

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
            val existing = metadataProvider.getTransactionMetadata(txId.toTxId())
            if (existing != null) {
                initialMemo = existing.memo
                memo.value = initialMemo
            } else {
                // No dashj-derived row: this is an SDK-only tx (or one with no
                // metadata yet). Prepare an SDK fallback row so a saved memo has
                // something to attach to.
                sdkFallback = loadSdkFallback(txId)
            }
        }
    }

    private suspend fun loadSdkFallback(txId: Sha256Hash): TransactionMetadata? = try {
        sdkTxDetailProvider.load(txId.toString())?.toDefaultMetadata()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        log.error("failed to load SDK detail for memo fallback {}", txId, t)
        null
    }

    suspend fun saveMemo() {
        if (isMemoChanged) {
            val txId = this.txId
            val memo = memo.value

            if (txId != null && memo != null && memo.length <= MAX_MEMO_CHARS) {
                metadataProvider.setTransactionMemo(txId.toTxId(), memo, fallbackMetadata = sdkFallback)
            }
        }
    }
}