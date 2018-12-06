package de.schildbach.wallet.rates;

import java.io.IOException;
import java.util.List;

import retrofit2.Response;

/**
 * @author Samuel Barbosa
 */
public abstract class ExchangeRatesClient extends RetrofitClient {

    protected ExchangeRatesClient(String baseUrl) {
        super(baseUrl);
    }

    public abstract Response<List<ExchangeRate>> getRates() throws IOException;

}
