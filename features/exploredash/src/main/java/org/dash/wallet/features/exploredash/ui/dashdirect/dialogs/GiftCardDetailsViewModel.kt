/*
 * Copyright 2023 Dash Core Group.
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

package org.dash.wallet.features.exploredash.ui.dashdirect.dialogs

import android.graphics.Bitmap
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import org.bitcoinj.core.Sha256Hash
import org.dash.wallet.common.services.TransactionMetadataProvider
import org.dash.wallet.features.exploredash.data.dashdirect.GiftCardDao
import org.dash.wallet.features.exploredash.data.dashdirect.model.GiftCard
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class GiftCardDetailsViewModel @Inject constructor(
    private val giftCardDao: GiftCardDao,
    private val metadataProvider: TransactionMetadataProvider
): ViewModel() {
    private val _giftCard: MutableLiveData<GiftCard?> = MutableLiveData()
    val giftCard: LiveData<GiftCard?>
        get() = _giftCard

    private val _icon: MutableLiveData<Bitmap?> = MutableLiveData()
    val icon: LiveData<Bitmap?>
        get() = _icon

    private val _date: MutableLiveData<LocalDateTime> = MutableLiveData()
    val date: LiveData<LocalDateTime>
        get() = _date

    fun init(transactionId: Sha256Hash) {
        viewModelScope.launch {
            _giftCard.value = giftCardDao.getCardForTransaction(transactionId)
            metadataProvider.getTransactionMetadata(transactionId)?.let { metadata ->
                _date.value = LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(metadata.timestamp),
                    ZoneId.systemDefault()
                )
                _icon.value = metadata.customIconId?.let { metadataProvider.getIcon(it) }
            }
        }
    }
}
