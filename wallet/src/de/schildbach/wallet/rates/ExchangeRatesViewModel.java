/*
 * Copyright © 2019 Dash Core Group. All rights reserved.
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

package de.schildbach.wallet.rates;

import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.ViewModel;

import java.util.List;

/**
 * @author Samuel Barbosa
 */
public class ExchangeRatesViewModel extends ViewModel {

    private ExchangeRatesRepository exchangeRatesRepository;

    public ExchangeRatesViewModel() {
        exchangeRatesRepository = ExchangeRatesRepository.getInstance();
    }

    public LiveData<List<ExchangeRate>> getRates() {
        return exchangeRatesRepository.getRates();
    }

    public LiveData<ExchangeRate> getRate(String currencyCode) {
        return exchangeRatesRepository.getRate(currencyCode);
    }

    public LiveData<List<ExchangeRate>> searchRates(String query) {
        if (query != null) {
            return exchangeRatesRepository.searchRates(query);
        } else {
            return exchangeRatesRepository.getRates();
        }
    }

    public LiveData<Boolean> isLoading() {
        return exchangeRatesRepository.isLoading;
    }

    public LiveData<Boolean> hasError() {
        return exchangeRatesRepository.hasError;
    }

}
