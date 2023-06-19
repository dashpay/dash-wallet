package de.schildbach.wallet.rates;

import com.squareup.moshi.Moshi;

import de.schildbach.wallet.adapter.BigDecimalAdapter;

import java.io.IOException;

import retrofit2.Call;
import retrofit2.Response;
import retrofit2.converter.moshi.MoshiConverterFactory;
import retrofit2.http.GET;

/**
 * @author Samuel Barbosa
 */
public class PoloniexClient extends RetrofitClient {

    private static PoloniexClient instance;

    public static PoloniexClient getInstance() {
        if (instance == null) {
            instance = new PoloniexClient("https://poloniex.com/");
        }
        return instance;
    }

    private PoloniexService service;

    private PoloniexClient(String baseUrl) {
        super(baseUrl);

        Moshi moshi = moshiBuilder.add(new BigDecimalAdapter()).build();
        retrofit = retrofitBuilder.addConverterFactory(MoshiConverterFactory.create(moshi)).build();
        service = retrofit.create(PoloniexService.class);
    }

    public Response<PoloniexResponse> getRates() throws IOException {
        return service.getRate().execute();
    }

    private interface PoloniexService {
        @GET("public?command=returnOrderBook&currencyPair=BTC_DASH&depth=1")
        Call<PoloniexResponse> getRate();
    }

}
