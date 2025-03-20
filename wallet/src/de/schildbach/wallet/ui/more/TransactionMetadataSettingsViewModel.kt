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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TransactionMetadataSettingsViewModel @Inject constructor(
    private val dashPayConfig: DashPayConfig
) : ViewModel() {
    suspend fun saveDataToNetwork(saveToNetwork: Boolean) = withContext(Dispatchers.IO) {
        dashPayConfig.set(DashPayConfig.TRANSACTION_METADATA_SAVE_TO_NETWORK, saveToNetwork)
    }
    suspend fun setTransactionMetadataInfoShown() = withContext(Dispatchers.IO) {
        dashPayConfig.setTransactionMetadataInfoShown()
    }

    val saveToNetwork = dashPayConfig.observe(DashPayConfig.TRANSACTION_METADATA_SAVE_TO_NETWORK)
}
