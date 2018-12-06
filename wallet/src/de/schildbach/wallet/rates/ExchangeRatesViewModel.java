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
        //TODO: Implement refresh logic (only if needed to refresh rates while app is focused).
        return exchangeRatesRepository.getRates();
    }

    public LiveData<ExchangeRate> getRate(String currencyCode) {
        return exchangeRatesRepository.getRate(currencyCode);
    }

}
