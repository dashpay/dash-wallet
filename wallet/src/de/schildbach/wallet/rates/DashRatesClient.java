package de.schildbach.wallet.rates;

import com.squareup.moshi.Moshi;

import org.dash.wallet.common.data.entity.ExchangeRate;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import retrofit2.Call;
import retrofit2.converter.moshi.MoshiConverterFactory;
import retrofit2.http.GET;

/**
 * @author Samuel Barbosa
 */
public class DashRatesClient extends RetrofitClient implements ExchangeRatesClient {

    private static DashRatesClient instance;

    public static DashRatesClient getInstance() {
        if (instance == null) {
            instance = new DashRatesClient();
        }
        return instance;
    }

    private DashRatesService dashRatesService;

    private DashRatesClient() {
        super("https://api.get-spark.com/");
        Moshi moshi = moshiBuilder.add(new ExchangeRateListMoshiAdapter()).build();
        retrofit = retrofitBuilder.addConverterFactory(MoshiConverterFactory.create(moshi)).build();
        dashRatesService = retrofit.create(DashRatesService.class);
    }

    @Override
    @NotNull
    public List<ExchangeRate> getRates() throws Exception {
        List<ExchangeRate> rates = dashRatesService.getRates().execute().body();
        if (rates == null || rates.isEmpty()) {
            throw new IllegalStateException("Failed to fetch prices from DashRates source");
        }
        return rates;
    }

    private interface DashRatesService {
        @GET("list")
        Call<List<ExchangeRate>> getRates();
    }

}
