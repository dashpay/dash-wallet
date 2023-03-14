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

package de.schildbach.wallet.ui.rates

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.rates.ExchangeRatesRepository
import org.dash.wallet.common.data.entity.ExchangeRate
import javax.inject.Inject

/**
 * @author Samuel Barbosa
 */
@Deprecated("Inject ExchangeRatesProvider into your viewModel instead")
@HiltViewModel
class ExchangeRatesViewModel @Inject constructor(
    private val exchangeRatesRepository: ExchangeRatesRepository
) : ViewModel() {

    val rates: LiveData<List<ExchangeRate>>
        get() = exchangeRatesRepository.observeExchangeRates().asLiveData()

    val isLoading: LiveData<Boolean>
        get() = exchangeRatesRepository.isLoading

    val hasError: LiveData<Boolean>
        get() = exchangeRatesRepository.hasError

    fun getRate(currencyCode: String?): LiveData<ExchangeRate> {
        currencyCode?.let {
            return exchangeRatesRepository.observeExchangeRate(currencyCode).asLiveData()
        }

        return MutableLiveData()
    }
}