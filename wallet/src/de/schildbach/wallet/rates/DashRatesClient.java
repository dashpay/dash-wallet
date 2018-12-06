package de.schildbach.wallet.rates;

import android.support.annotation.Nullable;
import android.support.annotation.WorkerThread;

import com.squareup.moshi.Moshi;

import java.io.IOException;
import java.util.List;

import retrofit2.Response;
import retrofit2.converter.moshi.MoshiConverterFactory;

/**
 * @author Samuel Barbosa
 */
public class DashRatesClient extends ExchangeRatesClient {

    private static DashRatesClient instance;
    private DashRatesService dashRatesService;

    private DashRatesClient() {
        super("https://api.get-spark.com/");
        Moshi moshi = moshiBuilder.add(new ExchangeRateListMoshiAdapter()).build();
        retrofit = retrofitBuilder.addConverterFactory(MoshiConverterFactory.create(moshi)).build();
        dashRatesService = retrofit.create(DashRatesService.class);
    }

    public static DashRatesClient getInstance() {
        if (instance == null) {
            instance = new DashRatesClient();
        }
        return instance;
    }

    @Override
    @WorkerThread
    @Nullable
    public Response<List<ExchangeRate>> getRates() throws IOException {
        return dashRatesService.getRates().execute();
    }
}
