/*
 * Copyright 2026 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui.dashspend.dialogs

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.data.entity.GiftCard
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.common.ui.components.MyImages
import org.dash.wallet.features.exploredash.data.explore.GiftCardDao
import javax.inject.Inject

data class GiftCardOrderUIState(
    val merchantName: String = "",
    val merchantIcon: Bitmap? = null,
    val serviceName: String? = null,
    val giftCards: List<GiftCard> = emptyList()
)

@HiltViewModel
class GiftCardOrderDetailsViewModel @Inject constructor(
    private val giftCardDao: GiftCardDao,
    private val metadataProvider: TransactionMetadataProvider
) : ViewModel() {
    lateinit var transactionId: Sha256Hash
        private set

    private val _uiState = MutableStateFlow(GiftCardOrderUIState())
    val uiState: StateFlow<GiftCardOrderUIState> = _uiState.asStateFlow()

    fun init(transactionId: Sha256Hash) {
        this.transactionId = transactionId

        metadataProvider.observeTransactionMetadata(transactionId)
            .filterNotNull()
            .onEach { metadata ->
                _uiState.update { current ->
                    current.copy(
                        merchantIcon = metadata.customIconId?.let { metadataProvider.getIcon(it) },
                        serviceName = metadata.service
                    )
                }
            }
            .launchIn(viewModelScope)

        giftCardDao.observeCardForTransaction(transactionId)
            .filterNotNull()
            .distinctUntilChanged()
            .onEach { giftCards ->
                val sorted = giftCards.sortedBy { it.index }
                _uiState.update { current ->
                    current.copy(
                        merchantName = sorted.firstOrNull()?.merchantName.orEmpty(),
                        giftCards = sorted
                    )
                }
            }
            .launchIn(viewModelScope)
    }
}