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
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.schildbach.wallet.rates.ExchangeRatesRepository
import org.dash.wallet.common.data.ExchangeRate

/**
 * @author Samuel Barbosa
 */
class ExchangeRatesViewModel : ViewModel() {
    private val exchangeRatesRepository = ExchangeRatesRepository.instance

    val rates: LiveData<List<ExchangeRate>>
        get() = exchangeRatesRepository.rates

    val isLoading: LiveData<Boolean>
        get() = exchangeRatesRepository.isLoading

    val hasError: LiveData<Boolean>
        get() = exchangeRatesRepository.hasError

    fun getRate(currencyCode: String?): LiveData<ExchangeRate> {
        return exchangeRatesRepository.getRate(currencyCode)
    }

    fun searchRates(query: String?): LiveData<List<ExchangeRate>> {
        return if (query != null) {
            exchangeRatesRepository.searchRates(query)
        } else {
            exchangeRatesRepository.rates
        }
    }
}