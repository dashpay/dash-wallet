package de.schildbach.wallet.rates;

import android.arch.lifecycle.LiveData;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import retrofit2.Response;

/**
 * @author Samuel Barbosa
 */
public class ExchangeRatesRepository {

    private static ExchangeRatesRepository instance;

    private AppDatabase appDatabase;
    private Executor executor;
    private Deque<ExchangeRatesClient> exchangeRatesClients = new ArrayDeque<>();

    private ExchangeRatesRepository() {
        appDatabase = AppDatabase.getAppDatabase();
        executor = Executors.newSingleThreadExecutor();

        populateExchangeRatesStack();
    }

    public static ExchangeRatesRepository getInstance() {
        if (instance == null) {
            instance = new ExchangeRatesRepository();
        }
        return instance;
    }

    private void populateExchangeRatesStack() {
        if (!exchangeRatesClients.isEmpty()) {
            exchangeRatesClients.clear();
        }
        //TODO: Push fallbacks first
        exchangeRatesClients.push(DashRatesClient.getInstance());
    }

    public LiveData<List<ExchangeRate>> getRates() {
        refreshRates();

        return appDatabase.exchangeRatesDao().getAll();
    }

    private void refreshRates() {
        //TODO: When Populate list again?
        //Maybe only when restarting the app
        if (!exchangeRatesClients.isEmpty()) {
            final ExchangeRatesClient exchangeRatesClient = exchangeRatesClients.pop();
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    //TODO: Check if needs to Fetch rates (or before calling this method?)
                    Response<List<ExchangeRate>> response = null;
                    try {
                        response = exchangeRatesClient.getRates();
                        List<ExchangeRate> rates = response.body();
                        if (response.isSuccessful() && rates != null && !rates.isEmpty()) {
                            appDatabase.exchangeRatesDao().insertAll(rates);
                        } else if (!exchangeRatesClients.isEmpty()) {
                            refreshRates();
                        } else {
                            //TODO: (?) Handle error? Show notification to user?
                        }
                    } catch (IOException e) {
                        //TODO: Handle Error
                        refreshRates();
                    }
                }
            });
        }
    }

    public LiveData<ExchangeRate> getRate(String currencyCode) {
        //TODO: Check if it's empty before returning.
        //TODO: Check if we need to call refresh here
        return appDatabase.exchangeRatesDao().getRate(currencyCode);
    }


}
