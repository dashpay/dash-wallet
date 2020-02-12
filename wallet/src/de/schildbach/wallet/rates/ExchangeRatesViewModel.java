package de.schildbach.wallet.rates;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModel;

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
