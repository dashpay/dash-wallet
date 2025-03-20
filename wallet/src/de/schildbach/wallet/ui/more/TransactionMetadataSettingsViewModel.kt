/*
 * Copyright (c) 2025 Dash Core Group
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.more

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.utils.TransactionMetadataSettings
import de.schildbach.wallet.ui.username.FiltersUIState
import de.schildbach.wallet.ui.username.UsernameGroupOption
import de.schildbach.wallet.ui.username.UsernameSortOption
import de.schildbach.wallet.ui.username.UsernameTypeOption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class TxMetadataSaveFrequency {
    afterTenTransactions,
    oncePerWeek,
    afterEveryTransaction;

    companion object {
        val defaultOption = oncePerWeek
    }
}

//data class TxMetadataSaveUIState(
//    val saveToNetwork: Boolean = false,
//    val sortByOption: TxMetadataSaveFrequency = TxMetadataSaveFrequency.defaultOption,
//    val paymentCategories: Boolean = false,
//    val taxCatagories: Boolean = false,
//    val exchangeRates: Boolean = false,
//    val memos: Boolean = false,
//) {
////    fun isDefault(): Boolean {
////        // typeOption isn't included because we show it in the header
////        return sortByOption == UsernameSortOption.DateDescending &&
////                !onlyDuplicates &&
////                !onlyLinks
////    }
//}

@HiltViewModel
class TransactionMetadataSettingsViewModel @Inject constructor(
    private val dashPayConfig: DashPayConfig
) : ViewModel() {
    private val _filterState = MutableStateFlow(TransactionMetadataSettings())
    val filterState: StateFlow<TransactionMetadataSettings> = _filterState.asStateFlow()
    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)
    init {
        dashPayConfig.observeTransactionMetadataSettings()
            .onEach {
                _filterState.value = it
            }
            .launchIn(viewModelWorkerScope)
    }

    suspend fun saveDataToNetwork(saveToNetwork: Boolean) = withContext(Dispatchers.IO) {
        dashPayConfig.set(DashPayConfig.TRANSACTION_METADATA_SAVE_TO_NETWORK, saveToNetwork)
    }
    suspend fun setTransactionMetadataInfoShown() = withContext(Dispatchers.IO) {
        dashPayConfig.setTransactionMetadataInfoShown()
    }

    suspend fun savePreferences(settings: TransactionMetadataSettings) = withContext(Dispatchers.IO) {
        dashPayConfig.setTransactionMetadataSettings(settings)
    }

    val saveToNetwork = dashPayConfig.observe(DashPayConfig.TRANSACTION_METADATA_SAVE_TO_NETWORK)
}
