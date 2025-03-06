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

package de.schildbach.wallet.ui.transactions

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.service.DashSystemService
import de.schildbach.wallet.transactions.coinjoin.CoinJoinMixingTxSet
import de.schildbach.wallet.transactions.coinjoin.CoinJoinTxResourceMapper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import org.bitcoinj.core.Sha256Hash
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.ExchangeRate
import org.bitcoinj.utils.MonetaryFormat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.WalletDataProvider
import org.dash.wallet.common.data.PresentableTxMetadata
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.transactions.TransactionWrapper
import org.dash.wallet.integrations.crowdnode.transactions.FullCrowdNodeSignUpTxSet
import javax.inject.Inject

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionGroupViewModel @Inject constructor(
    val walletData: WalletDataProvider,
    val dashSystemService: DashSystemService,
    val config: Configuration,
    private val metadataProvider: TransactionMetadataProvider
) : ViewModel() {
    companion object {
        private const val THROTTLE_DURATION = 500L
    }
    private var chainLockBlockHeight: Int = 0
    val dashFormat: MonetaryFormat = config.format.noCode()

    private val _dashValue = MutableLiveData<Coin>()
    val dashValue: LiveData<Coin>
        get() = _dashValue

    private val _exchangeRate = MutableLiveData<ExchangeRate?>()
    val exchangeRate: LiveData<ExchangeRate?>
        get() = _exchangeRate

    private val _transactions = MutableLiveData<List<TransactionRowView>>()
    val transactions: LiveData<List<TransactionRowView>>
        get() = _transactions

    fun init(transactionWrapper: TransactionWrapper) {
        _exchangeRate.value = transactionWrapper.transactions.values.first().exchangeRate

        metadataProvider.observePresentableMetadata()
            .flatMapLatest { memos ->
                refreshTransactions(transactionWrapper, memos)
                walletData.observeTransactions(true)
                    .debounce(THROTTLE_DURATION)
                    .onEach { tx ->
                        chainLockBlockHeight = dashSystemService.system.chainLockHandler.getBestChainLockBlockHeight()
                        if (transactionWrapper.tryInclude(tx)) {
                            refreshTransactions(transactionWrapper, memos)
                        }
                    }
            }
            .launchIn(viewModelScope)
    }

    private fun refreshTransactions(
        transactionWrapper: TransactionWrapper,
        metadata: Map<Sha256Hash, PresentableTxMetadata>
    ) {
        val resourceMapper = when (transactionWrapper) {
            is FullCrowdNodeSignUpTxSet -> CrowdNodeTxResourceMapper()
            is CoinJoinMixingTxSet -> CoinJoinTxResourceMapper()
            else -> TxResourceMapper()
        }

        _transactions.value = transactionWrapper.transactions.values.map {
            val txMetadata = metadata.getOrDefault(it.txId, null)
            TransactionRowView.fromTransaction(
                it, walletData.wallet!!, walletData.wallet!!.context, txMetadata, null, resourceMapper, chainLockBlockHeight
            )
        }.sortedBy { row -> row.time }
        _dashValue.value = transactionWrapper.getValue(walletData.transactionBag)
    }
}