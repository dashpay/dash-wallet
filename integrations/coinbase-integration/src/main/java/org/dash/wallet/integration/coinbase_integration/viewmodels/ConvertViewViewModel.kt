/*
 * Copyright 2021 Dash Core Group.
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

package org.dash.wallet.integration.coinbase_integration.viewmodels

import androidx.lifecycle.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.bitcoinj.core.Coin
import org.bitcoinj.utils.Fiat
import org.dash.wallet.common.Configuration
import org.dash.wallet.common.data.ExchangeRate
import org.dash.wallet.common.data.SingleLiveEvent
import org.dash.wallet.common.livedata.Event
import org.dash.wallet.common.services.ExchangeRatesProvider
import org.dash.wallet.integration.coinbase_integration.model.CoinBaseUserAccountDataUIModel
import javax.inject.Inject

@ExperimentalCoroutinesApi
@HiltViewModel
class ConvertViewViewModel @Inject constructor(
    var exchangeRates: ExchangeRatesProvider,
    var configuration: Configuration
) : ViewModel() {

    private val _selectedCryptoCurrency = MutableLiveData<CoinBaseUserAccountDataUIModel?>()
    val selectedCryptoCurrencyAccount: LiveData<CoinBaseUserAccountDataUIModel?>
        get() = _selectedCryptoCurrency

    private val _selectedLocalCurrencyCode = MutableStateFlow(configuration.exchangeCurrencyCode)
    var selectedLocalCurrencyCode: String
        get() = _selectedLocalCurrencyCode.value
        set(value) {
            _selectedLocalCurrencyCode.value = value
        }

    private val _selectedPickerCurrencyCode = MutableStateFlow(configuration.exchangeCurrencyCode)
    var selectedPickerCurrencyCode: String
        get() = _selectedPickerCurrencyCode.value
        set(value) {
            _selectedPickerCurrencyCode.value = value
        }

    var enteredConvertAmount = "0"

    private val _selectedLocalExchangeRate = MutableLiveData<ExchangeRate>()
    val selectedLocalExchangeRate: LiveData<ExchangeRate>
        get() = _selectedLocalExchangeRate

    var maxAmount: Coin = Coin.ZERO
    val onContinueEvent = SingleLiveEvent<Pair<Boolean, Fiat>>()

    private val _userAccountsInfo: MutableLiveData<List<CoinBaseUserAccountDataUIModel>> = MutableLiveData()
    val userAccountsInfo: LiveData<List<CoinBaseUserAccountDataUIModel>>
        get() = _userAccountsInfo

    private val _userAccountsWithBalance: MutableLiveData<Event<List<CoinBaseUserAccountDataUIModel>>> = MutableLiveData()
    val userAccountsWithBalance: LiveData<Event<List<CoinBaseUserAccountDataUIModel>>>
        get() = _userAccountsWithBalance

    private val _userAccountError: SingleLiveEvent<Boolean> = SingleLiveEvent()
    val userAccountError: LiveData<Boolean>
        get() = _userAccountError

    init {
        _selectedLocalCurrencyCode.flatMapLatest { code ->
            exchangeRates.observeExchangeRate(code)
        }.onEach(_selectedLocalExchangeRate::postValue)
            .launchIn(viewModelScope)
    }
    fun setUserAccountsList(userAccountsList: List<CoinBaseUserAccountDataUIModel>?) {
        _userAccountsInfo.value = userAccountsList
    }

    fun getUserWalletAccounts(dashToCrypt: Boolean) {
        val userAccountsWithBalanceList =
            if (dashToCrypt) {
                _userAccountsInfo.value?.filter {
                    isValidCoinBaseAccount(it)
                }
            } else {
                _userAccountsInfo.value?.filter {
                    isValidCoinBaseAccount(it) && it.coinBaseUserAccountData.balance?.amount?.toDouble() != 0.0
                }
            }

        if (userAccountsWithBalanceList.isNullOrEmpty()) {
            _userAccountError.value = true
        } else {
            _userAccountsWithBalance.value = Event(userAccountsWithBalanceList)
        }
    }

    private fun isValidCoinBaseAccount(
        it: CoinBaseUserAccountDataUIModel
    ) = (
        it.coinBaseUserAccountData.balance?.amount?.toDouble() != null &&
            !it.coinBaseUserAccountData.balance.amount.toDouble().isNaN() &&
            it.coinBaseUserAccountData.balance.currency != "DASH"
        )

    fun setSelectedCryptoCurrency(account: CoinBaseUserAccountDataUIModel) {
        maxAmount = try {
            Coin.parseCoin(account.coinBaseUserAccountData.balance?.amount?.toFloat().toString())
        } catch (x: Exception) {
            Coin.ZERO
        }

        _selectedCryptoCurrency.value = account
    }
}
