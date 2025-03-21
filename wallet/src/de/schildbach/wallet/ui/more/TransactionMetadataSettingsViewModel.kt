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
import androidx.lifecycle.viewModelScope
import com.google.common.collect.Comparators.max
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.rates.ExchangeRatesRepository
import de.schildbach.wallet.ui.dashpay.utils.DashPayConfig
import de.schildbach.wallet.ui.dashpay.utils.TransactionMetadataSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.data.WalletUIConfig
import org.dash.wallet.common.data.entity.ExchangeRate
import org.dash.wallet.common.util.Constants
import org.dash.wallet.common.util.toFormattedString
import java.math.BigDecimal
import java.util.Currency
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
    private val dashPayConfig: DashPayConfig,
    walletUIConfig: WalletUIConfig,
    exchangeRates: ExchangeRatesRepository
) : ViewModel() {
    companion object {
        val CURRENT_DATA_COST = Coin.valueOf(25000) //0.00025000
    }
    private val _filterState = MutableStateFlow(TransactionMetadataSettings())
    val filterState: StateFlow<TransactionMetadataSettings> = _filterState.asStateFlow()
    private val workerJob = SupervisorJob()
    private val viewModelWorkerScope = CoroutineScope(Dispatchers.IO + workerJob)
    private var _selectedExchangeRate = MutableStateFlow<ExchangeRate?>(null)
    val selectedExchangeRate = _selectedExchangeRate.asStateFlow()
    private var selectedCurrency: String = Constants.USD_CURRENCY

    init {
        dashPayConfig.observeTransactionMetadataSettings()
            .onEach {
                _filterState.value = it
            }
            .launchIn(viewModelWorkerScope)

        walletUIConfig.observe(WalletUIConfig.SELECTED_CURRENCY)
            .filterNotNull()
            .onEach { selectedCurrency = it }
            .flatMapLatest(exchangeRates::observeExchangeRate)
            .onEach { _selectedExchangeRate.value = it }
            .launchIn(viewModelScope)
    }

    suspend fun saveDataToNetwork(saveToNetwork: Boolean) = withContext(Dispatchers.IO) {
        dashPayConfig.set(DashPayConfig.TRANSACTION_METADATA_SAVE_TO_NETWORK, saveToNetwork)
        if (dashPayConfig.get(DashPayConfig.TRANSACTION_METADATA_SAVE_AFTER) == null) {
            dashPayConfig.set(DashPayConfig.TRANSACTION_METADATA_SAVE_AFTER, System.currentTimeMillis())
        }
    }

    suspend fun setTransactionMetadataInfoShown() = withContext(Dispatchers.IO) {
        dashPayConfig.setTransactionMetadataInfoShown()
    }

    suspend fun savePreferences(settings: TransactionMetadataSettings) = withContext(Dispatchers.IO) {
        dashPayConfig.setTransactionMetadataSettings(settings)
    }

    val saveToNetwork = dashPayConfig.observe(DashPayConfig.TRANSACTION_METADATA_SAVE_TO_NETWORK)

    fun getBalanceInLocalFormat(): String {
        selectedExchangeRate.value?.fiat?.let {
            val exchangeRate = org.bitcoinj.utils.ExchangeRate(Coin.COIN, it)
            val fiatValue = exchangeRate.coinToFiat(CURRENT_DATA_COST)
            val minValue = try {
                val fractionDigits = Currency.getInstance(selectedCurrency).defaultFractionDigits
                val newValue = BigDecimal.ONE.movePointLeft(fractionDigits)
                Fiat.parseFiat(fiatValue.currencyCode, newValue.toPlainString())
            } catch (e: Exception) {
                Fiat.parseFiat(fiatValue.currencyCode, "0.01")
            }
            return max(fiatValue, minValue).toFormattedString()
        }

        return ""
    }
}
