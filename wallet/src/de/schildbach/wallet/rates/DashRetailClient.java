package de.schildbach.wallet.rates;

import androidx.annotation.Nullable;

import com.squareup.moshi.Moshi;

import org.dash.wallet.common.data.BigDecimalAdapter;

import java.util.ArrayList;
import java.util.List;

import retrofit2.Call;
import retrofit2.Response;
import retrofit2.converter.moshi.MoshiConverterFactory;
import retrofit2.http.GET;

public class DashRetailClient extends RetrofitClient implements ExchangeRatesClient {

    private static final String DASH_CURRENCY_SYMBOL = "DASH";

    private static DashRetailClient instance;

    public static DashRetailClient getInstance() {
        if (instance == null) {
            instance = new DashRetailClient("https://rates2.dashretail.org/");
        }
        return instance;
    }

    private DashRetailService service;

    private DashRetailClient(String baseUrl) {
        super(baseUrl);

        Moshi moshi = moshiBuilder.add(new BigDecimalAdapter()).build();
        retrofit = retrofitBuilder.addConverterFactory(MoshiConverterFactory.create(moshi)).build();
        service = retrofit.create(DashRetailService.class);
    }

    @Nullable
    @Override
    public List<ExchangeRate> getRates() throws Exception {
        Response<List<DashRetailRate>> response = service.getRates().execute();
        List<DashRetailRate> rates = response.body();

        if (rates == null || rates.isEmpty()) {
            throw new IllegalStateException("Failed to fetch prices from DashRetail");
        }

        List<ExchangeRate> exchangeRates = new ArrayList<>();
        for (DashRetailRate rate : rates) {
            if (DASH_CURRENCY_SYMBOL.equals(rate.getBaseCurrency())) {
                exchangeRates.add(new ExchangeRate(rate.getQuoteCurrency(), rate.getPrice().toPlainString()));
            }
        }

        return exchangeRates;
    }

    private interface DashRetailService {
        @GET("rates?source=dashretail")
        Call<List<DashRetailRate>> getRates();
    }

}
