package de.schildbach.wallet.rates;

import java.util.List;

import retrofit2.Call;
import retrofit2.http.GET;

/**
 * @author Samuel Barbosa
 */
public interface DashRatesService {

    @GET("list")
    Call<List<ExchangeRate>> getRates();

}
